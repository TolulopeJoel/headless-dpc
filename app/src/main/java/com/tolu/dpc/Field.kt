package com.tolu.dpc

import android.content.Context
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
 *     hours between 06:30 and 10:00 (see Away); or
 *   - "I went out, and work pulled me back", once the session has begun and the phone has actually been away from
 *     home during it (at least PULLED_BACK_AWAY minutes), so it can't be taken without leaving the house.
 * Either needs the reason in his own words, and every pass is kept for the Sunday numbers.
 */
object Field {
    private const val TAG = "Field"
    private const val PREFS = "field"
    private const val KEY_LOG = "pass_log"
    private const val KEY_PASS_DAY = "pass_day"
    /** "Pulled back" needs this much time away from home since today's field time began. */
    const val PULLED_BACK_AWAY = 5

    /**
     * Today's sessions: everything in the Ministry calendar, whatever its title (metro, magazine, return visits),
     * except meetings and preparation; or any event titled "Field ...". See CalendarSessions for why one can't be
     * cancelled on its own day.
     */
    private val sessions = CalendarSessions("Field", PREFS, calendar = "Ministry", except = listOf("meeting", "prep"), defaults = listOf(
        *listOf(Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.FRIDAY, Calendar.SATURDAY).map { Triple(it, 9 * 60, 14 * 60) }.toTypedArray(),
        Triple(Calendar.SUNDAY, 11 * 60 + 30, 14 * 60),
    ))

    fun today(context: Context): List<SessionWindow> = sessions.today(context)

    fun edgesAhead(context: Context): List<Long> = sessions.edgesAhead(context)

    /** The session holding right now (no pass taken today), or null. */
    fun holding(context: Context): SessionWindow? {
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
        val began = today(context).filter { it.start <= now }.minOfOrNull { it.start }
        // Counted from half an hour before it began: leaving at 08:40 to meet the group counts as going out.
        val wentOut = began != null && Away.minutesAway(context, began - 30 * 60_000L, now) >= PULLED_BACK_AWAY
        return (Away.minutesAwayThisMorning(context) >= Away.NEEDED_MINUTES) to wentOut
    }

    /** Take the pass: the rest of today's sessions are free. */
    fun takePass(context: Context, kind: String, reason: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val log = JSONArray(prefs.getString(KEY_LOG, "[]"))
        log.put(JSONObject().put("at", System.currentTimeMillis()).put("kind", kind).put("reason", reason.trim()))
        prefs.edit().putLong(KEY_PASS_DAY, startOfDay(0)).putString(KEY_LOG, log.toString()).apply()
        Log.d(TAG, "pass: $kind, $reason")
    }

    /** Passes taken on the day starting at `dayStart`. */
    fun passesOn(context: Context, dayStart: Long): Int {
        val log = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOG, "[]"))
        return (0 until log.length()).count { log.getJSONObject(it).getLong("at") in dayStart until dayStart + 86_400_000L }
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
}
