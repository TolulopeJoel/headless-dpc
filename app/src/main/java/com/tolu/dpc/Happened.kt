package com.tolu.dpc

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * The day as it happened, written into his Google calendar named "Happened" (he makes it once in Google Calendar). It
 * syncs whenever the phone is online; the laptop reads it for the weekly report, and Google Calendar shows it beside
 * the plan. Each row is an event ("Field", "Study · Ransom", "Away (unlabelled)"), plus one all-day "Day" event whose
 * description holds the numbers: minutes per fun app, JW Library minutes, the Bible entry, when the screen went dark
 * and light. No places or coordinates leave the phone. Rewriting a day replaces what was written before.
 */
object Happened {
    private const val TAG = "Happened"
    private const val PREFS = "happened"
    const val CALENDAR = "Happened"

    /** Write the day starting at `dayStart`. False if there's no Happened calendar to write to (yet). */
    fun write(context: Context, dayStart: Long): Boolean = runCatching {
        val cal = calendarId(context) ?: return false
        val resolver = context.contentResolver
        val p = prefs(context)
        // Replace what this day had.
        val old = JSONArray(p.getString("day_$dayStart", "[]"))
        (0 until old.length()).forEach { resolver.delete(android.content.ContentUris.withAppendedId(Events.CONTENT_URI, old.getLong(it)), null, null) }

        val ids = JSONArray()
        val zone = TimeZone.getDefault().id
        rows(context, dayStart).forEach { b ->
            val v = ContentValues().apply {
                put(Events.CALENDAR_ID, cal); put(Events.TITLE, titleOf(b)); put(Events.DTSTART, b.from); put(Events.DTEND, b.to)
                put(Events.EVENT_TIMEZONE, zone)
                put(Events.DESCRIPTION, descriptionOf(b))
            }
            resolver.insert(Events.CONTENT_URI, v)?.lastPathSegment?.toLongOrNull()?.let { ids.put(it) }
        }
        // The day's numbers, as one all-day event.
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            val local = Calendar.getInstance().apply { timeInMillis = dayStart }
            clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, cal); put(Events.TITLE, "Day"); put(Events.ALL_DAY, 1)
            put(Events.DTSTART, utc); put(Events.DTEND, utc + 86_400_000L); put(Events.EVENT_TIMEZONE, "UTC")
            put(Events.DESCRIPTION, summary(context, dayStart).toString())
        }
        resolver.insert(Events.CONTENT_URI, v)?.lastPathSegment?.toLongOrNull()?.let { ids.put(it) }
        p.edit().putString("day_$dayStart", ids.toString()).apply()
        Log.d(TAG, "wrote ${ids.length()} events for $dayStart")
        true
    }.getOrElse { Log.e(TAG, "writing the day failed", it); false }

    /** The blocks a day writes: what happened, not what was only planned or missed. */
    private fun rows(context: Context, dayStart: Long) = Day.blocks(context, dayStart).filter { it.status != Day.Status.MISSED && !it.fromPlan }

    private fun titleOf(b: Day.Block) = when (b.status) {
        Day.Status.UNKNOWN -> "Away (unlabelled)"
        Day.Status.GUESS -> "${b.kind.label}? (unconfirmed)"
        else -> b.title
    }

    private fun descriptionOf(b: Day.Block) = "kind=${b.kind.name}; status=${b.status.name}; late=${b.late}"

    /**
     * adb (ACTION_EXPORT_HAPPENED): `days` days from the one starting at `from`, exactly as they'd be written to the
     * calendar, into files/happened.json for the laptop's report (weekly_report.py --phone). For weeks the calendar
     * missed: 11–17 Oct had no Happened calendar on the phone, so nothing was written.
     */
    fun export(context: Context, from: Long, days: Int): java.io.File {
        val out = JSONArray()
        val day = Calendar.getInstance().apply { timeInMillis = from }
        repeat(days) {
            val dayStart = day.timeInMillis
            val events = JSONArray()
            rows(context, dayStart).forEach { b ->
                events.put(JSONObject().put("title", titleOf(b)).put("start", b.from).put("end", b.to).put("desc", descriptionOf(b)))
            }
            out.put(JSONObject().put("day", dayStart).put("events", events).put("summary", summary(context, dayStart)))
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        return java.io.File(context.getExternalFilesDir(null), "happened.json").apply { writeText(out.toString()) }
    }

    /** The numbers: fun-app minutes by app, JW Library minutes, the entry, the night (dark → light), what was on after 22:00, reviewed. */
    fun summary(context: Context, dayStart: Long): JSONObject {
        val dayEnd = dayStart + 86_400_000L
        val byApp = Usage.minutesByApp(context, dayStart, dayEnd)
        val fun_ = JSONObject()
        byApp.filterKeys { ColourKeeper.isDrift(context, it) }.entries.groupBy { ColourKeeper.label(context, it.key) }
            .mapValues { (_, e) -> e.sumOf { it.value } }.entries.sortedByDescending { it.value }.forEach { fun_.put(it.key, it.value) }
        val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)
        return JSONObject().apply {
            put("fun", fun_)
            put("jw", byApp["org.jw.jwlibrary.mobile"] ?: 0)
            put("entry", entryOn(context, dayStart))
            Usage.night(context, dayStart)?.let { (dark, light) -> put("dark", hhmm.format(dark)); put("light", hhmm.format(light)) }
            // The apps in front from 22:00 to 05:00 the next morning, by name, to find what keeps the phone on (28 Oct 2026).
            val late = JSONObject()
            Usage.minutesByApp(context, dayStart + 22 * 3_600_000L, dayStart + 29 * 3_600_000L).filterValues { it > 0 }.entries
                .groupBy { ColourKeeper.label(context, it.key) }.mapValues { (_, e) -> e.sumOf { it.value } }
                .entries.sortedByDescending { it.value }.forEach { late.put(it.key, it.value) }
            put("late", late)
            put("reviewed", Day.reviewed(context, dayStart))
            put("passes", Field.passesOn(context, dayStart))
            Reading.dayResult(context, dayStart)?.let { put("reading", it) }
        }
    }

    /** The Àṣàrò entry (ACTION_ENTRY_SAVED) was written on this day. */
    fun noteEntry(context: Context) = prefs(context).edit().putBoolean("entry_${startOfDay(0)}", true).apply()
    private fun entryOn(context: Context, dayStart: Long) = prefs(context).getBoolean("entry_$dayStart", false)

    /** His "Happened" calendar, if it's on the phone and writable. */
    private fun calendarId(context: Context): Long? {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        context.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.CALENDAR_ACCESS_LEVEL),
            "${Calendars.CALENDAR_DISPLAY_NAME} = ? COLLATE NOCASE", arrayOf(CALENDAR), null)?.use { c ->
            while (c.moveToNext()) if (c.getInt(1) >= Calendars.CAL_ACCESS_CONTRIBUTOR) return c.getLong(0)
        }
        return null
    }

    fun describe(context: Context) = "happened calendar: ${if (calendarId(context) != null) "found" else "missing"}"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
