package com.tolu.dpc

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.Calendar

/**
 * Colour and Tides: screen time with no daily cap, only a limit on binges. Every app counts as drift unless it's done on
 * purpose (the Bible, Àṣàrò, the ministry, calls, Slack and Meet for work; not Gmail) or part of the phone's plumbing (home screen, Settings, camera,
 * messages and the like). One drift session runs across all of them, so hopping doesn't reset it. Past 5 minutes the
 * screen turns grey. At 15, the tide goes out for the app in front alone: it locks for 15 minutes the first time that
 * day, then 15 more each time (15, 30, 45, 60…), and comes back by itself; the counts reset at 07:00. After a tide the
 * session drops to 10, so the next drift app goes after 5 more minutes.
 *
 * Only two things bring the session down: putting the phone down (screen off: a minute undoes four), or 10 minutes
 * straight in a purpose app, which clears it. Purpose apps, plumbing and calls pause it. Day and evening only; the
 * night lock has its own screen.
 *
 * Driven by BlockScreenService (which app is in front, screen on or off, a tick every 30 seconds). Greyscale is a
 * display setting that needs WRITE_SECURE_SETTINGS, granted once over adb.
 */
internal object ColourKeeper {

    private const val TAG = "dpc.Colour"

    /** Short names for the screens; any other app goes by its own label. */
    private val LABELS = linkedMapOf(
        "com.android.chrome" to "Chrome",
        "com.whatsapp.w4b" to "WhatsApp",
        "com.whatsapp" to "WhatsApp",
        "com.instagram.android" to "Instagram",
        "com.google.android.youtube" to "YouTube",
        "com.twitter.android" to "X",
        "com.google.android.googlequicksearchbox" to "Google",
    )

    /**
     * The phone's plumbing: neither drift nor purpose, so they pause the session. They never take a tide (locking
     * Settings or the keyboard would trap you). Anything without a launcher icon counts as plumbing too.
     */
    private val PLUMBING = setOf(
        "com.android.settings", "com.android.settings.intelligence", "com.android.stk",
        "com.transsion.calculator", "com.google.android.calendar", "com.transsion.camera",
        "com.google.android.apps.nbu.files", "com.google.android.apps.messaging",
        "com.transsion.soundrecorder", "com.transsion.notebook", "org.localsend.localsend_app",
    )

    private val driftCache = mutableMapOf<String, Boolean>()
    @Volatile private var home: String? = null

    /** Drift is any app with an icon that isn't done on purpose, isn't plumbing and isn't the home screen. */
    fun isDrift(context: Context, pkg: String): Boolean = driftCache.getOrPut(pkg) {
        val pm = context.packageManager
        if (home == null) home = runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
        pkg !in PURPOSE && pkg !in PLUMBING && pkg != home && pkg !in OVERLAYS && pm.getLaunchIntentForPackage(pkg) != null
    }

    /** The name the screens use for a drift app. */
    fun label(context: Context, pkg: String): String = LABELS[pkg] ?: runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("That app")

    /**
     * Soft tides: these aren't suspended, because a suspended app can't ring, and a WhatsApp call must always come
     * through. Instead BlockScreenService turns them away at the door (opening a chat shows the tide screen), while
     * calls and notifications still arrive.
     */
    val SOFT = setOf("com.whatsapp.w4b", "com.whatsapp")

    /** On purpose: these pause the session, and 10 minutes straight in them clears it. */
    private val PURPOSE = setOf(
        "org.jw.jwlibrary.mobile", "com.asaro.meditation", "com.lostpixels.fieldservice",
        "com.sh.smart.caller", "com.google.android.apps.maps", "com.google.android.apps.tachyon",
        "com.Slack", "com.transsion.deskclock", "com.transsnet.palmpay",
    )

    /** Shells that come and go on top of whatever is open; they don't change what "in front" means. */
    private val OVERLAYS = setOf("com.android.systemui", "com.tolu.dpc", "android")

    private const val GREY_AFTER = 5f        // minutes of drift before the screen goes grey
    private const val TIDE_AFTER = 15f       // minutes of drift before the tide takes the app in front
    private const val AFTER_TIDE = 10f       // where the session sits after a tide: still grey, 5 minutes from the next
    private const val TIDE_STEP = 15         // each tide that day is 15 minutes longer: 15, 30, 45, 60…
    private const val REST_SPEED = 4f        // with the screen off, a minute undoes four minutes of a session
    private const val PURPOSE_CLEARS = 10 * 60_000L   // 10 minutes straight in purpose apps clears the session

