package com.tolu.dpc

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Instances
import android.util.Log
import java.util.Calendar
import java.util.TimeZone

/**
 * Meeting assignments, read from the phone's calendar: an event with "Assignment" in its title, on a meeting day
 * (Thursday for the midweek meeting, Sunday for the weekend one; any other day doesn't count).
 * The night before it, the hotspot and USB debugging stay until 22:00 instead of 21:00, so the laptop can be used to
 * prepare. It counts only if the DPC had seen the event by the end of the day before that night (for a Sunday
 * assignment, by Friday): assignments come weeks ahead, so this is easy to meet and can't be added on the night itself.
 * An event moved to another day is a new one, seen afresh.
 */
object Assignments {
    private const val TAG = "Assignments"
    private const val PREFS = "assignments"
    private const val DAY_MS = 86_400_000L
    /** android.text.format.Time.EPOCH_JULIAN_DAY: the Julian day of 1 Jan 1970. */
    private const val EPOCH_JULIAN_DAY = 2_440_588
    /** How far ahead the calendar is read, so an assignment is seen the day it's added. */
    private const val AHEAD_DAYS = 180
    /** The meeting days: Thursday (midweek) and Sunday (weekend). So the nights it can open are Wednesday and Saturday. */
    private val MEETING_DAYS = setOf(Calendar.THURSDAY, Calendar.SUNDAY)

    private data class Seen(val key: String, val title: String, val day: Int, val firstSeen: Long)

    /** True on the day before an assignment that was in the calendar by the end of the day before that. */
    fun tomorrow(context: Context): Boolean {
        val today = julianDay(System.currentTimeMillis())
        return seen(context).any { it.day == today + 1 && meetingDay(it.day) && it.firstSeen < startOfJulianDay(today) }
    }

    /** For adb (ACTION_ASSIGNMENTS): every upcoming assignment, when it was first seen, and whether its night counts. */
    fun describe(context: Context): String {
        val all = seen(context)
        if (all.isEmpty()) return "no assignments in the calendar (calendar readable: ${canRead(context)})"
        return all.sortedBy { it.day }.joinToString("\n") {
            val night = it.day - 1
            "${dateOf(it.day)} \"${it.title}\": first seen ${java.util.Date(it.firstSeen)}; the night before (${dateOf(night)}) " + when {
                !meetingDay(it.day) -> "doesn't count (not a Thursday or Sunday)"
                it.firstSeen < startOfJulianDay(night) -> "counts"
                else -> "doesn't count (seen too late)"
            }
        }
    }

    /** The assignments in the calendar now, each with the time the DPC first saw it (recorded on first sight). */
    private fun seen(context: Context): List<Seen> {
        if (!canRead(context)) return emptyList()
        val now = System.currentTimeMillis()
        val today = julianDay(now)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startOfJulianDay(today))
            ContentUris.appendId(it, now + AHEAD_DAYS * DAY_MS)
        }.build()
        val found = mutableListOf<Seen>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.START_DAY),
                "${Instances.TITLE} LIKE ? AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Instances.STATUS_CANCELED})",
                arrayOf("%assignment%"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val day = c.getInt(2)
                    val key = "${c.getLong(0)}@$day"
                    found += Seen(key, c.getString(1).orEmpty(), day, prefs.getLong(key, now))
                }
            }
        }.onFailure { Log.e(TAG, "reading the calendar failed", it) }

        // Remember first sight; forget assignments whose day has passed.
        val edit = prefs.edit()
        found.filter { !prefs.contains(it.key) }.forEach { edit.putLong(it.key, it.firstSeen) }
        prefs.all.keys.filter { (it.substringAfter('@').toIntOrNull() ?: 0) < today }.forEach { edit.remove(it) }
        edit.apply()
        return found
    }

    /** The DPC, as device owner, grants itself calendar reading; nothing to tap. */
    fun grantCalendar(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        if (canRead(context)) return
        runCatching {
            dpm.setPermissionGrantState(admin, context.packageName, Manifest.permission.READ_CALENDAR,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }.onFailure { Log.e(TAG, "granting calendar access failed", it) }
    }

    private fun meetingDay(day: Int) =
        Calendar.getInstance().apply { timeInMillis = startOfJulianDay(day) }.get(Calendar.DAY_OF_WEEK) in MEETING_DAYS

    private fun canRead(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun julianDay(millis: Long): Int =
        (Math.floorDiv(millis + TimeZone.getDefault().getOffset(millis), DAY_MS) + EPOCH_JULIAN_DAY).toInt()

    /** Local midnight at the start of a Julian day. */
    private fun startOfJulianDay(day: Int): Long {
        // The day's date, read in UTC (where Julian days start at midnight), then that date's local midnight.
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = (day - EPOCH_JULIAN_DAY) * DAY_MS }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }

    private fun dateOf(day: Int): String =
        java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.UK).format(java.util.Date(startOfJulianDay(day)))
}
