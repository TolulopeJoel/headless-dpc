package com.tolu.dpc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot

/**
 * His return visits and Bible studies, from Field Service itself: the laptop reads Field Service's own automatic backup
 * (AutoBackup.mabackup, gzip + Smile), keeps each person's name, kind, street, where they are (the pin he dropped, or
 * the street's lines from the map) and the latest "next time" note, and pushes it here as files/people.json
 * (tools/fieldservice.py, run by the laptop program whenever the phone is plugged in). Field Service stays the one
 * place he records anything; this only reads it.
 *
 * When he's near someone during field time (or the two hours after), one notification lists who's on that street,
 * how long since the last visit and what he planned to talk about. Each person at most once a day. Stays on the phone.
 */
object People {
    private const val TAG = "People"
    private const val NEAR_PIN_M = 120.0
    private const val NEAR_STREET_M = 60.0
    private const val AFTER_FIELD_MS = 2 * 3_600_000L

    data class Person(val name: String, val study: Boolean, val street: String, val pin: Boolean,
                      val lines: List<List<Pair<Double, Double>>>, val last: Long, val visits: Int, val next: String,
                      val note: String = "", val dates: List<Pair<Long, Boolean>> = emptyList())

    @Volatile private var cache: Pair<Long, List<Person>>? = null

    fun file(context: Context) = File(context.filesDir, "people.json")

    fun all(context: Context): List<Person> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        cache?.let { (at, list) -> if (at == f.lastModified()) return list }
        val list = runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val w = o.getJSONArray("where")
                val lines = if (o.optBoolean("pin")) listOf(listOf(w.getJSONArray(0).let { it.getDouble(0) to it.getDouble(1) }))
                    else (0 until w.length()).map { i -> w.getJSONArray(i).let { l -> (0 until l.length()).map { j -> l.getJSONArray(j).let { it.getDouble(0) to it.getDouble(1) } } } }
                val dates = o.optJSONArray("dates")?.let { d -> (0 until d.length()).map { d.getJSONObject(it).let { x -> x.getLong("d") to x.optBoolean("held", true) } } }.orEmpty()
                Person(o.getString("name"), o.getString("kind") == "study", o.optString("street"), o.optBoolean("pin"),
                    lines, o.optLong("last"), o.optInt("visits"), o.optString("next"), o.optString("note"), dates)
            }
        }.onFailure { Log.e(TAG, "people.json unreadable", it) }.getOrDefault(emptyList())
        cache = f.lastModified() to list
        return list
    }

    /** Metres from (la, lo) to the person: to the pin, or to the nearest point on the street's lines. */
    fun distance(p: Person, la: Double, lo: Double): Double {
        val k = cos(Math.toRadians(la)) * 111_320.0
        fun d(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val px = lo * k; val py = la * 111_320.0
            val ax = a.second * k; val ay = a.first * 111_320.0; val bx = b.second * k; val by = b.first * 111_320.0
            val dx = bx - ax; val dy = by - ay; val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0.0, 1.0)
            return hypot(px - ax - t * dx, py - ay - t * dy)
        }
        if (p.lines.isEmpty()) return Double.MAX_VALUE
        return p.lines.minOf { line -> if (line.size == 1) d(line[0], line[0]) else line.zipWithNext().minOf { (a, b) -> d(a, b) } }
    }

    /** In field time, or within two hours after today's last field session (or after he tapped Done). */
    private fun active(context: Context): Boolean = runCatching {
        val now = System.currentTimeMillis()
        if (Field.holding(context) != null) return@runCatching true
        val (start, end) = Field.marks(context, startOfDay(0))
        if (start != null && (end == null || now - end < AFTER_FIELD_MS)) return@runCatching true
        Field.today(context).any { now >= it.start && now < it.end + AFTER_FIELD_MS }
    }.getOrDefault(false)

    /** A new location: remind him of anyone nearby he hasn't been reminded of today. */
    fun near(context: Context, la: Double, lo: Double) {
        if (!active(context)) return
        val prefs = context.getSharedPreferences("people", Context.MODE_PRIVATE)
        val today = startOfDay(0)
        val nearby = all(context).filter { p -> distance(p, la, lo) < if (p.pin) NEAR_PIN_M else NEAR_STREET_M }
            .filter { prefs.getLong("told_${it.name}", 0L) < today }
        if (nearby.isEmpty()) return
        prefs.edit().apply { nearby.forEach { putLong("told_${it.name}", System.currentTimeMillis()) } }.apply()
        notify(context, nearby.sortedWith(compareBy({ !it.study }, { -it.last.coerceAtLeast(0) })))
    }

    fun ago(last: Long): String {
        if (last <= 0) return "not visited yet"
        val days = ((System.currentTimeMillis() - last) / 86_400_000L).toInt()
        return when { days <= 0 -> "today"; days == 1 -> "yesterday"; else -> "$days days ago" }
    }

    private fun notify(context: Context, ps: List<Person>) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("people", "People nearby", NotificationManager.IMPORTANCE_HIGH))
        val streets = ps.map { it.street }.filter { it.isNotBlank() }.distinct()
        val title = if (ps.size == 1) "${ps[0].name} is nearby" else "${ps.size} people nearby" + (streets.singleOrNull()?.let { " · $it" } ?: "")
        val body = ps.joinToString("\n\n") { p ->
            "${p.name} · ${if (p.study) "Bible study" else "return visit"} · ${p.street.ifBlank { "pinned" }} · last ${ago(p.last)}" +
                (if (p.next.isNotBlank()) "\nNext: ${p.next}" else "")
        }
        val open = PendingIntent.getActivity(context, 50,
            Intent(context, PeopleActivity::class.java).putExtra(PeopleActivity.EXTRA_NAMES, ps.map { it.name }.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, "people")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(ps.joinToString(", ") { it.name })
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(4000 + (streets.firstOrNull()?.hashCode() ?: 0) % 1000, n) }
        Log.d(TAG, "reminded: ${ps.map { it.name }}")
    }

    /** For adb (ACTION_AWAY): how many people it knows. */
    fun describe(context: Context) = all(context).let { ps -> "people: ${ps.size} (${ps.count { it.study }} studies, ${ps.count { it.pin }} pinned)" }
}
