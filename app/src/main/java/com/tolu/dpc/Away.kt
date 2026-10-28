package com.tolu.dpc

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Home, and time away from it. Google's location service watches a 100 m circle round home and records each time the
 * phone leaves it and comes back, with the time of each, even while HiOS has the DPC frozen; GPS needs no internet.
 * Home is fixed (his house, set 12 Oct); `ACTION_SET_HOME` over adb moves it to wherever the phone is. While he's out,
 * Trail notes where the phone is, so Places can say where he went. All of it stays on the phone.
 */
object Away {
    private const val TAG = "Away"
    private const val PREFS = "away"
    /**
     * 60 m: at home the phone's own readings stay within 46 m of the centre (55 readings, 12 Oct evening), so 60 m
     * holds home and the next street is away. 100 m round the wrong centre counted a street away as home (13 Oct).
     */
    const val DEFAULT_RADIUS_M = 40f
    /** The circle: set by the home survey, or 60 m. */
    fun radius(context: Context): Float = prefs(context).getFloat("radius", DEFAULT_RADIUS_M)
    /** His house as his phone reads it: the middle of 55 readings taken there (12 Oct). */
    private val DEFAULT_HOME = 6.532275 to 3.347183
    /** A location note every 5 minutes, all day: the in/out alerts get dropped by HiOS, these get through. */
    private const val PULSE_MS = 5 * 60_000L
    private const val PULSE_KEEP_MS = 3 * 86_400_000L
    /** Notes in a row beyond the circle before he counts as out: indoors one or two can stray (12 Oct). */
    private const val LEAVE_AFTER = 3
    /** The witnessing window: 06:30 to 10:00 (09:30 until he widened it on 10 Oct). */
    const val FROM = 6 * 60 + 30
    const val TO = 10 * 60
    const val NEEDED_MINUTES = 120

    // ── Home ──────────────────────────────────────────────────────────────────

    /** Home: the point set over adb, or his house. */
    fun home(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        if (!p.contains("home_lat")) return DEFAULT_HOME
        return Double.fromBits(p.getLong("home_lat", 0L)) to Double.fromBits(p.getLong("home_lon", 0L))
    }

