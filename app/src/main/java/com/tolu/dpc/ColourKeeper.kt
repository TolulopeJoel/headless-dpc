package com.tolu.dpc

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Colour and Tides: screen time with no daily cap, only a limit on binges. Drifting (an unbroken stretch in Chrome,
 * WhatsApp or Instagram) runs free for 5 minutes, then the screen dims and greys until, at 15 minutes, the tide goes
 * out: those apps lock for 15 minutes, then 30, then 45 as the day goes on (reset at 07:00), and come back by
 * themselves. Stopping on your own avoids the tide: putting the phone down refills the colour, and opening something
 * done on purpose (the Bible, Àṣàrò, the ministry, calls, work) gives it back at once. Day and evening only; the night
 * lock has its own screen.
 *
 * Driven by BlockScreenService (which app is in front, screen on or off, a tick every 30 seconds). Writing the two
 * display settings needs WRITE_SECURE_SETTINGS, granted once over adb.
 */
internal object ColourKeeper {

    private const val TAG = "dpc.Colour"

    /** Drift apps, and how fast they drain: messaging at half the rate of scrolling. They lock when the tide goes out. */
    val DRIFT = mapOf(
        "com.android.chrome" to 1.0f,
        "com.instagram.android" to 1.0f,
        "com.google.android.youtube" to 1.0f,
        "com.twitter.android" to 1.0f,
        "com.whatsapp.w4b" to 0.5f,
        "com.whatsapp" to 0.5f,
    )

    /** On purpose: opening one of these gives the colour straight back. */
    private val PURPOSE = setOf(
        "org.jw.jwlibrary.mobile", "com.asaro.meditation", "com.lostpixels.fieldservice",
        "com.sh.smart.caller", "com.google.android.apps.maps", "com.google.android.apps.tachyon",
        "com.Slack", "com.google.android.gm", "com.transsion.deskclock", "com.transsnet.palmpay",
    )

    /** Shells that come and go on top of whatever is open; they don't change what "in front" means. */
    private val OVERLAYS = setOf("com.android.systemui", "com.tolu.dpc", "android")

    private const val FREE_MINUTES = 5f      // drifting this long costs nothing
    private const val FADE_MINUTES = 10f     // then the colour drains over this long: grey, and the tide, at 15
    /** Each tide of the day is longer: 15, 30, then 45 minutes. */
    private val TIDE_MINUTES = listOf(15, 30, 45)
    private const val PREFS = "focus"
    private const val KEY_TIDE_START = "tide_start"
    private const val KEY_TIDE_UNTIL = "tide_until"
    private const val KEY_TIDE_DAY = "tide_day"
    private const val KEY_TIDES = "tides_today"
    const val ACTION_TIDE_END = "com.tolu.dpc.ACTION_TIDE_END"
    private const val REST_SPEED = 4f        // a minute away undoes four minutes of drift
    private const val MAX_DIM = 55           // Extra dim at its strongest, still easy to read

    @Volatile private var drift = 0f         // minutes of drift, net of rest
    @Volatile private var front: String? = null
    @Volatile private var screenOn = true
    @Volatile private var lastTick = SystemClock.elapsedRealtime()
    @Volatile private var lastBucket = -1

    /** 1 is full colour, 0 is grey. */
    fun colour(): Float = 1f - ((drift - FREE_MINUTES) / FADE_MINUTES).coerceIn(0f, 1f)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True while the tide is out: the drift apps are locked until it comes back. */
    fun tideOut(context: Context) = System.currentTimeMillis() < prefs(context).getLong(KEY_TIDE_UNTIL, 0L)

    /** When this tide went out and when it comes back, as epoch ms (0 when there's none). */
    fun tideWindow(context: Context) = prefs(context).getLong(KEY_TIDE_START, 0L) to prefs(context).getLong(KEY_TIDE_UNTIL, 0L)

