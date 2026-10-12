package com.tolu.dpc

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the phone goes while he's out. Leaving home (the geofence) starts a location note every few minutes; coming
 * home stops them and keeps the trip as an outing: when it left, when it came back, the notes. Notes come from Google's
 * location service, like the geofence, so they keep arriving while HiOS has the DPC frozen, and GPS needs no internet.
 * Outings are kept for two weeks, on the phone.
 */
object Trail {
    private const val TAG = "Trail"
    private const val PREFS = "trail"
    private const val EVERY_MS = 4 * 60_000L
    private const val KEEP_MS = 14 * 86_400_000L
    /** Shorter trips than this aren't asked about. */
    const val ASK_AFTER_MIN = 30

    data class Point(val lat: Double, val lon: Double, val at: Long)

    /** A place the phone stayed: at least STOP_MIN minutes within STOP_M metres. */
    data class Stop(val lat: Double, val lon: Double, val from: Long, val to: Long) {
        val minutes get() = ((to - from) / 60_000L).toInt()
        val key get() = "$from"
    }

    data class Outing(val from: Long, val to: Long, val points: List<Point>, val answers: Map<String, Kind>, val test: Boolean = false) {
        val id get() = from
        val minutes get() = ((to - from) / 60_000L).toInt()
        val stops: List<Stop> get() = stopsOf(points)
    }

    private const val STOP_M = 120.0
    private const val STOP_MIN = 8

    // ── Out and back ──────────────────────────────────────────────────────────

    /** Left home: start noting. */
    fun start(context: Context, at: Long) {
        val p = prefs(context)
        if (p.getLong("out_at", 0L) == 0L) p.edit().putLong("out_at", at).putString("points", "[]").apply()
        request(context)
    }

    /** Came home: stop noting, keep the trip, and ask about it if it needs asking. */
    fun stop(context: Context, at: Long) {
        val p = prefs(context)
        val from = p.getLong("out_at", 0L)
        runCatching { LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(pendingIntent(context)) }
        if (from == 0L) return
        val points = JSONArray(p.getString("points", "[]"))
        p.edit().putLong("out_at", 0L).putString("points", "[]").apply()
        if (at - from < 5 * 60_000L) return                       // in and out of the circle's edge
        val outings = JSONArray(p.getString("outings", "[]"))
        outings.put(JSONObject().put("from", from).put("to", at).put("points", points).put("answers", JSONObject()))
        save(context, outings)
        Log.d(TAG, "outing ${(at - from) / 60_000} min, ${points.length()} notes")
        Ask.cameHome(context, from)
    }

    /**
     * adb test (ACTION_TEST_OUTING): a trip that ended just now, `minutes` long, with a stop of each length in `stops`
     * (minutes) about 400 m apart round (lat, lon), as if he'd just come home. Goes through the same asking as a real one.
     */
    fun pretend(context: Context, minutes: Int, stops: List<Int>, lat: Double, lon: Double, endedAgo: Int = 0) {
        val to = System.currentTimeMillis() - endedAgo * 60_000L; val from = to - minutes * 60_000L
        val pts = JSONArray()
        var t = from + 5 * 60_000L
        stops.forEachIndexed { i, len ->
            val la = lat + 0.0036 * i; val lo = lon + 0.0021 * (i % 2)
            var k = 0L
            while (k <= len) { pts.put(JSONArray().put(la + 0.00005 * (k % 3)).put(lo).put(t + k * 60_000L)); k += 4 }
            t += (len + 10) * 60_000L
        }
        val outings = JSONArray(prefs(context).getString("outings", "[]"))
        outings.put(JSONObject().put("from", from).put("to", to).put("points", pts).put("answers", JSONObject()).put("test", true))
        save(context, outings)
        Ask.cameHome(context, from)
    }