    private const val PREFS = "focus"
    private const val KEY_DAY = "tides_day"
    const val ACTION_TIDE_END = "com.tolu.dpc.ACTION_TIDE_END"

    /** Minutes of drift, net of rest, shared by all the drift apps. */
    @Volatile private var drift = 0f
    @Volatile private var front: String? = null
    @Volatile private var frontDrift = false
    /** When the current unbroken stretch in purpose apps began (elapsed ms), or 0 when not in one. */
    @Volatile private var purposeSince = 0L
    @Volatile private var screenOn = true
    @Volatile private var lastTick = SystemClock.elapsedRealtime()
    @Volatile private var lastGrey: Boolean? = null
    @Volatile private var calling = false
    /** An adb pretend holds for 20 seconds, so the widget redrawing (which reports the launcher in front) can't undo it. */
    @Volatile private var holdUntil = 0L
    private fun holding() = SystemClock.elapsedRealtime() < holdUntil

    /** On a call: a phone call, or a WhatsApp (or any VoIP) call, which puts audio into communication mode. */
    fun inCall(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java)?.mode
        calling = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_CALL_SCREENING
        return calling
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 1 is full colour, 0 is grey: grey the moment the session passes 5 minutes, while a drift app is in front. */
    fun colour(): Float = if (screenOn && !calling && frontDrift && drift > GREY_AFTER) 0f else 1f

    // ── Tides ────────────────────────────────────────────────────────────────

    /** The drift apps whose tide is out right now. */
    fun tidedApps(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        return prefs(context).all.filter { (k, v) -> k.startsWith("tide_until_") && v is Long && now < v }
            .keys.map { it.removePrefix("tide_until_") }.toSet()
    }

    fun tideOut(context: Context) = tidedApps(context).isNotEmpty()

    /** The most recent tide still out: its app, when it went out and when it comes back (epoch ms). */
    fun latestTide(context: Context): Triple<String, Long, Long>? {
        val p = prefs(context)
        return tidedApps(context)
            .map { Triple(it, p.getLong("tide_start_$it", 0L), p.getLong("tide_until_$it", 0L)) }
            .maxByOrNull { it.second }
    }

    fun tideWindow(context: Context): Pair<Long, Long> = latestTide(context)?.let { it.second to it.third } ?: (0L to 0L)