    /** The tide's day starts at 07:00, so a late-night count never spills into the next morning. */
    private fun tideDay(): Int = java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, -7) }.let {
        it.get(java.util.Calendar.YEAR) * 1000 + it.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun startTide(context: Context) {
        val p = prefs(context)
        val today = tideDay()
        val n = if (p.getInt(KEY_TIDE_DAY, 0) == today) p.getInt(KEY_TIDES, 0) + 1 else 1
        val minutes = TIDE_MINUTES[(n - 1).coerceAtMost(TIDE_MINUTES.size - 1)]
        val now = System.currentTimeMillis()
        val until = now + minutes * 60_000L
        p.edit().putLong(KEY_TIDE_START, now).putLong(KEY_TIDE_UNTIL, until).putInt(KEY_TIDE_DAY, today).putInt(KEY_TIDES, n).apply()
        drift = 0f   // the next session starts fresh once the tide is back
        // The tide comes back on its own: an exact alarm re-applies the schedule then.
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, ACTION_TIDE_END.hashCode(),
            Intent(context, ScheduleReceiver::class.java).setAction(ACTION_TIDE_END), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until + 1_000L, pi) }
        tell(context, when (n) {
            1 -> "Fifteen minutes straight. Tide is out o. Chrome and co. are back in $minutes minutes."
            else -> "Tide is out again. Back in $minutes minutes. Me, I'm not going anywhere."
        })
        Log.d(TAG, "tide $n out for $minutes min")
        ScheduleReceiver.enforce(context)
    }

    /** For adb testing: send the tide out now. */
    fun testTide(context: Context) = startTide(context)

    fun onForeground(context: Context, pkg: String?) {
        if (pkg == null || pkg in OVERLAYS || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        advance(context)
        front = pkg
        if (pkg in PURPOSE) drift = 0f
        apply(context)
    }

    fun onScreen(context: Context, on: Boolean) {
        advance(context)
        screenOn = on
        apply(context)
    }

    fun tick(context: Context) {
        advance(context)
        apply(context)
    }

    /** For adb testing: pretend this many minutes of drift. */
    fun pretend(context: Context, minutes: Float) {
        drift = minutes.coerceAtLeast(0f)
        lastTick = SystemClock.elapsedRealtime()
        apply(context)
    }

    /** Full colour and both display settings off, e.g. when the DPC is removed. */
    fun reset(context: Context) {
        drift = 0f
        write(context, dim = 0, grey = false)
    }

    private fun advance(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val minutes = (now - lastTick) / 60_000f
        lastTick = now
        val rate = if (screenOn && !tideOut(context)) DRIFT[front] else null
        drift = if (rate != null) drift + minutes * rate else (drift - minutes * REST_SPEED).coerceAtLeast(0f)
        if (!activeNow(context)) drift = 0f
        if (drift >= FREE_MINUTES + FADE_MINUTES && !tideOut(context)) startTide(context)
    }

    /** Only in the day and evening phases: at night and in the Bible hour the lock already has its say. */
    private fun activeNow(context: Context): Boolean {
        val state = ScheduleReceiver.currentState(context)
        return !state.gated && (state.phase == ScheduleReceiver.Phase.DAY || state.phase == ScheduleReceiver.Phase.EVENING)
    }

    private fun apply(context: Context) {
        val c = colour()
        write(context, dim = ((1f - c) * MAX_DIM).toInt(), grey = c <= 0f)

        // The widget's sky shows the colour, redrawn when it moves by a tenth.
        val bucket = (c * 10).toInt()
        if (bucket != lastBucket) {
            lastBucket = bucket
            runCatching { FocusWidget.refresh(context) }
        }
    }

    private fun write(context: Context, dim: Int, grey: Boolean) {
        val cr = context.contentResolver
        try {
            if (dim > 0) {
                if (Settings.Secure.getInt(cr, "reduce_bright_colors_level", -1) != dim) Settings.Secure.putInt(cr, "reduce_bright_colors_level", dim)
                if (Settings.Secure.getInt(cr, "reduce_bright_colors_activated", 0) != 1) Settings.Secure.putInt(cr, "reduce_bright_colors_activated", 1)
            } else if (Settings.Secure.getInt(cr, "reduce_bright_colors_activated", 0) != 0) {
                Settings.Secure.putInt(cr, "reduce_bright_colors_activated", 0)
            }
            val g = if (grey) 1 else 0
            if (grey && Settings.Secure.getInt(cr, "accessibility_display_daltonizer", -1) != 0) Settings.Secure.putInt(cr, "accessibility_display_daltonizer", 0)
            if (Settings.Secure.getInt(cr, "accessibility_display_daltonizer_enabled", 0) != g) Settings.Secure.putInt(cr, "accessibility_display_daltonizer_enabled", g)
        } catch (e: SecurityException) {
            Log.e(TAG, "needs WRITE_SECURE_SETTINGS: adb shell pm grant com.tolu.dpc android.permission.WRITE_SECURE_SETTINGS", e)
        }
    }

    private fun tell(context: Context, line: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("asaro", "Àṣàrò", NotificationManager.IMPORTANCE_DEFAULT))
        val n = Notification.Builder(context, "asaro")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Àṣàrò")
            .setContentText(line)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(2001, n) }
    }
}
