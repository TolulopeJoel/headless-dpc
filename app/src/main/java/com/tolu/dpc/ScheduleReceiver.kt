package com.tolu.dpc

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import java.util.Calendar

/**
 * The daily focus schedule:
 *   05:00  MORNING  Chrome (jw.org and wol.jw.org only), JW Library and Àṣàrò open
 *   07:00  DAY      everything open, once today's Àṣàrò entry exists (until then it stays MORNING; 12:00 releases it regardless)
 *   17:30  EVENING  Slack locks
 *   20:00  NIGHT    everything locks except calls, PalmPay, the clock, JW Library and Àṣàrò
 *   21:00           hotspot and USB debugging off (the laptop's internet); on Saturdays at 20:00, with the apps
 *   22:00           JW Library and Àṣàrò lock too
 * Every trigger works out the phase from the clock, so a missed or extra alarm can't leave the phone in the wrong state.
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: ${intent.action}")

        when (intent.action) {
            ACTION_ENTRY_SAVED -> recordEntry(context)
            ACTION_TEST_GATE -> startGateTest()
            ACTION_TEST_NIGHT -> startTest(Phase.NIGHT)
            ACTION_TEST_MORNING -> startTest(Phase.MORNING)
            ACTION_TEST_END -> { testUntil = 0L; gateTestUntil = 0L }
        }
        // Tests can also pretend a time of day (--ei minute 1320 is 22:00), for the blocked screen's sky and countdown only.
        when (intent.action) {
            ACTION_TEST_GATE, ACTION_TEST_NIGHT, ACTION_TEST_MORNING -> testMinute = intent.getIntExtra("minute", -1)
            ACTION_TEST_END -> { testMinute = -1; previewUntil = 0L }   // enforce() below redraws the widget
            ACTION_TEST_TIDE -> ColourKeeper.testTide(context, intent.getStringExtra("pkg") ?: "com.android.chrome")
            ACTION_TEST_COLOUR -> {
                ColourKeeper.pretend(context, intent.getFloatExtra("drift", 0f), intent.getStringExtra("pkg") ?: "com.android.chrome")
                return   // look only: pretends minutes of drift, nothing is locked
            }
            ACTION_PREVIEW -> {
                previewName = intent.getStringExtra("mood")?.uppercase()
                previewMinute = intent.getIntExtra("minute", -1)
                previewUntil = SystemClock.elapsedRealtime() + TEST_MS
                runCatching { FocusWidget.refresh(context) }
                return   // look only: nothing is locked or unlocked
            }
        }

        // Every path applies the current phase and re-arms all alarms.
        // HiOS's Hiber freezer drops the alarms of a frozen app, so nothing may rely on one alarm surviving.
        enforce(context)
        KeepAliveService.start(context)
    }

    enum class Phase { MORNING, DAY, EVENING, NIGHT }

    companion object {

        private const val TAG = "dpc.ScheduleReceiver"

        // Alarms from earlier versions (v1.1's window, v1.2's phase names), cancelled on every run so they can't linger.
        private val LEGACY_ACTIONS = listOf("RESTRICT", "UNRESTRICT", "MORNING", "DAY", "EVENING", "NIGHT")
            .map { "com.tolu.dpc.ACTION_$it" }

        /**
         * The one grace there was (30 Sep 2026, until midnight): USB debugging and the hotspot stayed on while apps locked.
         * The command that set it is gone, so no new grace can be given from a laptop; this only reads the old one out.
         */
        private const val KEY_GRACE_UNTIL = "grace_until_millis"

        /** Sent by Àṣàrò each time a new entry is saved. */
        const val ACTION_ENTRY_SAVED = "com.tolu.dpc.ACTION_ENTRY_SAVED"

        // Test hooks for adb: show the night or morning phase for 90 seconds, in the daytime only.
        // TEST_GATE acts as if it were 07:00 with no entry yet, until an entry arrives or 90 seconds pass.
        const val ACTION_TEST_NIGHT = "com.tolu.dpc.ACTION_TEST_NIGHT"
        const val ACTION_TEST_MORNING = "com.tolu.dpc.ACTION_TEST_MORNING"
        const val ACTION_TEST_END = "com.tolu.dpc.ACTION_TEST_END"
        const val ACTION_TEST_GATE = "com.tolu.dpc.ACTION_TEST_GATE"
        private const val TEST_MS = 90_000L

        /** Phase boundaries as minutes after midnight, and the phase each one starts. */
        private val BOUNDARIES = listOf(
            5 * 60 to Phase.MORNING,
            7 * 60 to Phase.DAY,
            17 * 60 + 30 to Phase.EVENING,
            20 * 60 to Phase.NIGHT,
        )

        /** The morning gate: an entry counts for today only if it was saved after 05:00; 12:00 releases the gate regardless. */
        private const val ENTRY_COUNTS_FROM = 5 * 60
        private const val GATE_RELEASE = 12 * 60
        private const val PREFS = "focus"
        private const val KEY_LAST_ENTRY = "last_entry_millis"

        /**
         * Inside the night: the Bible and Àṣàrò stay open until 22:00; the hotspot and debugging go at 21:00, except on
         * Saturdays, when they go at 20:00 with everything else. Saturday is the night before the weekend meeting and
         * the fifth field day, so the laptop closes earliest then (the life audit's advice).
         */
        private const val BIBLE_UNTIL = 22 * 60
        private const val DEVICE_RULES_FROM = 21 * 60
        private val BIBLE_APPS = setOf("org.jw.jwlibrary.mobile", "com.asaro.meditation")

        /** Alarm times: the phase boundaries, the two steps inside the night, and the gate's release. */
        private val ALARM_MINUTES = BOUNDARIES.map { it.first } + DEVICE_RULES_FROM + BIBLE_UNTIL + GATE_RELEASE

        private fun minuteNow(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

        /** When the laptop's internet goes tonight: 20:00 on a Saturday, 21:00 otherwise. */
        private fun deviceRulesFrom(): Int =
            if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) 20 * 60 else DEVICE_RULES_FROM

        /** 20:00–22:00: the night has started, but the Bible and Àṣàrò are still open. */
        fun bibleEvening(): Boolean = minuteNow().let { it >= 20 * 60 && it < BIBLE_UNTIL }

        private const val CHROME = "com.android.chrome"
        private val SLACK = setOf("com.Slack")

        /** Open all night: calls, PalmPay, the alarm clock, and the DPC itself. */
        private val NIGHT_ALLOWED = setOf(
            "com.sh.smart.caller",          // phone calls (Android won't suspend the dialer anyway)
            "com.transsnet.palmpay",
            "com.transsion.deskclock",      // so the morning alarm always rings
            "com.tolu.dpc",
        )

        /** Added from 05:00: the Bible hour. */
        private val MORNING_ALLOWED = NIGHT_ALLOWED + setOf(
            CHROME,                         // jw.org and wol.jw.org only, set by chromePolicy
            "org.jw.jwlibrary.mobile",
            "com.asaro.meditation",
        )

        /** jw.org covers its subdomains, wol.jw.org included; jw-cdn.org serves their images and media. */
        private val JW_SITES = arrayOf("jw.org", "wol.jw.org", "jw-cdn.org")

        /** Night-only device rules: no hotspot (the laptop's internet) and no USB debugging. */
        private val NIGHT_RESTRICTIONS = listOf(
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
        )

        /** The lock screen reads "This device was modified by Tolu in ways you've never seen" instead of "…your organisation". */
        private const val OWNER_NAME = "Tolu, who keeps his word"

        /** Always on: the schedule reads the clock, so the clock and time zone come from the network and can't be edited. */
        private val ALWAYS_RESTRICTIONS = listOf(UserManager.DISALLOW_CONFIG_DATE_TIME)

        @Volatile private var testPhase = Phase.NIGHT
        @Volatile private var testUntil = 0L
        @Volatile private var gateTestSince = 0L
        @Volatile private var gateTestUntil = 0L

        /**
         * Look-only preview for designing the blocked screen: `--es mood NIGHT|MORNING|EVENING|ENTRY --ei minute 360`.
         * For 90 seconds the blocked screen shows that mood at that time. It changes nothing that is locked, so it works
         * at night too.
         */
        const val ACTION_PREVIEW = "com.tolu.dpc.ACTION_PREVIEW"

        /** adb: sends one app's tide out now, for real (`--es pkg com.whatsapp.w4b`; Chrome if not given). */
        const val ACTION_TEST_TIDE = "com.tolu.dpc.ACTION_TEST_TIDE"

        /** Apps parked until a date: the Àṣàrò dev build rests until 15 Nov 2026, the end of the Six Anchors plan. */
        private val PARKED_UNTIL = mapOf(
            "com.asaro.meditation.dev" to Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 15, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis,
        )

        private fun parked(): Set<String> = PARKED_UNTIL.filterValues { System.currentTimeMillis() < it }.keys

        /** adb: `--ef drift 20` pretends 20 minutes of drift, to see Colour dim (0 puts it back). */
        const val ACTION_TEST_COLOUR = "com.tolu.dpc.ACTION_TEST_COLOUR"
        @Volatile private var previewName: String? = null
        @Volatile var previewMinute = -1
        @Volatile private var previewUntil = 0L

        fun previewMood(): String? = previewName?.takeIf {
            SystemClock.elapsedRealtime() < previewUntil && it in setOf("DAY", "NIGHT", "MORNING", "EVENING", "ENTRY", "TIDE", "PARKED")
        }

        /** A pretend minute of the day for the blocked screen during a test, or -1. Never used by the schedule itself. */
        @Volatile var testMinute = -1

        private fun startTest(phase: Phase) {
            testPhase = phase
            testUntil = SystemClock.elapsedRealtime() + TEST_MS
        }

        private fun startGateTest() {
            gateTestSince = System.currentTimeMillis()
            gateTestUntil = SystemClock.elapsedRealtime() + TEST_MS
        }

        private fun recordEntry(context: Context) {
            prefs(context).edit().putLong(KEY_LAST_ENTRY, System.currentTimeMillis()).apply()
            Log.d(TAG, "entry saved")
        }

        private fun inGrace(context: Context) = System.currentTimeMillis() < prefs(context).getLong(KEY_GRACE_UNTIL, 0L)

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** True when today's entry exists: saved today, at or after 05:00. */
        fun hasTodaysEntry(context: Context): Boolean {
            val last = prefs(context).getLong(KEY_LAST_ENTRY, 0L)
            val countsFrom = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, ENTRY_COUNTS_FROM / 60)
                set(Calendar.MINUTE, ENTRY_COUNTS_FROM % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            return last >= countsFrom
        }

        /** Between 07:00 and 12:00, the phone stays in the morning phase until today's entry exists. */
        private fun gated(context: Context): Boolean {
            if (SystemClock.elapsedRealtime() < gateTestUntil) {
                return prefs(context).getLong(KEY_LAST_ENTRY, 0L) < gateTestSince
            }
            val now = Calendar.getInstance()
            val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            return minute < GATE_RELEASE && !hasTodaysEntry(context)
        }

        // ── Public entry points ───────────────────────────────────────────────────

        /** Idempotent: put the phone in the current phase and re-arm every alarm. Safe to call as often as you like. */
        /** When enforce last ran (elapsed ms): after a Hiber freeze, the first touch uses this to catch up at once. */
        @Volatile var lastEnforceAt = 0L

        fun enforce(context: Context) {
            lastEnforceAt = SystemClock.elapsedRealtime()
            runLogged("keepAccessibility") { keepAccessibility(context) }
            runLogged("applyPhase") { applyPhase(context, currentState(context)) }
            runLogged("scheduleAlarms") { scheduleAlarms(context) }
            runLogged("refreshWidget") { FocusWidget.refresh(context) }
        }

        fun clockPhase(now: Calendar = Calendar.getInstance()): Phase {
            val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            return BOUNDARIES.lastOrNull { minute >= it.first }?.second ?: Phase.NIGHT
        }

        /** The phase to apply, and whether it comes from an adb test (tests skip the night device rules). */
        data class State(val phase: Phase, val test: Boolean, val gated: Boolean = false, val tide: Boolean = false)

        /** A test never loosens the real schedule: it only applies while the clock says DAY or EVENING. */
        fun currentState(context: Context): State {
            val clock = clockPhase()
            val daytime = clock == Phase.DAY || clock == Phase.EVENING
            if (daytime && SystemClock.elapsedRealtime() < testUntil) return State(testPhase, test = true)
            if (daytime && gated(context)) return State(Phase.MORNING, test = SystemClock.elapsedRealtime() < gateTestUntil, gated = true)
            return State(clock, test = false, tide = daytime && ColourKeeper.tideOut(context))
        }

        fun statusText(context: Context): String {
            val state = currentState(context)
            if (state.gated) return "Answer two questions in Àṣàrò to open"
            return when (state.phase) {
                Phase.MORNING -> "Bible hour · entry opens the phone"
                Phase.DAY -> "Open · Slack locks 5:30PM"
                Phase.EVENING -> "Slack off · all locks 8PM"
                Phase.NIGHT -> "Locked · jw.org opens 5AM"
            }
        }

        fun scheduleAlarms(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            LEGACY_ACTIONS.forEach { am.cancel(pendingIntentFor(context, it)) }
            ALARM_MINUTES.forEach { minute ->
                val pi = pendingIntentFor(context, actionFor(minute))
                val trigger = nextOccurrence(minute / 60, minute % 60)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, pi), pi)
                Log.d(TAG, "scheduleAlarm: ${actionFor(minute)} -> $trigger")
            }
        }

        fun cancelAlarms(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            LEGACY_ACTIONS.forEach { am.cancel(pendingIntentFor(context, it)) }
            ALARM_MINUTES.forEach { am.cancel(pendingIntentFor(context, actionFor(it))) }
        }

        /** Lift everything: every app, Chrome's site rules and the night device rules. Used when removing the DPC. */
        fun unrestrict(context: Context) {
            val (dpm, admin) = dpmAndAdmin(context)
            val all = userPackages(context).toTypedArray()
            if (all.isNotEmpty()) dpm.setPackagesSuspended(admin, all, false)
            dpm.setApplicationRestrictions(admin, CHROME, Bundle())
            (NIGHT_RESTRICTIONS + ALWAYS_RESTRICTIONS).forEach { dpm.clearUserRestriction(admin, it) }
            dpm.setOrganizationName(admin, null)
            dpm.setShortSupportMessage(admin, null)
        }

        // ── The phases ────────────────────────────────────────────────────────────

        private fun applyPhase(context: Context, state: State) {
            val (dpm, admin) = dpmAndAdmin(context)
            val phase = state.phase
            val testing = state.test
            val packages = userPackages(context)

            // In the day and evening, a tide locks the drift apps, and parked apps stay parked.
            // Soft-tided apps (WhatsApp) stay unsuspended so calls ring; BlockScreenService turns them away instead.
            val daytimeLocks = parked() + (ColourKeeper.tidedApps(context) - ColourKeeper.SOFT)
            val suspend = when (phase) {
                Phase.DAY -> packages.filter { it in daytimeLocks }
                Phase.EVENING -> packages.filter { it in SLACK || it in daytimeLocks }
                Phase.NIGHT -> packages.filter { it !in NIGHT_ALLOWED && !(bibleEvening() && it in BIBLE_APPS) }
                Phase.MORNING -> packages.filter { it !in MORNING_ALLOWED }
            }
            val release = packages - suspend.toSet()
            val failed = mutableListOf<String>()
            if (suspend.isNotEmpty()) failed += dpm.setPackagesSuspended(admin, suspend.toTypedArray(), true)
            if (release.isNotEmpty()) failed += dpm.setPackagesSuspended(admin, release.toTypedArray(), false)
            Log.d(TAG, "applyPhase: $phase (test=$testing, gated=${state.gated}) suspended ${suspend.size}, released ${release.size}, failed $failed")

            // Chrome keeps the jw.org-only rules all night too, so it stays limited even if it ever escapes suspension.
            val jwOnly = phase == Phase.NIGHT || phase == Phase.MORNING
            setChromePolicy(dpm, admin, jwOnly)

            // A test skips the device rules: blocking debugging would cut the adb session running the test,
            // and blocking tethering would cut the laptop's internet.
            // The hotspot and debugging wait until 21:00 (the plan's "laptop shut"), even though the apps lock at 20:00;
            // on Saturdays they don't wait.
            val beforeNine = phase == Phase.NIGHT && minuteNow().let { it >= 20 * 60 && it < deviceRulesFrom() }
            val nightRules = jwOnly && !testing && !inGrace(context) && !beforeNine
            // Once today's entry is written (after 05:00), the hotspot comes back even before 07:00: the Bible came
            // first, so the laptop can have its internet. USB debugging and the apps still wait for 07:00.
            val entryWritten = phase == Phase.MORNING && hasTodaysEntry(context)
            NIGHT_RESTRICTIONS.forEach {
                val keep = nightRules && !(it == UserManager.DISALLOW_CONFIG_TETHERING && entryWritten)
                if (keep) dpm.addUserRestriction(admin, it) else dpm.clearUserRestriction(admin, it)
            }
            lockClock(dpm, admin)
            // Reading the organisation name back throws on this phone ("Calling user is not authorized"), which used to end
            // this function early; setting it is allowed and idempotent, so just set it.
            runCatching { dpm.setOrganizationName(admin, OWNER_NAME) }.onFailure { Log.e(TAG, "setOrganizationName failed", it) }
            // A second lock-screen line that follows the day.
            runCatching { dpm.setDeviceOwnerLockScreenInfo(admin, lockScreenLine(context, state)) }.onFailure { Log.e(TAG, "lock screen line failed", it) }

            // The line under "Blocked by work policy" (BlockedActivity covers that dialog now, so this is a fallback).
            runCatching { dpm.setShortSupportMessage(admin, blockedMessage(state)) }
        }

        /** The lock screen's own line, by the part of the day. */
        private fun lockScreenLine(context: Context, state: State): String {
            val m = minuteNow()
            return when {
                state.gated -> "Answer two questions in Àṣàrò. Then it all opens."
                state.phase == Phase.MORNING -> if (hasTodaysEntry(context)) "Entry done. Everything opens at 7." else "Bible first. Everything opens at 7."
                state.tide -> "Tide's out for ${ColourKeeper.tidedNames(context)}. Everything else is open."
                state.phase == Phase.DAY -> "Open. Use it on purpose."
                state.phase == Phase.EVENING -> "Work's done. Everything rests at 8."
                state.phase == Phase.NIGHT && m >= 20 * 60 && m < BIBLE_UNTIL -> "The Bible's open till 10. Everything else is resting."
                else -> "Rest. JW Library opens at 5."
            }
        }

        /** What a locked app says, by the part of the day it's locked for. */
        fun blockedMessage(state: State): String = when {
            state.gated -> "Answer two questions in today's Àṣàrò entry and your phone opens."
            state.phase == Phase.NIGHT && bibleEvening() -> "It's night. JW Library and Àṣàrò are open until 10PM."
            state.tide -> "That app is out with the tide. It comes back by itself."
            state.phase == Phase.DAY -> "Àṣàrò's dev build is parked until 15 November. Your journal is open."
            state.phase == Phase.MORNING -> "Bible first. Everything opens at 7AM."
            state.phase == Phase.NIGHT -> "It's night. Rest, Tolu. JW Library and jw.org open at 5AM."
            state.phase == Phase.EVENING -> "Work's done for today. Slack opens again at 7AM."
            else -> "Locked by your focus schedule."
        }

        /**
         * Android won't let a device owner lock Settings, so the accessibility service (the blocked screen, the tides, the
         * grey) could be switched off there. It's switched straight back on here, every few minutes and on screen-on.
         */
        private fun keepAccessibility(context: Context) {
            val cr = context.contentResolver
            val me = ComponentName(context, BlockScreenService::class.java).flattenToString()
            val on = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            if (on.split(':').none { it.equals(me, ignoreCase = true) }) {
                val list = if (on.isBlank()) me else "$on:$me"
                android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list)
                android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.d(TAG, "accessibility service switched back on")
            }
        }

        /** Network time and time zone, turned on and locked, day and night. */
        private fun lockClock(dpm: DevicePolicyManager, admin: ComponentName) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!dpm.getAutoTimeEnabled(admin)) dpm.setAutoTimeEnabled(admin, true)
                if (!dpm.getAutoTimeZoneEnabled(admin)) dpm.setAutoTimeZoneEnabled(admin, true)
            } else {
                @Suppress("DEPRECATION")
                dpm.setAutoTimeRequired(admin, true)
            }
            ALWAYS_RESTRICTIONS.forEach { dpm.addUserRestriction(admin, it) }
        }

        /** Chrome's own policies, the ones work phones use. Secure DNS stays off so Chrome can't route around NextDNS. */
        private fun setChromePolicy(dpm: DevicePolicyManager, admin: ComponentName, jwOnly: Boolean) {
            val policy = Bundle().apply {
                putString("DnsOverHttpsMode", "off")
                if (jwOnly) {
                    putStringArray("URLBlocklist", arrayOf("*"))
                    putStringArray("URLAllowlist", JW_SITES)
                }
            }
            // Setting it makes Chrome reload its policies, so only set it when it changes.
            val current = dpm.getApplicationRestrictions(admin, CHROME)
            if (!samePolicy(current, policy)) {
                dpm.setApplicationRestrictions(admin, CHROME, policy)
                Log.d(TAG, "chrome policy: jwOnly=$jwOnly")
            }
        }

        private fun samePolicy(a: Bundle, b: Bundle): Boolean =
            a.keySet() == b.keySet() && a.keySet().all { key ->
                val x = a.get(key)
                val y = b.get(key)
                if (x is Array<*> && y is Array<*>) x.contentEquals(y) else x == y
            }

        // ── Internal helpers ──────────────────────────────────────────────────────

        private inline fun runLogged(what: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "$what failed", e)
            }
        }

        /** One alarm per time of day, named by it: ACTION_AT_0500, ACTION_AT_1200, … */
        private fun actionFor(minute: Int) = "com.tolu.dpc.ACTION_AT_%02d%02d".format(minute / 60, minute % 60)

        private fun nextOccurrence(hour: Int, minute: Int): Long =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis

        private fun userPackages(context: Context): List<String> {
            val pm = context.packageManager

            // Never suspend the home screen launcher.
            val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName

            val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            return pm.queryIntentActivities(launcherIntent, 0)
                .map { it.activityInfo.packageName }
                .filter { it != defaultLauncher }
                .distinct()
        }

        private fun dpmAndAdmin(context: Context): Pair<DevicePolicyManager, ComponentName> {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, AdminReceiver::class.java)
            return dpm to admin
        }

        private fun pendingIntentFor(context: Context, action: String): PendingIntent {
            val intent = Intent(context, ScheduleReceiver::class.java).apply { this.action = action }
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