    /** adb (ACTION_TEST_CLEAR): forget the test trips. */
    fun forgetTests(context: Context) {
        val a = JSONArray(prefs(context).getString("outings", "[]"))
        val kept = JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optBoolean("test") }.forEach { kept.put(it) }
        prefs(context).edit().putString("outings", kept.toString()).apply()
    }

    /** Still out after a reboot or a Play-services restart: ask for the notes again (the same request replaces itself). */
    fun keep(context: Context) {
        if (prefs(context).getLong("out_at", 0L) != 0L) request(context)
    }

    fun isOut(context: Context) = prefs(context).getLong("out_at", 0L) != 0L

    @SuppressLint("MissingPermission")
    private fun request(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, EVERY_MS)
            .setMinUpdateIntervalMillis(EVERY_MS / 2)
            .setMinUpdateDistanceMeters(0f)
            .build()
        runCatching { LocationServices.getFusedLocationProviderClient(context).requestLocationUpdates(request, pendingIntent(context)) }
            .onFailure { Log.e(TAG, "location notes failed", it) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 8, Intent(context, Receiver::class.java), flags)
    }

    /** Google's location service delivers each note here. Notes worse than 100 m are dropped. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val result = LocationResult.extractResult(intent) ?: return
            val p = prefs(context)
            if (p.getLong("out_at", 0L) == 0L) return
            val a = JSONArray(p.getString("points", "[]"))
            result.locations.filter { !it.hasAccuracy() || it.accuracy <= 100f }.forEach {
                a.put(JSONArray().put(it.latitude).put(it.longitude).put(it.time))
            }
            p.edit().putString("points", a.toString()).apply()
        }
    }

    // ── The trips ─────────────────────────────────────────────────────────────

    fun outings(context: Context): List<Outing> {
        val a = JSONArray(prefs(context).getString("outings", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val pts = o.getJSONArray("points")
            val ans = o.optJSONObject("answers") ?: JSONObject()
            Outing(o.getLong("from"), o.getLong("to"),
                (0 until pts.length()).map { pts.getJSONArray(it) }.map { Point(it.getDouble(0), it.getDouble(1), it.getLong(2)) },
                ans.keys().asSequence().mapNotNull { k -> Kind.of(ans.getString(k))?.let { k to it } }.toMap(), o.optBoolean("test"))
        }
    }

    /** Outings that overlap [from, to). */
    fun between(context: Context, from: Long, to: Long) = outings(context).filter { it.from < to && it.to > from }

    fun outing(context: Context, id: Long) = outings(context).firstOrNull { it.id == id }

    /**
     * His answer for one stop (key: the stop's start), or for the whole outing (key "all"). A stop's answer also
     * teaches the place.
     */
    fun answer(context: Context, id: Long, key: String, kind: Kind) {
        val a = JSONArray(prefs(context).getString("outings", "[]"))
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getLong("from") != id) continue
            (o.optJSONObject("answers") ?: JSONObject().also { o.put("answers", it) }).put(key, kind.name)
        }
        save(context, a)
        // A test trip never teaches a place.
        outing(context, id)?.takeIf { !it.test }?.stops?.firstOrNull { it.key == key }?.let { Places.learn(context, it.lat, it.lon, kind) }
    }

    private fun save(context: Context, outings: JSONArray) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val kept = JSONArray()
        (0 until outings.length()).map { outings.getJSONObject(it) }.filter { it.getLong("to") > cutoff }.forEach { kept.put(it) }
        prefs(context).edit().putString("outings", kept.toString()).apply()
    }

    /** Runs of notes that stay within STOP_M of where they started, for at least STOP_MIN minutes. */
    fun stopsOf(points: List<Point>): List<Stop> {
        val stops = mutableListOf<Stop>()
        var i = 0
        while (i < points.size) {
            var j = i
            while (j + 1 < points.size && Places.metres(points[i].lat, points[i].lon, points[j + 1].lat, points[j + 1].lon) <= STOP_M) j++
            val run = points.subList(i, j + 1)
            if ((run.last().at - run.first().at) / 60_000L >= STOP_MIN) {
                stops += Stop(run.map { it.lat }.average(), run.map { it.lon }.average(), run.first().at, run.last().at)
            }
            i = j + 1
        }
        return stops
    }

    /** For adb (ACTION_AWAY). */
    fun describe(context: Context): String {
        val df = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.UK)
        return "out now: ${isOut(context)}\n" + outings(context).takeLast(8).joinToString("\n") { o ->
            "  ${df.format(o.from)}–${df.format(o.to).takeLast(5)}: ${o.points.size} notes, ${o.stops.size} stops, answers ${o.answers.values.map { it.label }}"
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
