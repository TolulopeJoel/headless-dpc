package com.tolu.dpc

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Instances
import android.util.Log
import java.util.Calendar

/**
 * Job interviews, read from the phone's calendar: an event with "Interview" in its title. From 15 minutes before it to
 * 15 minutes after, Google Meet stays open and the hotspot stays allowed (the laptop's internet), even at night or with
 * the morning gate shut: interviews abroad often land in the Lagos evening (asked for on 28 Oct 2026). Like an
 * assignment, it counts only if the DPC had seen it before its day began, so one can't be added on the night itself.
 */
object Interviews {
    private const val TAG = "Interviews"
    private const val PREFS = "interviews"
    private const val MARGIN_MS = 15 * 60_000L
    /** How far ahead the calendar is read, so an interview is seen the day it's booked. */
    private const val AHEAD_MS = 30 * 86_400_000L

    /** True while an interview that counts is on, margins included. */
    fun now(context: Context): Boolean = System.currentTimeMillis().let { t -> today(context).any { t >= it.start && t < it.end } }

    /** Today's starts and ends still to come, so the DPC wakes at each edge. */
    fun edgesAhead(context: Context): List<Long> =
        today(context).flatMap { listOf(it.start, it.end) }.filter { it > System.currentTimeMillis() }

    /** For adb (ACTION_INTERVIEWS): today's interviews that count, as windows. */
    fun describe(context: Context): String = today(context).joinToString("\n") { "${java.util.Date(it.start)} → ${java.util.Date(it.end)}" }
        .ifEmpty { "no interview today that counts" }

    /** Today's interviews that were in the calendar before today began, margins included. Records first sight of every one ahead. */
    private fun today(context: Context): List<SessionWindow> {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startOfDay(0))
            ContentUris.appendId(it, now + AHEAD_MS)
        }.build()
        val found = mutableListOf<Triple<String, Long, Long>>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END),
                "${Instances.TITLE} LIKE ? AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Instances.STATUS_CANCELED})",
                arrayOf("%interview%"),
                null,
            )?.use { c -> while (c.moveToNext()) found += Triple("${c.getLong(0)}@${c.getLong(1)}", c.getLong(1), c.getLong(2)) }
        }.onFailure { Log.e(TAG, "reading the calendar failed", it) }

        // Remember first sight; forget interviews that have passed.
        val edit = prefs.edit()
        found.filter { !prefs.contains(it.first) }.forEach { edit.putLong(it.first, now) }
        prefs.all.keys.filter { (it.substringAfter('@').toLongOrNull() ?: 0L) < startOfDay(-1) }.forEach { edit.remove(it) }
        edit.apply()

        return found.filter { (key, begin, _) -> begin < startOfDay(1) && prefs.getLong(key, now) < dayStartOf(begin) }
            .map { (_, begin, end) -> SessionWindow(begin - MARGIN_MS, end + MARGIN_MS) }
    }

    private fun dayStartOf(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
