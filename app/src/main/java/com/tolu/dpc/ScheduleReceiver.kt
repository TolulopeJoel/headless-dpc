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
 *   07:00  DAY      everything open, once today's Àṣàrò entry exists (until then it stays MORNING, all day if need be)
 *   17:30  EVENING  Slack locks
 *   20:00  NIGHT    everything locks except calls, PalmPay, the clock, Recorder, Notepad, Calculator, JW Library and Àṣàrò
 *   21:00           hotspot and USB debugging off (the laptop's internet); on Saturdays at 20:00, with the apps; 22:00 the night before a
 *                   Thursday or Sunday assignment in the calendar (see Assignments)
 *   22:00           JW Library and Àṣàrò lock too
 * Every trigger works out the phase from the clock, so a missed or extra alarm can't leave the phone in the wrong state.
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: ${intent.action}")

        when (intent.action) {
            // Àṣàrò hands over the entry; it counts only once Gemini agrees it comes from the reading (see Reading).
            ACTION_ENTRY_SAVED -> Reading.received(context, intent.getStringExtra("entry"))
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
            ACTION_ASSIGNMENTS -> Log.d(TAG, "assignments:\n" + Assignments.describe(context))
            ACTION_INTERVIEWS -> Log.d(TAG, "interviews:\n" + Interviews.describe(context))
            ACTION_SET_HOME -> Away.noteHome(context, force = true)
            ACTION_SURVEY -> if (intent.getStringExtra("do") == "stop") Log.d(TAG, Away.surveyStop(context)) else Away.surveyStart(context)
            // Tests over adb: a pretend session for a few minutes, a pretend trip home, the review.
            ACTION_TEST_SESSION -> {
                val title = intent.getStringExtra("kind") ?: "Study"
                val now = System.currentTimeMillis()
                CalendarSessions.TEST[title] = SessionWindow(now - 60_000L, now + intent.getIntExtra("minutes", 5) * 60_000L)
            }
            // The bar's start buttons: a study or meeting-prep session starts now.
            // The bar in field time: "First conversation" and "Done for today".
            ACTION_FIELD_MARK -> if (intent.getStringExtra("which") == "end") Field.markEnd(context) else Field.markStart(context)
            ACTION_START -> if (Logbook.running(context) == null) Kind.of(intent.getStringExtra("kind"))?.let { Logbook.start(context, it) }
            // adb: which apps a daytime field or study session would lock, by the same rule applyPhase uses. Locks nothing.
            ACTION_CHECK_FIELD -> {
                val locked = userPackages(context).filter { it in parked() || fieldLocked(context, it) }
                Log.d(TAG, "field would lock: ${locked.sorted()}; open: ${(userPackages(context) - locked.toSet()).sorted()}")
                return
            }
            // adb test: pretend the phone is at (lat, lon) and run the people reminder (field time or not).
            ACTION_SYNC_PEOPLE -> FieldService.sync(context, force = true)
            ACTION_TEST_BACKUP -> { FieldBackup.test(context); return }
            // Relabel an old trip: "id" is its start; "work" lists stop keys to file as work; then he's asked about the rest.
            ACTION_REOPEN_OUTING -> {
                val id = intent.getStringExtra("id")?.toLongOrNull() ?: return
                Trail.reopen(context, id)
                intent.getStringExtra("work")?.split(",")?.filter { it.isNotBlank() }?.forEach { Trail.answer(context, id, it.trim(), Kind.WORK) }
                Ask.askAgain(context, id)
                return
            }
            ACTION_TEST_PEOPLE -> {
                intent.getStringExtra("open")?.let { names ->
                    context.startActivity(Intent(context, PeopleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .apply { if (names.isNotBlank()) putExtra(PeopleActivity.EXTRA_NAMES, names.split(",").toTypedArray()) })
                    return
                }
                val la = intent.getStringExtra("lat")?.toDoubleOrNull() ?: return
                val lo = intent.getStringExtra("lon")?.toDoubleOrNull() ?: return
                context.getSharedPreferences("people", Context.MODE_PRIVATE).edit().clear().apply()
                CalendarSessions.TEST["Field"] = SessionWindow(System.currentTimeMillis() - 60_000L, System.currentTimeMillis() + 120_000L)
                People.near(context, la, lo)
                CalendarSessions.TEST.remove("Field")
                return
            }
            ACTION_PREVIEW_PASS -> {
                context.startActivity(Intent(context, FieldPassActivity::class.java).putExtra(FieldPassActivity.EXTRA_PREVIEW, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            ACTION_PREVIEW_BAR -> {
                NowBar.previewFace = intent.getStringExtra("face")?.uppercase()
                NowBar.previewMode = intent.getStringExtra("mode")
                NowBar.previewUntil = SystemClock.elapsedRealtime() + 60_000L
                NowBar.refresh(context)
                return
            }
            // adb: ask the launcher to place the bar (Android shows "Add to home screen").
            ACTION_PIN_BAR -> {
                val manager = android.appwidget.AppWidgetManager.getInstance(context)
                if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(context, NowBar::class.java), null, null)
                else Log.d(TAG, "this launcher can't pin widgets")
            }
            ACTION_TEST_CLEAR -> {
                CalendarSessions.TEST.clear()
                Trail.forgetTests(context)
                FieldBackup.forgetToday(context)
                Ask.settle(context)
                intent.getLongExtra("since", 0L).takeIf { it > 0 }?.let {
                    Logbook.running(context)?.let { (_, started) -> if (started >= it) Logbook.cancel(context) }
                    Logbook.forgetSince(context, it)
                    context.getSharedPreferences("review", Context.MODE_PRIVATE).edit().remove("reviewed_${startOfDay(0)}").apply()
                }
            }
            ACTION_TEST_OUTING -> if (intent.hasExtra("path")) Trail.pretendPath(context,
                intent.getStringExtra("path")!!.split(";").mapNotNull { seg -> seg.split(",").takeIf { it.size == 3 }?.let { Triple(it[0].toDouble(), it[1].toDouble(), it[2].toInt()) } },
                intent.getIntExtra("ago", 0)) else Trail.pretend(context, intent.getIntExtra("minutes", 90),
                (intent.getStringExtra("stops") ?: "40").split(",").mapNotNull { it.trim().toIntOrNull() },
                intent.getStringExtra("lat")?.toDoubleOrNull() ?: 6.5400, intent.getStringExtra("lon")?.toDoubleOrNull() ?: 3.3600,
                intent.getIntExtra("ago", 0))
            ACTION_TEST_REVIEW -> context.startActivity(Intent(context, ReviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ACTION_AWAY -> Log.d(TAG, "away:\n" + Reading.describe(context) + "\n" + Away.describe(context) + "\n" + Trail.describe(context) + "\n" + Places.describe(context) + "\n" + Happened.describe(context) + "\n" + People.describe(context) + "\n" + FieldService.describe(context))
            ACTION_TEST_COLOUR -> {
                ColourKeeper.pretend(context, intent.getFloatExtra("drift", 0f), intent.getStringExtra("pkg") ?: "com.android.chrome")
                return   // look only: pretends minutes of drift, nothing is locked
            }
            ACTION_EXPORT_HAPPENED -> {
                val from = intent.getLongExtra("from", startOfDay(-7))
                Log.d(TAG, "exported: " + Happened.export(context, from, intent.getIntExtra("days", 7)))
                return
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
        if (intent.action == actionFor(Ask.REVIEW_AT)) Ask.reviewTime(context)
    }

    enum class Phase { MORNING, DAY, EVENING, NIGHT }

    companion object {

        private const val TAG = "dpc.ScheduleReceiver"

        // Alarms from earlier versions (v1.1's window, v1.2's phase names), cancelled on every run so they can't linger.
        private val LEGACY_ACTIONS = listOf("RESTRICT", "UNRESTRICT", "MORNING", "DAY", "EVENING", "NIGHT", "AT_1200")
            .map { "com.tolu.dpc.ACTION_$it" }

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

        /**
         * The morning gate: an entry counts for today only if it was saved after 05:00. There's no release time: the 12:00
         * one was removed on 9 Oct 2026 at his request, so without an entry the phone stays in the morning phase all day.
         */
        private const val ENTRY_COUNTS_FROM = 5 * 60
        private const val PREFS = "focus"
        private const val KEY_LAST_ENTRY = "last_entry_millis"

        /**
         * Inside the night: the Bible and Àṣàrò stay open until 22:00; the hotspot and debugging go at 21:00, except on
         * Saturdays, when they go at 20:00 with everything else. Saturday is the night before the weekend meeting and
         * the fifth field day, so the laptop closes earliest then (the life audit's advice). He moved Saturday to 21:00 on
         * 3 Oct 2026 and back to 20:00 on 9 Oct; a Sunday assignment in the calendar still gives that Saturday until 22:00.
         */
        private const val BIBLE_UNTIL = 22 * 60
        private const val DEVICE_RULES_FROM = 21 * 60
        private val BIBLE_APPS = setOf("org.jw.jwlibrary.mobile", "com.asaro.meditation", OWN_WORDS)

        /** Weekdays from 08:56 (his ask, 28 Oct 2026), the work HR site opens in Chrome even while the morning gate keeps Chrome to jw.org. */
        private const val WORK_SITES_FROM = 8 * 60 + 56
        private val WORK_SITES = arrayOf("schulltech.approvehr.io")

        /** From 09:30, Google Meet opens even while the morning gate holds, so standup can be joined (asked for on 28 Oct 2026). */
        private const val MEET_FROM = 9 * 60 + 30
        private const val MEET = "com.google.android.apps.tachyon"

        /** Meet every day, and Slack on weekdays, from 09:30 (Slack added on 28 Oct 2026, for standup). */
        private fun standupOpen(pkg: String): Boolean = minuteNow() >= MEET_FROM &&
            (pkg == MEET || (pkg in SLACK && Calendar.getInstance().get(Calendar.DAY_OF_WEEK) !in setOf(Calendar.SATURDAY, Calendar.SUNDAY)))

        /** Alarm times: the phase boundaries, the two steps inside the night, the work site and Meet. */
        private val ALARM_MINUTES = BOUNDARIES.map { it.first } + DEVICE_RULES_FROM + BIBLE_UNTIL + WORK_SITES_FROM + MEET_FROM + PAUSE_ENDS + PLANNING_ENDS + Ask.REVIEW_AT + Ask.REPORT_AT +
            FIELD_HOTSPOT_FROM + FIELD_HOTSPOT_TO

        /**
         * Field time leaves every app and site open (his call, 17 Oct); only the hotspot (the laptop's internet) goes,
         * and only from 10:30 to 13:00 within the session.
         */
        private const val FIELD_HOTSPOT_FROM = 10 * 60 + 30
        private const val FIELD_HOTSPOT_TO = 13 * 60
        private fun fieldHotspotOff(state: State) = state.field && minuteNow() in FIELD_HOTSPOT_FROM until FIELD_HOTSPOT_TO

        /** When the laptop's internet goes tonight: 20:00 on a Saturday, 21:00 otherwise. */
        fun deviceRulesFrom(): Int {
            val now = Calendar.getInstance()
            val today = "%04d-%02d-%02d".format(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
            return if (now.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY && today !in LATE_SATURDAYS) 20 * 60 else DEVICE_RULES_FROM
        }

        /** The hotspot stays allowed until this moment: he asked on 20 Oct 2026 at 20:14 for the night, until 10:00 on 21 Oct. */
        private const val HOTSPOT_OPEN_UNTIL = 1792573200000L   // 2026-10-21 10:00 WAT
        private fun hotspotOpen() = System.currentTimeMillis() < HOTSPOT_OPEN_UNTIL

        /** Everything open until this moment: a planning night he asked for on 28 Oct 2026 at 20:36, until 00:00 on 29 Oct. */
        private const val PAUSED_UNTIL = 1793228400000L   // 2026-10-29 00:00 WAT
        private fun paused() = System.currentTimeMillis() < PAUSED_UNTIL || planningNight()
        private const val PAUSE_ENDS = 0                    // the midnight alarm puts the night back

        /**
         * Planning nights: on a checkpoint Sunday (the first Sunday of the month) everything stays open from 20:00 until
         * 23:00, for the monthly check-in and next month's plan. Built in so a pause never needs a new build, and limited to
         * those Sundays so it can't become every night (28 Oct 2026).
         */
        private const val PLANNING_ENDS = 23 * 60
        fun planningNight(now: Calendar = Calendar.getInstance()): Boolean =
            now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && now.get(Calendar.DAY_OF_MONTH) <= 7 &&
                (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)).let { it >= 20 * 60 && it < PLANNING_ENDS }
        /** Google Photos stays open in the same window (asked for at 20:19). */
        private const val PHOTOS = "com.google.android.apps.photos"

        /** Saturdays that keep 21:00 like the rest, one at a time: he asked for 10 Oct 2026 at 19:51 that evening. */
        private val LATE_SATURDAYS = setOf("2026-10-10")

        /** Monday to Friday, from 08:56. */
        private fun workHours(): Boolean = Calendar.getInstance().let {
            it.get(Calendar.DAY_OF_WEEK) !in setOf(Calendar.SATURDAY, Calendar.SUNDAY) && minuteNow() >= WORK_SITES_FROM
        }

        private fun minuteNow(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

        /** 20:00–22:00: the night has started, but the Bible and Àṣàrò are still open. */
        fun bibleEvening(): Boolean = minuteNow().let { it >= 20 * 60 && it < BIBLE_UNTIL }

        private const val CHROME = "com.android.chrome"
        private val SLACK = setOf("com.Slack")

        /** Never locked: calls, PalmPay, the alarm clock, the tools that capture rather than feed, and the DPC itself. */
        private val NIGHT_ALLOWED = setOf(
            "com.sh.smart.caller",          // phone calls (Android won't suspend the dialer anyway)
            "com.transsnet.palmpay",
            "com.transsion.deskclock",      // so the morning alarm always rings
            "com.transsion.soundrecorder",  // Recorder: voice memos, talk practice
            "com.transsion.notebook",       // Notepad: write the thought down and let it go
            "com.transsion.calculator",
            "com.transsion.camera",         // Camera: capture it, like Notepad
            "com.google.android.calendar",  // Calendar: tomorrow can be planned at any hour (28 Oct 2026); no time limit
            "com.android.settings",         // Settings, always: its risky parts are locked on their own
            "com.android.settings.intelligence",
            "com.tolu.dpc",
        )

        /** Added from 05:00: the Bible hour. */
        private val MORNING_ALLOWED = NIGHT_ALLOWED + setOf(
            CHROME,                         // jw.org and wol.jw.org only, set by chromePolicy
            "org.jw.jwlibrary.mobile",
            "com.asaro.meditation",
            OWN_WORDS,                      // In My Own Words: where the writing lives, open like Àṣàrò
            FIELD_SERVICE,                  // so hours can be logged on an early public-witnessing morning
        )

        private const val FIELD_SERVICE = "com.lostpixels.fieldservice"
        private const val OWN_WORDS = "com.tolu.ownwords"

        /** jw.org covers its subdomains, wol.jw.org included; jw-cdn.org serves their images and media. */
        private val JW_SITES = arrayOf("jw.org", "wol.jw.org", "jw-cdn.org")

        /**
         * Google's results page only, wherever Chrome keeps to jw.org: a word can be looked up (the definition box sits on
         * the results page), but a result can't be opened, since every other site stays blocked. Asked for on 10 Oct.
         */
        private val SEARCH_PAGES = arrayOf("www.google.com/search", "google.com/search", "www.google.com.ng/search", "google.com.ng/search")

        /** Night-only device rules: no hotspot (the laptop's internet) and no USB debugging. */
        private val NIGHT_RESTRICTIONS = listOf(
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
        )

        /** The lock screen reads "This device was modified by Tolu in ways you've never seen" instead of "…your organisation". */
        private const val OWNER_NAME = "Tolu, who keeps his word"

        /** Always on: the schedule reads the clock, so the clock and time zone come from the network and can't be edited. */
        /** Day and night: the clock (network time) and location (the field pass's proof) can't be switched off. */
        private val ALWAYS_RESTRICTIONS = listOf(UserManager.DISALLOW_CONFIG_DATE_TIME, UserManager.DISALLOW_CONFIG_LOCATION)

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

        /** adb: logs the assignments the DPC has found in the calendar, and which nights they open (look only). */
        const val ACTION_ASSIGNMENTS = "com.tolu.dpc.ACTION_ASSIGNMENTS"

        /** adb: logs today's interviews that count, as windows (look only). */
        const val ACTION_INTERVIEWS = "com.tolu.dpc.ACTION_INTERVIEWS"

        /** adb: note home here, now (normally learned in the Bible hour), and: log what the away tracker knows. */
        const val ACTION_SET_HOME = "com.tolu.dpc.ACTION_SET_HOME"
        const val ACTION_START = "com.tolu.dpc.ACTION_START"
        const val ACTION_FIELD_MARK = "com.tolu.dpc.ACTION_FIELD_MARK"
        const val ACTION_PIN_BAR = "com.tolu.dpc.ACTION_PIN_BAR"
        const val ACTION_PREVIEW_BAR = "com.tolu.dpc.ACTION_PREVIEW_BAR"
        const val ACTION_PREVIEW_PASS = "com.tolu.dpc.ACTION_PREVIEW_PASS"
        const val ACTION_TEST_PEOPLE = "com.tolu.dpc.ACTION_TEST_PEOPLE"
        const val ACTION_TEST_BACKUP = "com.tolu.dpc.ACTION_TEST_BACKUP"
        const val ACTION_REOPEN_OUTING = "com.tolu.dpc.ACTION_REOPEN_OUTING"
        const val ACTION_SYNC_PEOPLE = "com.tolu.dpc.ACTION_SYNC_PEOPLE"
        const val ACTION_CHECK_FIELD = "com.tolu.dpc.ACTION_CHECK_FIELD"
        const val ACTION_TEST_SESSION = "com.tolu.dpc.ACTION_TEST_SESSION"
        const val ACTION_TEST_CLEAR = "com.tolu.dpc.ACTION_TEST_CLEAR"
        const val ACTION_TEST_OUTING = "com.tolu.dpc.ACTION_TEST_OUTING"
        const val ACTION_TEST_REVIEW = "com.tolu.dpc.ACTION_TEST_REVIEW"
        const val ACTION_AWAY = "com.tolu.dpc.ACTION_AWAY"
        const val ACTION_EXPORT_HAPPENED = "com.tolu.dpc.ACTION_EXPORT_HAPPENED"
        const val ACTION_SURVEY = "com.tolu.dpc.ACTION_SURVEY"

        /** Apps parked until a date: the Àṣàrò dev build rests until 1 May 2027, the day after the six-month lock-in (28 Oct 2026). */
        private val PARKED_UNTIL = mapOf(
            "com.asaro.meditation.dev" to Calendar.getInstance().apply { set(2027, Calendar.MAY, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis,
        )

        private fun parked(): Set<String> = PARKED_UNTIL.filter { (_, until) -> System.currentTimeMillis() < until }.keys

        /** adb: `--ef drift 20` pretends 20 minutes of drift, to see Colour dim (0 puts it back). */
        const val ACTION_TEST_COLOUR = "com.tolu.dpc.ACTION_TEST_COLOUR"
        @Volatile private var previewName: String? = null
        @Volatile var previewMinute = -1
        @Volatile private var previewUntil = 0L

        fun previewMood(): String? = previewName?.takeIf {
            SystemClock.elapsedRealtime() < previewUntil && it in setOf("DAY", "NIGHT", "MORNING", "EVENING", "ENTRY", "TIDE", "PARKED", "FIELD", "STUDY")
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

        /** Today's entry passed Reading's check; `at` is when Àṣàrò handed it over. */
        fun recordEntry(context: Context, at: Long) {
            prefs(context).edit().putLong(KEY_LAST_ENTRY, at).apply()
            if (at >= entryCountsFrom()) Happened.noteEntry(context)
            Log.d(TAG, "entry passed")
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** True when today's entry exists: saved today, at or after 05:00. */
        fun hasTodaysEntry(context: Context): Boolean = prefs(context).getLong(KEY_LAST_ENTRY, 0L) >= entryCountsFrom()

        /** Today at 05:00: an entry from before then is yesterday's. */
        fun entryCountsFrom(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, ENTRY_COUNTS_FROM / 60)
            set(Calendar.MINUTE, ENTRY_COUNTS_FROM % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** From 07:00, the phone stays in the morning phase until today's entry exists. */
        private fun gated(context: Context): Boolean {
            if (SystemClock.elapsedRealtime() < gateTestUntil) {
                return prefs(context).getLong(KEY_LAST_ENTRY, 0L) < gateTestSince
            }
            return !hasTodaysEntry(context)
        }

        // ── Public entry points ───────────────────────────────────────────────────

        /** Idempotent: put the phone in the current phase and re-arm every alarm. Safe to call as often as you like. */
        /** Apps that should be locked right now but that Android refused to suspend; BlockScreenService turns them away. */
        @Volatile var softLocked: Set<String> = emptySet()

        /** When enforce last ran (elapsed ms): after a Hiber freeze, the first touch uses this to catch up at once. */
        @Volatile var lastEnforceAt = 0L

        fun enforce(context: Context) {
            lastEnforceAt = SystemClock.elapsedRealtime()
            runLogged("keepAccessibility") { keepAccessibility(context) }
            runLogged("applyPhase") { applyPhase(context, currentState(context)) }
            runLogged("scheduleAlarms") { scheduleAlarms(context) }
            runLogged("refreshWidget") { FocusWidget.refresh(context) }
            runLogged("refreshBar") { NowBar.refresh(context) }
            runLogged("wallpaper") { ModeWallpaper.apply(context, currentState(context)) }
            runLogged("reading") { Reading.retry(context) }
        }

        fun clockPhase(now: Calendar = Calendar.getInstance()): Phase {
            val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            return BOUNDARIES.lastOrNull { minute >= it.first }?.second ?: Phase.NIGHT
        }

        /** The phase to apply, and whether it comes from an adb test (tests skip the night device rules). */
        data class State(val phase: Phase, val test: Boolean, val gated: Boolean = false, val tide: Boolean = false, val field: Boolean = false,
                         val study: Boolean = false, val paused: Boolean = false)

        /** A test never loosens the real schedule: it only applies while the clock says DAY or EVENING. */
        fun currentState(context: Context): State {
            val clock = clockPhase()
            val daytime = clock == Phase.DAY || clock == Phase.EVENING
            // A pause opens everything; test = true keeps the night device rules (hotspot, debugging) off as well.
            if (paused()) return State(Phase.DAY, test = true, paused = true)
            if (daytime && SystemClock.elapsedRealtime() < testUntil) return State(testPhase, test = true)
            if (daytime && gated(context)) return State(Phase.MORNING, test = SystemClock.elapsedRealtime() < gateTestUntil, gated = true)
            return State(clock, test = false, tide = daytime && ColourKeeper.tideOut(context),
                field = daytime && Field.holding(context) != null, study = daytime && Study.holding(context) != null)
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
            // Today's field and study sessions start and end whenever the calendar says: the DPC wakes at each edge.
            runCatching {
                (Field.edgesAhead(context) + Study.edgesAhead(context) + Interviews.edgesAhead(context)).distinct().forEach { at ->
                    val action = "com.tolu.dpc.ACTION_SESSION_" + java.text.SimpleDateFormat("HHmm", java.util.Locale.UK).format(java.util.Date(at))
                    val pi = pendingIntentFor(context, action)
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi), pi)
                }
            }.onFailure { Log.e(TAG, "field alarms failed", it) }
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
            val interview = Interviews.now(context)
            val suspend = when (phase) {
                Phase.DAY -> if (state.paused) emptyList() else packages.filter { it in daytimeLocks || (state.study && fieldLocked(context, it)) }
                Phase.EVENING -> packages.filter { it in SLACK || it in daytimeLocks || (state.study && fieldLocked(context, it)) }
                Phase.NIGHT -> packages.filter {
                    it !in NIGHT_ALLOWED && !(bibleEvening() && it in BIBLE_APPS) && !(hotspotOpen() && it == PHOTOS) && !(interview && it == MEET)
                }
                Phase.MORNING -> packages.filter {
                    it !in MORNING_ALLOWED && !(hotspotOpen() && it == PHOTOS) && !standupOpen(it) && !(interview && it == MEET)
                }
            }
            val release = packages - suspend.toSet()
            val failed = mutableListOf<String>()
            if (suspend.isNotEmpty()) failed += dpm.setPackagesSuspended(admin, suspend.toTypedArray(), true)
            // Android won't suspend some apps (Play Store, as the app verifier; Settings on HiOS). Those are turned away
            // at the door by BlockScreenService instead.
            softLocked = failed.toSet().intersect(suspend.toSet())
            if (release.isNotEmpty()) failed += dpm.setPackagesSuspended(admin, release.toTypedArray(), false)
            Log.d(TAG, "applyPhase: $phase (test=$testing, gated=${state.gated}) suspended ${suspend.size}, released ${release.size}, failed $failed")

            // Chrome keeps the jw.org-only rules all night too, so it stays limited even if it ever escapes suspension.
            val jwOnly = phase == Phase.NIGHT || phase == Phase.MORNING
            if (setChromePolicy(dpm, admin, jwOnly || state.study)) restartChrome(context)

            // A test skips the device rules: blocking debugging would cut the adb session running the test,
            // and blocking tethering would cut the laptop's internet.
            // The hotspot and debugging wait until 21:00 (the plan's "laptop shut"), even though the apps lock at 20:00;
            // on Saturdays they don't wait; the night before an assignment, they wait until 22:00, to prepare on the laptop.
            Assignments.grantCalendar(context, dpm, admin)
            // The field pass's proof: location kept on, home noted in the Bible hour, the circle watched.
            runCatching { Away.keepLocationOn(context, dpm, admin); Away.watch(context); Trail.keep(context); Ask.daily(context); Ask.weeklyReminder(context) }
                .onFailure { Log.e(TAG, "away tracking failed", it) }
            val assignment = Assignments.tomorrow(context)
            val rulesFrom = if (assignment) BIBLE_UNTIL else deviceRulesFrom()
            val beforeRules = phase == Phase.NIGHT && minuteNow().let { it >= 20 * 60 && it < rulesFrom }
            val nightRules = jwOnly && !testing && !beforeRules
            // Once today's entry is written (after 05:00), the hotspot comes back even before 07:00: the Bible came
            // first, so the laptop can have its internet. USB debugging and the apps still wait for 07:00.
            val entryWritten = phase == Phase.MORNING && hasTodaysEntry(context)
            NIGHT_RESTRICTIONS.forEach {
                val keep = nightRules && !(it == UserManager.DISALLOW_CONFIG_TETHERING && entryWritten) ||
                    // A field session takes the laptop's internet (the hotspot) from 10:30 to 13:00, not USB debugging.
                    (it == UserManager.DISALLOW_CONFIG_TETHERING && fieldHotspotOff(state) && !testing)
                val open = it == UserManager.DISALLOW_CONFIG_TETHERING && (hotspotOpen() || interview)
                if (keep && !open) dpm.addUserRestriction(admin, it) else dpm.clearUserRestriction(admin, it)
            }
            lockClock(dpm, admin)
            // Reading the organisation name back throws on this phone ("Calling user is not authorized"), which used to end
            // this function early; setting it is allowed and idempotent, so just set it.
            runCatching { dpm.setOrganizationName(admin, OWNER_NAME) }.onFailure { Log.e(TAG, "setOrganizationName failed", it) }
            // A second lock-screen line that follows the day.
            runCatching { dpm.setDeviceOwnerLockScreenInfo(admin, lockScreenLine(context, state, assignment)) }.onFailure { Log.e(TAG, "lock screen line failed", it) }

            // The line under "Blocked by work policy" (BlockedActivity covers that dialog now, so this is a fallback).
            runCatching { dpm.setShortSupportMessage(admin, blockedMessage(state)) }
        }

        /** The lock screen's own line, by the part of the day. */
        private fun lockScreenLine(context: Context, state: State, assignment: Boolean): String {
            val m = minuteNow()
            return when {
                state.gated -> "Answer two questions in Àṣàrò. Then it all opens."
                state.phase == Phase.MORNING -> if (hasTodaysEntry(context)) "Entry done. Everything opens at 7." else "Bible first. Everything opens at 7."
                fieldHotspotOff(state) -> "Field time. The hotspot's off till ${hhmm(minOf(Field.endsAt(context) ?: Long.MAX_VALUE, startOfDay(0) + FIELD_HOTSPOT_TO * 60_000L))}."
                state.field -> "Field time. Everything's open."
                state.study -> "Study time. JW Library's open; the rest waits till ${hhmm(Study.endsAt(context))}."
                state.tide -> "Tide's out for ${ColourKeeper.tidedNames(context)}. Everything else is open."
                state.phase == Phase.DAY -> "Open. Use it on purpose."
                state.phase == Phase.EVENING -> "Work's done. Everything rests at 8."
                state.phase == Phase.NIGHT && m >= 20 * 60 && m < BIBLE_UNTIL && assignment -> "Assignment tomorrow. The laptop's online till 10."
                state.phase == Phase.NIGHT && m >= 20 * 60 && m < BIBLE_UNTIL -> "The Bible's open till 10. Everything else is resting."
                else -> "Rest. JW Library opens at 5."
            }
        }

        /** What a locked app says, by the part of the day it's locked for. */
        fun blockedMessage(state: State): String = when {
            state.gated -> "Answer two questions in today's Àṣàrò entry and your phone opens."
            state.phase == Phase.NIGHT && bibleEvening() -> "It's night. JW Library and Àṣàrò are open until 10PM."
            state.study -> "It's study time. This opens when the session ends."
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
        /** True if Chrome's rules changed. */
        private fun setChromePolicy(dpm: DevicePolicyManager, admin: ComponentName, jwOnly: Boolean): Boolean {
            val policy = Bundle().apply {
                putString("DnsOverHttpsMode", "off")
                if (jwOnly) {
                    putStringArray("URLBlocklist", arrayOf("*"))
                    putStringArray("URLAllowlist", JW_SITES + SEARCH_PAGES + (if (workHours()) WORK_SITES else emptyArray()))
                }
            }
            // Setting it makes Chrome reload its policies, so only set it when it changes.
            val current = dpm.getApplicationRestrictions(admin, CHROME)
            if (!samePolicy(current, policy)) {
                dpm.setApplicationRestrictions(admin, CHROME, policy)
                Log.d(TAG, "chrome policy: jwOnly=$jwOnly, work site=${jwOnly && workHours()}")
                return true
            }
            return false
        }

        /**
         * A Chrome already running keeps its old rules until it restarts (seen 12 Oct: a Study session left Wikipedia
         * open). So when the rules change, Chrome is closed: sent home first if it's in front, then its process ended.
         * Its tabs come back when it opens again, under the new rules.
         */
        private fun restartChrome(context: Context) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val inFront = ColourKeeper.front() == CHROME
            if (inFront) BlockScreenService.goHome()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { am.killBackgroundProcesses(CHROME) }.onFailure { Log.e(TAG, "closing Chrome failed", it) }
                Log.d(TAG, "chrome closed so it reads its new rules (was in front: $inFront)")
            }, if (inFront) 800L else 0L)
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
        /** During a study session: the drift apps rest, except Chrome (jw.org only) and WhatsApp (the group's arrangements). */
        private fun fieldLocked(context: Context, pkg: String) =
            pkg != CHROME && pkg !in ColourKeeper.SOFT && ColourKeeper.isDrift(context, pkg)

        private fun hhmm(at: Long?): String =
            at?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(it)) } ?: "later"

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
