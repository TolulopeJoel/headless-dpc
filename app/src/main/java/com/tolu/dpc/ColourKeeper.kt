package com.tolu.dpc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Colour: screen time without walls. Nothing is blocked and there's no timer. Drifting (a long unbroken stretch in
 * Chrome, WhatsApp and the like) runs free for a while, then the screen slowly dims, and after about half an hour it
 * turns grey. Opening something done on purpose (the Bible, Àṣàrò, the ministry, calls, work) brings the colour back
 * at once, and putting the phone down refills it. Only in the day and the evening; the night lock has its own screen.
 *
 * Driven by BlockScreenService (which app is in front, screen on or off, a tick every 30 seconds). Writing the two
 * display settings needs WRITE_SECURE_SETTINGS, granted once over adb.
 */
internal object ColourKeeper {

    private const val TAG = "dpc.Colour"

    /** Drift apps, and how fast they drain: messaging at half the rate of scrolling. */
    private val DRIFT = mapOf(
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

    private const val FREE_MINUTES = 10f     // drifting this long costs nothing
    private const val FADE_MINUTES = 20f     // then the colour drains over this long, to grey at 30
    private const val REST_SPEED = 4f        // a minute away undoes four minutes of drift
    private const val MAX_DIM = 55           // Extra dim at its strongest, still easy to read

    @Volatile private var drift = 0f         // minutes of drift, net of rest
    @Volatile private var front: String? = null
    @Volatile private var screenOn = true
    @Volatile private var lastTick = SystemClock.elapsedRealtime()
    @Volatile private var greyToldAt = -1f
    @Volatile private var lastBucket = -1

    /** 1 is full colour, 0 is grey. */
    fun colour(): Float = 1f - ((drift - FREE_MINUTES) / FADE_MINUTES).coerceIn(0f, 1f)

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
        val rate = if (screenOn) DRIFT[front] else null
        drift = if (rate != null) drift + minutes * rate else (drift - minutes * REST_SPEED).coerceAtLeast(0f)
        if (!activeNow(context)) drift = 0f
    }

    /** Only in the day and evening phases: at night and in the Bible hour the lock already has its say. */
    private fun activeNow(context: Context): Boolean {
        val state = ScheduleReceiver.currentState(context)
        return !state.gated && (state.phase == ScheduleReceiver.Phase.DAY || state.phase == ScheduleReceiver.Phase.EVENING)
    }

    private fun apply(context: Context) {
        val c = colour()
        write(context, dim = ((1f - c) * MAX_DIM).toInt(), grey = c <= 0f)

        // Àṣàrò says one thing, once, when it has gone grey. The fading itself is the only other message.
        if (c <= 0f && greyToldAt < 0f) {
            greyToldAt = drift
            tell(context, "It's all grey now o. Put it down small, the colour will come back.")
        }
        if (c >= 1f) greyToldAt = -1f

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
