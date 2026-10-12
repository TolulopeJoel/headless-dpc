package com.tolu.dpc

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Instances
import android.util.Log
import java.util.Calendar

/** One session from the calendar, in epoch milliseconds. */
data class SessionWindow(val start: Long, val end: Long)

/**
 * The calendar's sessions of one kind: every event in its calendar (`calendar`, by name), except those whose title has
 * one of the `except` words, or any event whose title starts with `title` ("Field", "Study"). Today's are those in the
 * calendar now, plus any the DPC knew about before today began, even if they've since been deleted or moved: a session
 * can't be cancelled on its own day. One added on the day holds at once. The week ahead is read, so a session is known
 * before its day.
 */
class CalendarSessions(
    private val title: String,
    private val prefsName: String,
    private val calendar: String? = null,
    private val except: List<String> = emptyList(),
    /** The typical week for this kind: (Calendar.DAY_OF_WEEK, start minute, end minute). Used on a day the calendar
     *  didn't plan before it began, unless an "Off…" event in the calendar, made before the day, frees it. */
    private val defaults: List<Triple<Int, Int, Int>> = emptyList(),
) {

    /** The state is read often (every screen-time tick); the calendar is read at most every 30 seconds. */
    @Volatile private var cached: Pair<Long, List<SessionWindow>>? = null

    fun today(context: Context): List<SessionWindow> {
        cached?.let { (at, list) -> if (System.currentTimeMillis() - at < 30_000) return list }
        return readToday(context).also { cached = System.currentTimeMillis() to it }
    }

    /** The session holding right now, or null. */
    fun now(context: Context): SessionWindow? {
        val now = System.currentTimeMillis()
        return today(context).firstOrNull { now >= it.start && now < it.end }
    }

    /** When today's last session ends, or null if there's none ahead. */
    fun endsAt(context: Context): Long? {
        val now = System.currentTimeMillis()
        return today(context).filter { it.end > now }.maxOfOrNull { it.end }
    }

    /** Today's session edges still to come, for alarms (the DPC wakes at each one). */
    fun edgesAhead(context: Context): List<Long> {
        val now = System.currentTimeMillis()
        return today(context).flatMap { listOf(it.start, it.end) }.filter { it > now }.distinct()
    }

    private fun readToday(context: Context): List<SessionWindow> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val dayStart = startOfDay(0)
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
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
            SessionWindow(s, e).takeIf { seen < dayStart && s < startOfDay(1) && e > dayStart }
        }
        val planned = (present.values + remembered).distinct().sortedBy { it.start }
        // Planned before today began (a remembered session), or freed by an "Off…" made before today: the calendar decides.
        if (remembered.isNotEmpty() || offBeforeToday(context, prefs, dayStart, now)) return planned
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val typical = defaults.filter { it.first == dow }.map { (_, a, b) -> SessionWindow(dayStart + a * 60_000L, dayStart + b * 60_000L) }
        return (planned + typical).distinct().sortedBy { it.start }
    }

    /** An "Off…" event in this kind's calendar today, first seen before today began. */
    private fun offBeforeToday(context: Context, prefs: android.content.SharedPreferences, dayStart: Long, now: Long): Boolean {
        if (calendar == null || context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return false
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, dayStart); ContentUris.appendId(it, startOfDay(8)) }.build()
        var off = false
        runCatching {
            context.contentResolver.query(uri, arrayOf(Instances.EVENT_ID, Instances.BEGIN),
                "${Instances.CALENDAR_DISPLAY_NAME} = ? COLLATE NOCASE AND ${Instances.TITLE} LIKE ?", arrayOf(calendar, "Off%"), null)?.use { c ->
                val edit = prefs.edit()
                while (c.moveToNext()) {
                    val key = "off|${c.getLong(0)}@${c.getLong(1)}"
                    val seen = prefs.getLong(key, now).also { if (!prefs.contains(key)) edit.putLong(key, now) }
                    if (c.getLong(1) < startOfDay(1) && seen < dayStart) off = true
                }
                edit.apply()
            }
        }
        return off
    }

    /** Sessions in the calendar from today to a week ahead, keyed by event and start. */
    private fun read(context: Context): Map<String, SessionWindow> {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyMap()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startOfDay(0))
            ContentUris.appendId(it, startOfDay(8))
        }.build()
        // By title, or by calendar (leaving out the titles with an `except` word).
        val args = mutableListOf("$title%")
        var which = "${Instances.TITLE} LIKE ?"
        if (calendar != null) {
            args += calendar
            val notIn = if (except.isEmpty()) "" else " AND NOT (" + except.joinToString(" OR ") { "${Instances.TITLE} LIKE ?" } + ")"
            args += except.map { "%$it%" }
            // "Off…" events free the day; they're never sessions themselves.
            which += " OR (${Instances.CALENDAR_DISPLAY_NAME} = ? COLLATE NOCASE AND ${Instances.TITLE} NOT LIKE 'Off%'$notIn)"
        }
        val found = mutableMapOf<String, SessionWindow>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.ALL_DAY),
                "($which) AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Instances.STATUS_CANCELED})",
                args.toTypedArray(),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(3) == 1) continue          // an all-day event isn't a session with hours
                    found["${c.getLong(0)}@${c.getLong(1)}"] = SessionWindow(c.getLong(1), c.getLong(2))
                }
            }
        }.onFailure { Log.e("CalendarSessions", "reading the calendar failed ($title)", it) }
        return found
    }
}

/** Local midnight, `days` from today. */
fun startOfDay(days: Int): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_YEAR, days)
}.timeInMillis

/**
 * Study sessions from the calendar ("Study ..."): personal study is protected time. The phone's fun apps rest (as in
 * Field) and Chrome keeps to jw.org, but the hotspot stays on, so wol.jw.org can be used on the laptop.
 */
/** Work and the banks, read only to show the mode (the laptop enforces them): the Work and Business calendars. */
object WorkTime {
    private val sessions = CalendarSessions("Work", "work", calendar = "Work")
    fun holding(context: Context) = sessions.now(context)
    fun today(context: Context) = sessions.today(context)
}

object BankTime {
    private val sessions = CalendarSessions("Bank", "bank", calendar = "Business")
    fun holding(context: Context) = sessions.now(context)
    fun today(context: Context) = sessions.today(context)
}

object Study {
    /** Every event in the Study calendar, or titled "Study ..." in any calendar. */
    private val sessions = CalendarSessions("Study", "study", calendar = "Study", defaults = listOf(
        Triple(Calendar.MONDAY, 6 * 60, 7 * 60),
        *listOf(Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY)
            .map { Triple(it, 6 * 60, 7 * 60 + 30) }.toTypedArray(),
        Triple(Calendar.TUESDAY, 19 * 60, 20 * 60 + 30),
        Triple(Calendar.THURSDAY, 17 * 60, 17 * 60 + 45),
    ))
    fun holding(context: Context) = sessions.now(context)
    fun today(context: Context) = sessions.today(context)
    fun endsAt(context: Context) = sessions.endsAt(context)
    fun edgesAhead(context: Context) = sessions.edgesAhead(context)
}