    /** "Chrome", "Chrome and WhatsApp", …: the apps out with the tide, by name. */
    fun tidedNames(context: Context): String {
        val names = tidedApps(context).map { label(context, it) }.distinct()
        return when (names.size) {
            0 -> "Your drift apps"
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }

    /** The tide's day starts at 07:00, so a late-night count never spills into the next morning. */
    private fun tideDay(): Int = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -7) }.let {
        it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR)
    }

    private fun startTide(context: Context, pkg: String) {
        val p = prefs(context)
        val today = tideDay()
        val edit = p.edit()
        if (p.getInt(KEY_DAY, 0) != today) {
            // A new day: every app's count starts again.
            p.all.keys.filter { it.startsWith("tides_") && it != KEY_DAY }.forEach { edit.remove(it) }
            edit.putInt(KEY_DAY, today)
        }
        val n = (if (p.getInt(KEY_DAY, 0) == today) p.getInt("tides_$pkg", 0) else 0) + 1
        val minutes = TIDE_STEP * n
        val now = System.currentTimeMillis()
        val until = now + minutes * 60_000L
        edit.putLong("tide_start_$pkg", now).putLong("tide_until_$pkg", until).putInt("tides_$pkg", n).apply()
        drift = AFTER_TIDE   // still grey; the next drift app has 5 minutes, not 15

        // The tide comes back on its own: an exact alarm re-applies the schedule then.
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, ("$ACTION_TIDE_END:$pkg").hashCode(),
            Intent(context, ScheduleReceiver::class.java).setAction(ACTION_TIDE_END), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until + 1_000L, pi) }

        val name = label(context, pkg)
        tell(context, if (n == 1) "Fifteen minutes straight on $name. Tide is out o. It's back in $minutes minutes."
            else "$name again? Tide is out, $minutes minutes this time. Me, I'm not going anywhere.")
        Log.d(TAG, "tide $n for $pkg: $minutes min")
        ScheduleReceiver.enforce(context)
        // A soft tide isn't a suspension, so if its app is open right now, send it away now rather than at the next screen.
        if (pkg in SOFT && front == pkg) BlockScreenService.turnAway()
    }

    // ── Inputs ───────────────────────────────────────────────────────────────

    fun onForeground(context: Context, pkg: String?) {
        if (holding()) return
        if (pkg == null || pkg in OVERLAYS || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        advance(context)
        // Moving between purpose apps keeps the stretch going; anything else ends it.
        if (pkg in PURPOSE) { if (front !in PURPOSE || purposeSince == 0L) purposeSince = SystemClock.elapsedRealtime() }
        else purposeSince = 0L
        front = pkg
        frontDrift = isDrift(context, pkg)
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

    /** For adb testing: pretend this many minutes of drift, with Chrome (or `pkg`) in front. It only ever adds: a test can't be used to wipe a real session. */
    fun pretend(context: Context, minutes: Float, pkg: String = "com.android.chrome") {
        front = pkg
        frontDrift = isDrift(context, pkg)
        screenOn = true
        drift = maxOf(drift, minutes)
        holdUntil = if (minutes > 0f) SystemClock.elapsedRealtime() + 20_000 else 0L
        lastTick = SystemClock.elapsedRealtime()
        apply(context)
    }

    /** For adb testing: send one app's tide out now, for real. */
    fun testTide(context: Context, pkg: String = "com.android.chrome") = startTide(context, pkg)

    /** Full colour and no grey, e.g. when the DPC is removed. */
    fun reset(context: Context) {
        drift = 0f
        write(context, grey = false)
    }

    // ── Workings ─────────────────────────────────────────────────────────────

    private fun advance(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (holding()) { lastTick = now; return }
        val minutes = (now - lastTick) / 60_000f
        lastTick = now
        // Ask whether the screen is on rather than trusting the broadcasts, which Hiber may hold while frozen.
        screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: screenOn
        // A gap of more than 2 minutes means the DPC was frozen or asleep: count it as rest, never as drift.
        if (minutes > 2f) {
            drift = (drift - minutes * REST_SPEED).coerceAtLeast(0f)
            return
        }
        if (!activeNow(context)) {
            drift = 0f
            return
        }
        // A call pauses the session: it neither counts up nor runs down, and no tide starts mid-call.
        if (inCall(context)) return
        // Screen off is the only rest.
        if (!screenOn) {
            purposeSince = 0L
            drift = (drift - minutes * REST_SPEED).coerceAtLeast(0f)
            return
        }
        val using = front.takeIf { frontDrift && it !in tidedApps(context) }
        when {
            using != null -> drift += minutes
            // 10 minutes straight in purpose apps clears it; until then it only waits.
            front in PURPOSE && purposeSince > 0L && now - purposeSince >= PURPOSE_CLEARS -> drift = 0f
            else -> Unit   // purpose (under 10 minutes), plumbing, the home screen: paused
        }
        if (using != null && drift >= TIDE_AFTER) startTide(context, using)
    }

    /** Only in the day and evening phases: at night and in the Bible hour the lock already has its say. */
    private fun activeNow(context: Context): Boolean {
        val state = ScheduleReceiver.currentState(context)
        return !state.gated && (state.phase == ScheduleReceiver.Phase.DAY || state.phase == ScheduleReceiver.Phase.EVENING)
    }

    private fun apply(context: Context) {
        inCall(context)
        val grey = colour() <= 0f
        write(context, grey)
        // The widget's sky greys with the phone; redraw it when that changes.
        if (grey != lastGrey) {
            lastGrey = grey
            runCatching { FocusWidget.refresh(context) }
        }
    }

    private fun write(context: Context, grey: Boolean) {
        val cr = context.contentResolver
        try {
            // Extra dim was the old gradual warning; it stays off now that grey comes at once.
            if (Settings.Secure.getInt(cr, "reduce_bright_colors_activated", 0) != 0) Settings.Secure.putInt(cr, "reduce_bright_colors_activated", 0)
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
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(2001, n) }
    }
}
