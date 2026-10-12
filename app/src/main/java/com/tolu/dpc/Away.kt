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
 * The proof behind the witnessing pass: time away from home. Google's location service watches a circle round home
 * and records each time the phone leaves it and comes back, with the time of each, even while HiOS has the DPC frozen;
 * GPS needs no internet. Home is learned from where the phone is during the Bible hour (05:00–06:30). Only home and
 * the leaving and returning times are kept, on the phone; never where he went.
 */
object Away {
    private const val TAG = "Away"
    private const val PREFS = "away"
    private const val RADIUS_M = 200f
    /** The witnessing window: 06:30 to 09:30. */
    const val FROM = 6 * 60 + 30
    const val TO = 9 * 60 + 30
    const val NEEDED_MINUTES = 120

    // ── Home ──────────────────────────────────────────────────────────────────

    /** Home: the middle of the last week's Bible-hour positions, so one night elsewhere doesn't move it. */
    fun home(context: Context): Pair<Double, Double>? {
        val samples = samples(context)
        if (samples.isEmpty()) return null
        fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]
        return median(samples.map { it.first }) to median(samples.map { it.second })
    }

    private fun samples(context: Context): List<Pair<Double, Double>> {
        val a = JSONArray(prefs(context).getString("home_samples", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map { it.getDouble("lat") to it.getDouble("lon") }
    }

    /** During the Bible hour, once a day, note where the phone is. `force` (adb) notes it now, whatever the time. */
    @SuppressLint("MissingPermission")
    fun noteHome(context: Context, force: Boolean = false) {
        if (!canLocate(context)) return
        val p = prefs(context)
        val minute = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (!force && (minute < 5 * 60 || minute >= FROM || p.getInt("home_day", -1) == today)) return
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null) return@addOnSuccessListener
                val a = JSONArray(p.getString("home_samples", "[]"))
                a.put(JSONObject().put("lat", loc.latitude).put("lon", loc.longitude).put("at", System.currentTimeMillis()))
                val kept = JSONArray().also { k -> (maxOf(0, a.length() - 7) until a.length()).forEach { k.put(a.get(it)) } }
                p.edit().putString("home_samples", kept.toString()).putInt("home_day", today).apply()
                Log.d(TAG, "home noted (±${loc.accuracy.toInt()} m)")
                watch(context, force = true)
            }
    }

    // ── The circle ────────────────────────────────────────────────────────────

    /** Ask Google's location service to watch the circle round home. Repeated now and then, since a reboot clears it. */
    @SuppressLint("MissingPermission")
    fun watch(context: Context, force: Boolean = false) {
        if (!canLocate(context)) return
        val (lat, lon) = home(context) ?: return
        val p = prefs(context)
        val key = "%.4f,%.4f".format(lat, lon)
        if (!force && p.getString("watching", null) == key && System.currentTimeMillis() - p.getLong("watched_at", 0L) < 6 * 3_600_000L) return
        val fence = Geofence.Builder()
            .setRequestId("home")
            .setCircularRegion(lat, lon, RADIUS_M)
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
            val at = event.triggeringLocation?.time ?: System.currentTimeMillis()
            record(context, at, out)
        }
    }

    private fun record(context: Context, at: Long, out: Boolean) {
        val p = prefs(context)
        val a = JSONArray(p.getString("moves", "[]"))
        a.put(JSONObject().put("at", at).put("out", out))
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        val kept = JSONArray().also { k -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getLong("at") > weekAgo }.forEach { k.put(it) } }
        p.edit().putString("moves", kept.toString()).apply()
        Log.d(TAG, "${if (out) "left" else "came"} home at $at")
    }

    // ── The count ─────────────────────────────────────────────────────────────

    /** Minutes away from home today between 06:30 and 09:30 (up to now, if still out). */
    fun minutesAwayThisMorning(context: Context): Int {
        val from = todayAt(FROM)
        val to = minOf(todayAt(TO), System.currentTimeMillis())
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
        return "home known: ${knowsHome(context)} (${samples(context).size} samples), watching: ${prefs(context).getString("watching", null) != null}, " +
            "can locate: ${canLocate(context)}\naway this morning (06:30–09:30): ${minutesAwayThisMorning(context)} min\n$moves"
    }
}