    /** `ACTION_SET_HOME` (adb): home becomes wherever the phone is now. */
    @SuppressLint("MissingPermission")
    fun noteHome(context: Context, force: Boolean = false) {
        if (!force || !canLocate(context)) return
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null) return@addOnSuccessListener
                prefs(context).edit().putLong("home_lat", loc.latitude.toBits()).putLong("home_lon", loc.longitude.toBits()).apply()
                Log.d(TAG, "home set (±${loc.accuracy.toInt()} m)")
                watch(context, force = true)
            }
    }

    // ── The circle ────────────────────────────────────────────────────────────

    /** Ask Google's location service to watch the circle round home. Repeated now and then, since a reboot clears it. */
    @SuppressLint("MissingPermission")
    fun watch(context: Context, force: Boolean = false) {
        if (!canLocate(context)) return
        val (lat, lon) = home(context) ?: return
        // The notes first: this used to sit at the end, after the early return, so they were renewed only every 6 hours.
        pulse(context)
        val p = prefs(context)
        val key = "%.5f,%.5f,%.0f".format(lat, lon, radius(context))
        if (!force && p.getString("watching", null) == key && System.currentTimeMillis() - p.getLong("watched_at", 0L) < 6 * 3_600_000L) return
        val fence = Geofence.Builder()
            .setRequestId("home")
            // The alerts are only a backup to the notes: a wider ring, so one stray alert can't say "out" at home.
            .setCircularRegion(lat, lon, radius(context) * 1.5f)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofence(fence)
            .build()
        LocationServices.getGeofencingClient(context).addGeofences(request, pendingIntent(context))
            .addOnSuccessListener { p.edit().putString("watching", key).putLong("watched_at", System.currentTimeMillis()).apply() }
            .addOnFailureListener { Log.e(TAG, "watching home failed", it) }
    }

    // ── The home survey ───────────────────────────────────────────────────────

    /**
     * He walks every corner of the house with the phone while this runs (ACTION_SURVEY start / stop over adb): a precise
     * reading every few seconds. On stop, home becomes the middle of them, and the circle reaches the farthest reading
     * plus 15 m for the wobble GPS has indoors (at least 40 m, at most 90).
     */
    @SuppressLint("MissingPermission")
    fun surveyStart(context: Context) {
        if (!canLocate(context)) return
        prefs(context).edit().putString("survey", "[]").putBoolean("surveying", true).apply()
        val request = com.google.android.gms.location.LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateIntervalMillis(2_000L).build()
        LocationServices.getFusedLocationProviderClient(context).requestLocationUpdates(request, surveyIntent(context))
        Log.d(TAG, "survey started")
    }

    fun surveyStop(context: Context): String {
        LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(surveyIntent(context))
        val p = prefs(context)
        p.edit().putBoolean("surveying", false).apply()
        val a = JSONArray(p.getString("survey", "[]"))
        val pts = (0 until a.length()).map { a.getJSONArray(it) }.filter { it.getDouble(3) <= 25.0 }.map { it.getDouble(0) to it.getDouble(1) }
        if (pts.size < 10) return "survey: only ${pts.size} good readings; nothing changed"
        val lat = pts.map { it.first }.sorted()[pts.size / 2]
        val lon = pts.map { it.second }.sorted()[pts.size / 2]
        val far = pts.maxOf { Places.metres(lat, lon, it.first, it.second) }
        // At least 40 m: with three checks in a row needed to count as out, 40 m never wrongly said "out" on an
        // ordinary evening at home (55 readings, 12 Oct); 30 and 35 m did.
        val r = (far + 15.0).coerceIn(40.0, 90.0).toFloat()
        p.edit().putLong("home_lat", lat.toBits()).putLong("home_lon", lon.toBits()).putFloat("radius", r).apply()
        watch(context, force = true)
        return "survey: ${pts.size} readings, farthest ${far.toInt()} m from the middle; home circle now ${r.toInt()} m"
    }

    private fun surveyIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 10, Intent(context, Survey::class.java), flags)
    }

    class Survey : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val result = com.google.android.gms.location.LocationResult.extractResult(intent) ?: return
            val p = prefs(context)
            if (!p.getBoolean("surveying", false)) return
            val a = JSONArray(p.getString("survey", "[]"))
            result.locations.forEach { a.put(JSONArray().put(it.latitude).put(it.longitude).put(it.time).put(if (it.hasAccuracy()) it.accuracy.toDouble() else 99.0)) }
            p.edit().putString("survey", a.toString()).apply()
        }
    }

    // ── The pulse: a note every 5 minutes ─────────────────────────────────────

    /** Ask Google's location service for a note every 5 minutes, all day. Re-asked every half hour (a reboot or an update clears it). */
    @SuppressLint("MissingPermission")
    fun pulse(context: Context, force: Boolean = false) {
        if (!canLocate(context)) return
        val p = prefs(context)
        if (!force && System.currentTimeMillis() - p.getLong("pulse_at", 0L) < 30 * 60_000L) return
        val request = com.google.android.gms.location.LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, PULSE_MS)
            .setMinUpdateIntervalMillis(PULSE_MS / 2).setMaxUpdateDelayMillis(PULSE_MS).build()
        runCatching {
            LocationServices.getFusedLocationProviderClient(context).requestLocationUpdates(request, pulseIntent(context))
            p.edit().putLong("pulse_at", System.currentTimeMillis()).apply()
        }.onFailure { Log.e(TAG, "pulse failed", it) }
    }

    private fun pulseIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 9, Intent(context, Pulse::class.java), flags)
    }

    /**
     * Each note: kept (3 days), and it decides out or home by itself, so a dropped alert can't hide a trip. Notes
     * worse than 80 m are skipped; leaving needs a note beyond the circle, coming back one well inside it.
     */
    class Pulse : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val result = com.google.android.gms.location.LocationResult.extractResult(intent) ?: return
            val (hLat, hLon) = home(context) ?: return
            val p = prefs(context)
            val a = JSONArray(p.getString("notes", "[]"))
            result.locations.sortedBy { it.time }.forEach { loc ->
                val d = Places.metres(hLat, hLon, loc.latitude, loc.longitude)
                val r = radius(context)
                // A rough fix (phone masts only: Wi-Fi and its scanning were off, 16 Oct, and he left at 10–11 unseen
                // till 13:57) can't place him, but it can still prove he's out: even its nearest edge is beyond the circle.
                if (loc.hasAccuracy() && loc.accuracy > 80f) {
                    if (!isOut(context) && d - loc.accuracy > r) {
                        val first = p.getLong("run_from", 0L).takeIf { it > 0 } ?: loc.time
                        val run = p.getInt("run", 0) + 1
                        p.edit().putLong("run_from", first).putInt("run", run).apply()
                        if (run >= LEAVE_AFTER) {
                            record(context, first, true); Trail.start(context, first)
                            p.edit().remove("run_from").remove("run").apply()
                        }
                    }
                    return@forEach
                }
                a.put(JSONArray().put(loc.latitude).put(loc.longitude).put(loc.time).put(d.toInt()))
                if (isOut(context)) {
                    // Back: one note well inside the circle (a wrong guess rarely lands on the house).
                    if (d < r * 0.75) { record(context, loc.time, false); Trail.stop(context, loc.time) }
                } else if (d > r) {
                    // Out: only after LEAVE_AFTER notes in a row beyond the circle; dated from the first of them.
                    val first = p.getLong("run_from", 0L).takeIf { it > 0 } ?: loc.time
                    val run = p.getInt("run", 0) + 1
                    p.edit().putLong("run_from", first).putInt("run", run).apply()
                    if (run >= LEAVE_AFTER) {
                        record(context, first, true); Trail.start(context, first)
                        p.edit().remove("run_from").remove("run").apply()
                    }
                } else p.edit().remove("run_from").remove("run").apply()
            }
            result.lastLocation?.let { runCatching { People.near(context, it.latitude, it.longitude) } }
            val cutoff = System.currentTimeMillis() - PULSE_KEEP_MS
            val kept = JSONArray()
            (0 until a.length()).map { a.getJSONArray(it) }.filter { it.getLong(2) > cutoff }.forEach { kept.put(it) }
            p.edit().putString("notes", kept.toString()).apply()
        }
    }

    /** Where the phone last was, from the 5-minute notes (or null). */
    fun lastSeen(context: Context): Pair<Double, Double>? {
        val a = JSONArray(prefs(context).getString("notes", "[]"))
        if (a.length() == 0) return null
        return a.getJSONArray(a.length() - 1).let { it.getDouble(0) to it.getDouble(1) }
    }

    /** Out, by the last recorded move. */
    private fun isOut(context: Context): Boolean {
        val a = JSONArray(prefs(context).getString("moves", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }.maxByOrNull { it.getLong("at") }?.getBoolean("out") ?: false
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 7, Intent(context, Receiver::class.java), flags)
    }

    /** Google's location service calls this on leaving and coming home; it may arrive late, but the time is the fix's. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event = GeofencingEvent.fromIntent(intent) ?: return
            if (event.hasError()) { Log.e(TAG, "geofence error ${event.errorCode}"); return }
            val out = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT
            if (isOut(context) == out) return
            // An alert alone isn't believed: with Wi-Fi and its scanning off, Google guessed from phone masts, hundreds
            // of metres out, and "came home" while he was still out (17 Oct). One precise fix decides: back only well
            // inside the circle, out only when even its nearest edge is beyond it.
            val pending = goAsync()
            LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    runCatching {
                        val (hLat, hLon) = home(context) ?: return@runCatching
                        if (loc == null || (loc.hasAccuracy() && loc.accuracy > 80f && !out)) { Log.d(TAG, "alert ${if (out) "out" else "back"} not confirmed (no precise fix)"); return@runCatching }
                        val d = Places.metres(hLat, hLon, loc.latitude, loc.longitude)
                        val r = radius(context)
                        val sure = if (out) d - (if (loc.hasAccuracy()) loc.accuracy else 0f) > r else d < r * 0.75
                        Log.d(TAG, "alert ${if (out) "out" else "back"}: ${d.toInt()} m ±${loc.accuracy.toInt()} -> ${if (sure) "yes" else "no"}")
                        if (sure && isOut(context) != out) {
                            record(context, loc.time, out)
                            if (out) Trail.start(context, loc.time) else Trail.stop(context, loc.time)
                        }
                    }
                    pending.finish()
                }
                .addOnFailureListener { pending.finish() }
        }
    }

    private fun record(context: Context, at: Long, out: Boolean) {
        if (isOut(context) == out) return          // already known (the pulse and the alert both saw it)
        val p = prefs(context)
        val a = JSONArray(p.getString("moves", "[]"))
        a.put(JSONObject().put("at", at).put("out", out))
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        val kept = JSONArray().also { k -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getLong("at") > weekAgo }.forEach { k.put(it) } }
        p.edit().putString("moves", kept.toString()).apply()
        Log.d(TAG, "${if (out) "left" else "came"} home at $at")
    }

    // ── The count ─────────────────────────────────────────────────────────────

    /** Minutes away from home today between 06:30 and 10:00 (up to now, if still out). */
    fun minutesAwayThisMorning(context: Context): Int = minutesAway(context, todayAt(FROM), todayAt(TO))

    /** Minutes away from home between two times: by the notes or by the moves, whichever saw more. */
    fun minutesAway(context: Context, from: Long, until: Long): Int = maxOf(minutesAwayByMoves(context, from, until), minutesAwayByNotes(context, from, until))

    /** From the 5-minute notes: runs of LEAVE_AFTER or more notes beyond the circle, each counting until the next note (at most 15 minutes). */
    private fun minutesAwayByNotes(context: Context, from: Long, until: Long): Int {
        val to = minOf(until, System.currentTimeMillis())
        val r = radius(context)
        val a = JSONArray(prefs(context).getString("notes", "[]"))
        val notes = (0 until a.length()).map { a.getJSONArray(it) }.map { it.getLong(2) to (it.getInt(3) > r) }.sortedBy { it.first }
        // Which notes belong to a run long enough to count.
        val counts = BooleanArray(notes.size)
        var i = 0
        while (i < notes.size) {
            if (!notes[i].second) { i++; continue }
            var j = i
            while (j + 1 < notes.size && notes[j + 1].second) j++
            if (j - i + 1 >= LEAVE_AFTER) for (k in i..j) counts[k] = true
            i = j + 1
        }
        var total = 0L
        notes.forEachIndexed { k, (at, _) ->
            if (!counts[k]) return@forEachIndexed
            val end = minOf(notes.getOrNull(k + 1)?.first ?: to, at + 15 * 60_000L, to)
            val start = maxOf(at, from)
            if (end > start) total += end - start
        }
        return (total / 60_000L).toInt()
    }

    private fun minutesAwayByMoves(context: Context, from: Long, until: Long): Int {
        val to = minOf(until, System.currentTimeMillis())
        if (to <= from) return 0
        val a = JSONArray(prefs(context).getString("moves", "[]"))
        val moves = (0 until a.length()).map { a.getJSONObject(it) }.map { it.getLong("at") to it.getBoolean("out") }.sortedBy { it.first }
        var out = moves.lastOrNull { it.first <= from }?.second ?: false
        var since = from
        var total = 0L
        for ((at, isOut) in moves.filter { it.first in (from + 1) until to }) {
            if (out) total += at - since
            out = isOut
            since = at
        }
        if (out) total += to - since
        return (total / 60_000L).toInt()
    }

    fun knowsHome(context: Context) = home(context) != null

    private fun todayAt(minute: Int) = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ── Keeping it working ────────────────────────────────────────────────────

    /** The DPC grants itself location and keeps location switched on (the switch itself is locked in ALWAYS_RESTRICTIONS). */
    fun keepLocationOn(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION).forEach {
            if (context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED) runCatching {
                dpm.setPermissionGrantState(admin, context.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }.onFailure { e -> Log.e(TAG, "granting $it failed", e) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { dpm.setLocationEnabled(admin, true) }
    }

    private fun canLocate(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** For adb (ACTION_AWAY): what it knows, without the coordinates. */
    fun describe(context: Context): String {
        val a = JSONArray(prefs(context).getString("moves", "[]"))
        val moves = (0 until a.length()).map { a.getJSONObject(it) }.joinToString("\n") {
            "  ${java.util.Date(it.getLong("at"))}: ${if (it.getBoolean("out")) "left" else "came"} home"
        }
        val notes = JSONArray(prefs(context).getString("notes", "[]"))
        return "notes: ${notes.length()} (last ${if (notes.length() > 0) java.util.Date(notes.getJSONArray(notes.length() - 1).getLong(2)) else "none"}, " +
            "${if (notes.length() > 0) notes.getJSONArray(notes.length() - 1).getInt(3) else -1} m)\n" +
            "home: ${if (prefs(context).contains("home_lat")) "set over adb" else "his house"}, ${radius(context).toInt()} m, watching: ${prefs(context).getString("watching", null) != null}, " +
            "can locate: ${canLocate(context)}\naway this morning (06:30–10:00): ${minutesAwayThisMorning(context)} min\n$moves"
    }
}
