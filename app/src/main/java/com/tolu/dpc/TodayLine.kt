package com.tolu.dpc

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract.Instances

/**
 * Today's few musts, from the all-day "Today: …" event in his Google calendar named "Today" (set up on 28 Oct 2026 for
 * mornings when his head is full), and the way into tomorrow's plan in Google Calendar.
 */
object TodayLine {
    private const val CALENDAR = "Today"
    private const val GOOGLE_CALENDAR = "com.google.android.calendar"

    /** "Personal study · Applications", or null if there's no line today (or the calendar isn't on the phone yet). */
    fun text(context: Context): String? {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startOfDay(0))
            ContentUris.appendId(it, startOfDay(1) - 1)
        }.build()
        return runCatching {
            context.contentResolver.query(uri, arrayOf(Instances.TITLE),
                "${Instances.CALENDAR_DISPLAY_NAME} = ? COLLATE NOCASE AND ${Instances.ALL_DAY} = 1", arrayOf(CALENDAR), null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.removePrefix("Today:")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Tomorrow morning, open in Google Calendar. */
    fun planTomorrow(context: Context) = runCatching {
        val tomorrow = Uri.parse("content://com.android.calendar/time/${startOfDay(1) + 5 * 3_600_000L}")
        context.startActivity(Intent(Intent.ACTION_VIEW, tomorrow).setPackage(GOOGLE_CALENDAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { android.util.Log.e("TodayLine", "couldn't open the calendar", it) }
}
