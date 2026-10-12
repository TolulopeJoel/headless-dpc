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
        Day.blocks(context, dayStart).filter { it.status != Day.Status.MISSED && !it.fromPlan }.forEach { b ->
            val title = when (b.status) {
                Day.Status.UNKNOWN -> "Away (unlabelled)"
                Day.Status.GUESS -> "${b.kind.label}? (unconfirmed)"
                else -> b.title
            }
            val v = ContentValues().apply {
                put(Events.CALENDAR_ID, cal); put(Events.TITLE, title); put(Events.DTSTART, b.from); put(Events.DTEND, b.to)
                put(Events.EVENT_TIMEZONE, zone)
                put(Events.DESCRIPTION, "kind=${b.kind.name}; status=${b.status.name}; late=${b.late}")
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

    /** The numbers: fun-app minutes by app, JW Library minutes, the entry, the night (dark → light), reviewed. */
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
            put("reviewed", Day.reviewed(context, dayStart))
            put("passes", Field.passesOn(context, dayStart))
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
