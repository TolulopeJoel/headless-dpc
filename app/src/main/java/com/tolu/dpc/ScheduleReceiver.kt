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
 *   21:00  NIGHT    everything locks except calls, PalmPay and the clock; hotspot off
 * Every trigger works out the phase from the clock, so a missed or extra alarm can't leave the phone in the wrong state.
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: ${intent.action}")

        when (intent.action) {
            ACTION_ENTRY_SAVED -> recordEntry(context)
            ACTION_GRACE -> recordGrace(context, intent.getLongExtra(EXTRA_UNTIL, 0L))
            ACTION_TEST_GATE -> startGateTest()
            ACTION_TEST_NIGHT -> startTest(Phase.NIGHT)
            ACTION_TEST_MORNING -> startTest(Phase.MORNING)
            ACTION_TEST_END -> { testUntil = 0L; gateTestUntil = 0L }
        }
        // Tests can also pretend a time of day (--ei minute 1320 is 22:00), for the blocked screen's sky and countdown only.
        when (intent.action) {
            ACTION_TEST_GATE, ACTION_TEST_NIGHT, ACTION_TEST_MORNING -> testMinute = intent.getIntExtra("minute", -1)
            ACTION_TEST_END -> { testMinute = -1; previewUntil = 0L }   // enforce() below redraws the widget
            ACTION_TEST_TIDE -> ColourKeeper.testTide(context)
            ACTION_TEST_COLOUR -> {
                ColourKeeper.pretend(context, intent.getFloatExtra("drift", 0f))
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
         * One-off grace from adb: USB debugging and the hotspot stay on until `until` (epoch ms, at most 6 hours ahead).
         * Apps still lock on time; only the two night device rules wait. It can only be sent while debugging is still on.
         */
        const val ACTION_GRACE = "com.tolu.dpc.ACTION_GRACE"
        const val EXTRA_UNTIL = "until"
        private const val KEY_GRACE_UNTIL = "grace_until_millis"
        private const val GRACE_MAX_MS = 6 * 60 * 60 * 1000L

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
            21 * 60 to Phase.NIGHT,
        )

        /** The morning gate: an entry counts for today only if it was saved after 05:00; 12:00 releases the gate regardless. */
        private const val ENTRY_COUNTS_FROM = 5 * 60
        private const val GATE_RELEASE = 12 * 60
        private const val PREFS = "focus"
        private const val KEY_LAST_ENTRY = "last_entry_millis"

        /** Alarm times: the phase boundaries plus the gate's release. */
        private val ALARM_MINUTES = BOUNDARIES.map { it.first } + GATE_RELEASE

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

        /** The lock screen reads "This device belongs to Tolu" instead of "…your organisation". */
        private const val OWNER_NAME = "Tolu"

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

        /** adb: sends the tide out now, for real (the drift apps lock for the next tide's length). */
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

        private fun recordGrace(context: Context, until: Long) {
            val capped = until.coerceAtMost(System.currentTimeMillis() + GRACE_MAX_MS)
            prefs(context).edit().putLong(KEY_GRACE_UNTIL, capped).apply()
            Log.d(TAG, "grace until $capped")
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
        fun enforce(context: Context) {
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
            if (state.gated) return "Write today's Àṣàrò entry to open"
            return when (state.phase) {
                Phase.MORNING -> "Bible hour · entry opens the phone"
                Phase.DAY -> "Open · Slack locks 5:30PM"
                Phase.EVENING -> "Slack off · all locks 9PM"
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
            val daytimeLocks = parked() + if (state.tide) ColourKeeper.DRIFT.keys else emptySet()
            val suspend = when (phase) {
                Phase.DAY -> packages.filter { it in daytimeLocks }
                Phase.EVENING -> packages.filter { it in SLACK || it in daytimeLocks }
                Phase.NIGHT -> packages.filter { it !in NIGHT_ALLOWED }
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
            val nightRules = jwOnly && !testing && !inGrace(context)
            NIGHT_RESTRICTIONS.forEach {
                if (nightRules) dpm.addUserRestriction(admin, it) else dpm.clearUserRestriction(admin, it)
            }
            lockClock(dpm, admin)
            if (dpm.getOrganizationName(admin)?.toString() != OWNER_NAME) dpm.setOrganizationName(admin, OWNER_NAME)

            // The line under "Blocked by work policy" when a locked app is opened.
            val message = blockedMessage(state)
            if (dpm.getShortSupportMessage(admin)?.toString() != message) dpm.setShortSupportMessage(admin, message)
        }

        /** What a locked app says, by the part of the day it's locked for. */
        fun blockedMessage(state: State): String = when {
            state.gated -> "Write today's Àṣàrò entry and your phone opens."
            state.tide -> "Chrome, WhatsApp and Instagram are out with the tide. They come back by themselves."
            state.phase == Phase.DAY -> "Àṣàrò's dev build is parked until 15 November. Your journal is open."
            state.phase == Phase.MORNING -> "Bible first. Everything opens at 7AM."
            state.phase == Phase.NIGHT -> "It's night. Rest, Tolu. JW Library and jw.org open at 5AM."
            state.phase == Phase.EVENING -> "Work's done for today. Slack opens again at 7AM."
            else -> "Locked by your focus schedule."
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
