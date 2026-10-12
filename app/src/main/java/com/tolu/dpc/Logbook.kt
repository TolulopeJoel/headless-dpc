package com.tolu.dpc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Personal study and meeting prep, logged by him: only he knows whether time in JW Library was study, prep or
 * browsing, so a session counts only when he starts it (Now screen) and stops it (topic, or which meeting). One
 * he forgot to start can be added in the 20:01 review; it's marked as added later. Kept for five weeks.
 */
object Logbook {
    private const val PREFS = "logbook"
    private const val KEEP_MS = 35 * 86_400_000L

    enum class Meeting(val label: String) { MIDWEEK("Midweek"), WATCHTOWER("Watchtower") }

    data class Entry(val kind: Kind, val from: Long, val to: Long, val topic: String, val late: Boolean) {
        val minutes get() = ((to - from) / 60_000L).toInt()
        val title get() = if (topic.isBlank()) kind.label else "${kind.label} · $topic"
    }

    // ── Running ───────────────────────────────────────────────────────────────

    /** The session running now: its kind and when it started. */
    fun running(context: Context): Pair<Kind, Long>? {
        val p = prefs(context)
        val kind = Kind.of(p.getString("running", null)) ?: return null
        return kind to p.getLong("since", 0L)
    }

    fun start(context: Context, kind: Kind) {
        require(kind == Kind.STUDY || kind == Kind.PREP)
        prefs(context).edit().putString("running", kind.name).putLong("since", System.currentTimeMillis()).apply()
    }

    /** Stop the running session and keep it, with its topic (study) or meeting (prep). Under a minute isn't kept. */
    fun stop(context: Context, topic: String) {
        val (kind, since) = running(context) ?: return
        prefs(context).edit().remove("running").remove("since").apply()
        if (System.currentTimeMillis() - since >= 60_000L) add(context, Entry(kind, since, System.currentTimeMillis(), topic.trim(), late = false))
    }

    fun cancel(context: Context) = prefs(context).edit().remove("running").remove("since").apply()

    // ── Kept ──────────────────────────────────────────────────────────────────

    fun add(context: Context, entry: Entry) {
        val a = JSONArray(prefs(context).getString("entries", "[]"))
        a.put(JSONObject().put("kind", entry.kind.name).put("from", entry.from).put("to", entry.to).put("topic", entry.topic).put("late", entry.late))
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val kept = JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getLong("to") > cutoff }.forEach { kept.put(it) }
        prefs(context).edit().putString("entries", kept.toString()).apply()
    }

    /** adb (ACTION_TEST_CLEAR with "since"): forget entries that started after `since` (test sessions). */
    fun forgetSince(context: Context, since: Long) {
        val a = JSONArray(prefs(context).getString("entries", "[]"))
        val kept = JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getLong("from") < since && it.getLong("to") < since }.forEach { kept.put(it) }
        prefs(context).edit().putString("entries", kept.toString()).apply()
    }

    fun between(context: Context, from: Long, to: Long): List<Entry> {
        val a = JSONArray(prefs(context).getString("entries", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Entry(Kind.valueOf(it.getString("kind")), it.getLong("from"), it.getLong("to"), it.optString("topic"), it.optBoolean("late")) }
            .filter { it.from < to && it.to > from }.sortedBy { it.from }
    }

    /** Recent study topics, newest first, for one-tap choices. */
    fun recentTopics(context: Context, n: Int = 4): List<String> =
        between(context, System.currentTimeMillis() - KEEP_MS, Long.MAX_VALUE).filter { it.kind == Kind.STUDY && it.topic.isNotBlank() }
            .sortedByDescending { it.from }.map { it.topic }.distinct().take(n)

    /** The meeting prep is most likely for: Midweek from Sunday afternoon to Thursday, Watchtower from Thursday night to Sunday. */
    fun nextMeeting(): Meeting {
        val c = java.util.Calendar.getInstance()
        val dow = c.get(java.util.Calendar.DAY_OF_WEEK)
        val hour = c.get(java.util.Calendar.HOUR_OF_DAY)
        return when (dow) {
            java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY, java.util.Calendar.WEDNESDAY -> Meeting.MIDWEEK
            java.util.Calendar.THURSDAY -> if (hour < 20) Meeting.MIDWEEK else Meeting.WATCHTOWER
            java.util.Calendar.SUNDAY -> if (hour < 14) Meeting.WATCHTOWER else Meeting.MIDWEEK
            else -> Meeting.WATCHTOWER
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
