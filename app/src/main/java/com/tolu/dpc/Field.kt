package com.tolu.dpc

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Instances
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Field sessions, read from the calendar: an event whose title starts with "Field" (a weekly "Field" on Tue, Wed, Fri
 * and Sat at 09:00–12:00, or whatever the calendar says). During one, the hotspot is off, so the laptop can't pull him
 * in, and the phone keeps only what the ministry needs. Deleting or moving a session on its day doesn't free it: a
 * session the DPC knew about before the day began holds anyway. Adding one on the day holds at once.
 *
 * The way out is the field pass, with no limit:
 *   - "I did 2 hours of public witnessing this morning": opens only once the phone has been away from home for two
 *     hours between 06:30 and 09:30 (see Away); or
 *   - "I went out, and work pulled me back", once the session has begun.
 * Either needs the reason in his own words, and every pass is kept for the Sunday numbers.
 */
object Field {
    private const val TAG = "Field"
    private const val PREFS = "field"
    private const val KEY_LOG = "pass_log"
    private const val KEY_PASS_DAY = "pass_day"

    data class Window(val start: Long, val end: Long)

    /** The state is read often (every screen-time tick); the calendar is read at most every 30 seconds. */
    @Volatile private var cached: Pair<Long, List<Window>>? = null

    /** Today's sessions: those in the calendar now, and those known before today even if they've since gone. */
    fun today(context: Context): List<Window> {
        cached?.let { (at, list) -> if (System.currentTimeMillis() - at < 30_000) return list }
        return readToday(context).also { cached = System.currentTimeMillis() to it }
    }

    private fun readToday(context: Context): List<Window> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dayStart = startOfDay(0)
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
        // The week ahead is read, so a session is known before its day; deleting it on the day then changes nothing.
        val ahead = read(context)
        val present = ahead.filterValues { it.start < startOfDay(1) && it.end > dayStart }
        // Remember each session the first time it's seen: "start,end,firstSeen".
        ahead.forEach { (key, w) ->
            if (!prefs.contains("w|$key")) edit.putString("w|$key", "${w.start},${w.end},$now")
        }
        // Forget sessions from before today.
        prefs.all.keys.filter { it.startsWith("w|") }.forEach { k ->
            val start = prefs.getString(k, null)?.split(",")?.firstOrNull()?.toLongOrNull() ?: 0L
            if (start < dayStart) edit.remove(k)
        }
        edit.apply()
        val remembered = prefs.all.filterKeys { it.startsWith("w|") }.values.mapNotNull { v ->
            val (s, e, seen) = (v as? String)?.split(",")?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 3 } ?: return@mapNotNull null
            Window(s, e).takeIf { seen < dayStart && s < startOfDay(1) && e > dayStart }
        }
        return (present.values + remembered).distinct().sortedBy { it.start }
    }

    /** The session holding right now (no pass taken today), or null. */
    fun holding(context: Context): Window? {
        if (passedToday(context)) return null
        val now = System.currentTimeMillis()
        return today(context).firstOrNull { now >= it.start && now < it.end }
    }

    /** When today's last session ends, or null if there's none ahead. For the countdown and the pass screen. */
    fun endsAt(context: Context): Long? {
        val now = System.currentTimeMillis()
        return today(context).filter { it.end > now }.maxOfOrNull { it.end }
    }

    fun passedToday(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_PASS_DAY, 0L) == startOfDay(0)

    /** Which passes can be taken now: (witnessing, pulled back). */
    fun passesOpen(context: Context): Pair<Boolean, Boolean> {
        if (passedToday(context)) return false to false
        val now = System.currentTimeMillis()
        val ahead = today(context).filter { it.end > now }
        if (ahead.isEmpty()) return false to false
        return (Away.minutesAwayThisMorning(context) >= Away.NEEDED_MINUTES) to ahead.any { now >= it.start }
    }

    /** Take the pass: the rest of today's sessions are free. */
    fun takePass(context: Context, kind: String, reason: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val log = JSONArray(prefs.getString(KEY_LOG, "[]"))
        log.put(JSONObject().put("at", System.currentTimeMillis()).put("kind", kind).put("reason", reason.trim()))
        prefs.edit().putLong(KEY_PASS_DAY, startOfDay(0)).putString(KEY_LOG, log.toString()).apply()
        Log.d(TAG, "pass: $kind, $reason")
    }

    /** This week's passes (weeks start on Sunday), oldest first: (time, kind, reason). */
    fun passesThisWeek(context: Context): List<Triple<Long, String, String>> {
        val weekStart = Calendar.getInstance().apply {
            timeInMillis = startOfDay(0)
            add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY + 7) % 7))
        }.timeInMillis
        val log = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOG, "[]"))
        return (0 until log.length()).map { log.getJSONObject(it) }
            .filter { it.getLong("at") >= weekStart }
            .map { Triple(it.getLong("at"), it.getString("kind"), it.getString("reason")) }
    }

    /** Sessions in the calendar from today to a week ahead, keyed by event and start. */
    private fun read(context: Context): Map<String, Window> {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyMap()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startOfDay(0))
            ContentUris.appendId(it, startOfDay(8))
        }.build()
        val found = mutableMapOf<String, Window>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.ALL_DAY),
                "${Instances.TITLE} LIKE ? AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Instances.STATUS_CANCELED})",
                arrayOf("Field%"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(3) == 1) continue          // an all-day "Field" isn't a session with hours
                    found["${c.getLong(0)}@${c.getLong(1)}"] = Window(c.getLong(1), c.getLong(2))
                }
            }
        }.onFailure { Log.e(TAG, "reading the calendar failed", it) }
        return found
    }

    /** Local midnight, `days` from today. */
    private fun startOfDay(days: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, days)
    }.timeInMillis

    /** Today's session edges still to come, for alarms (the DPC wakes at each one). */
    fun edgesAhead(context: Context): List<Long> {
        val now = System.currentTimeMillis()
        return today(context).flatMap { listOf(it.start, it.end) }.filter { it > now }.distinct()
    }
}
