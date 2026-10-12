package com.tolu.dpc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import android.view.View
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Behind the ring widget: what's going on right now and till when, what's resting, what's next today, the one useful
 * button, and the two starts: personal study and meeting prep (only he knows when he's studying, so he says so).
 */
class NowActivity : Activity() {

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val state = ScheduleReceiver.currentState(this)
        val mode = ModeWallpaper.modeOf(this, state)
        val ui = Ui(this, dark = mode == "night")
        val colour = COLOURS[mode] ?: ui.ink
        val now = System.currentTimeMillis()
        val minute = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val todayAt = { m: Int -> startOfDay(0) + m * 60_000L }

        // This stretch of the day: from when to when.
        val window: SessionWindow? = when (mode) {
            "field" -> Field.holding(this)?.let { SessionWindow(it.start, Field.endsAt(this) ?: it.end) }
            "study" -> Study.holding(this)
            "work" -> WorkTime.holding(this)
            "bank" -> BankTime.holding(this)
            "morning" -> SessionWindow(todayAt(5 * 60), todayAt(7 * 60))
            "night" -> if (minute >= 12 * 60) SessionWindow(todayAt(20 * 60), todayAt(29 * 60)) else SessionWindow(todayAt(-4 * 60), todayAt(5 * 60))
            "free" -> freeWindow(now)
            else -> null
        }

        val content = ui.column()
        content.addView(ui.kick("Now"))
        content.addView(ui.big("■ " + (NAMES[mode] ?: mode)).apply {
            text = android.text.SpannableString(text).apply { setSpan(android.text.style.ForegroundColorSpan(if (ui.dark) 0xFFE8D6A0.toInt() else colour), 0, 1, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.45f), 0, 1, 0) }
        })
        if (window != null) {
            val frac = ((now - window.start).toFloat() / (window.end - window.start)).coerceIn(0f, 1f)
            content.addView(ui.bar(frac, if (ui.dark) 0xFFE8D6A0.toInt() else colour))
            content.addView(ui.ends("till ${hhmm(window.end)}", left(window.end - now)))
        } else if (mode == "entry") content.addView(ui.sub("till you've written today's entry"))

        // What's resting.
        val tethering = (getSystemService(USER_SERVICE) as UserManager).hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING)
        val rules: List<Pair<String, String>> = when (mode) {
            "morning" -> listOf("Phone" to "JW Library, Àṣàrò, Field Service", "Chrome" to "jw.org + search")
            "entry" -> listOf("Phone" to "waits for today's entry")
            "field", "study" -> listOf("Fun apps" to "resting", "Chrome" to "jw.org only")
            "work", "bank" -> listOf("Phone" to "open", "Side projects" to "resting")
            "night" -> listOf("Phone" to "calls, clock, tools", "JW Library, Àṣàrò" to "till 22:00")
            else -> if (state.phase == ScheduleReceiver.Phase.NIGHT) listOf("Phone" to "calls, clock, tools", "Side projects" to "open till offline")
                else listOf("Phone" to "open", "Side projects" to "open")
        }
        content.addView(ui.gap(8))
        rules.forEach { (k, v) -> content.addView(ui.row(k, ui.text(v, 12.5f, ui.body, ui.ink2).apply { gravity = android.view.Gravity.END; maxWidth = ui.dp(170) })) }
        content.addView(ui.row("Hotspot", ui.pill(if (tethering) "off" else "on", !tethering)))
        if (Field.passedToday(this)) content.addView(ui.row("Field pass", ui.pill("taken", true)))

        // A session he's logging, if one is running.
        Logbook.running(this)?.let { (kind, since) ->
            content.addView(ui.card(
                ui.kick(if (kind == Kind.STUDY) "Studying" else "Meeting prep", kind.colour),
                ui.text(left(now - since, suffix = false), 24f, ui.display, ui.ink, 2),
                ui.sub("since ${hhmm(since)}"),
            ).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14) }; setOnClickListener { openSession() } })
        }

        // Next today.
        val ahead = buildList {
            Field.today(this@NowActivity).forEach { add(Triple(it.start, "Field service", Kind.FIELD.colour)) }
            Study.today(this@NowActivity).forEach { add(Triple(it.start, "Study", Kind.STUDY.colour)) }
            WorkTime.today(this@NowActivity).forEach { add(Triple(it.start, "Work", Kind.WORK.colour)) }
            BankTime.today(this@NowActivity).forEach { add(Triple(it.start, "Kowane", Kind.BANK.colour)) }
            if (minute < Ask.REVIEW_AT) add(Triple(todayAt(Ask.REVIEW_AT), "Review the day", ui.ink))
            if (minute < ScheduleReceiver.deviceRulesFrom()) add(Triple(todayAt(ScheduleReceiver.deviceRulesFrom()), "Laptop offline", ui.ink))
        }.filter { it.first > now }.sortedBy { it.first }.take(4)
        content.addView(ui.kick("Next today", top = 18))
        if (ahead.isEmpty()) content.addView(ui.sub("Nothing else today.", 8))
        ahead.forEach { (at, label, c) -> content.addView(ui.edged(hhmm(at), label, c)) }

        // The buttons: the one for the moment, then the starts.
        val footer = ui.footer()
        val action: Pair<String, () -> Unit>? = when {
            Ask.reviewDue(this) -> "Review today" to { startActivity(Intent(this, ReviewActivity::class.java)) }
            mode == "field" -> "Can't go out? Field pass" to { startActivity(Intent(this, FieldPassActivity::class.java)) }
            mode == "entry" -> "Write today's entry" to { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("asaro://addEntry")).setPackage("com.asaro.meditation").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            mode == "morning" -> "Open JW Library" to { packageManager.getLaunchIntentForPackage("org.jw.jwlibrary.mobile")?.let { startActivity(it) }; Unit }
            else -> null
        }
        action?.let { (label, go) -> footer.addView(ui.button(label, if (ui.dark) 0xFFE8D6A0.toInt() else colour, if (ui.dark) ui.page else 0xFFFFFFFF.toInt()) { go() }) }
        if (!state.gated && Logbook.running(this) == null && (mode != "night" || minute < 22 * 60)) {
            footer.addView(start(ui, Kind.STUDY, "Start study", "Personal Bible study"))
            footer.addView(start(ui, Kind.PREP, "Start meeting prep", "${Logbook.nextMeeting().label} · ${if (Logbook.nextMeeting() == Logbook.Meeting.MIDWEEK) "Thursday" else "Sunday"}"))
        }
        setContentView(ui.page(content, footer))
    }

    /** A big start button: ▶, the name, a small line under it. */
    private fun start(ui: Ui, kind: Kind, label: String, under: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); background = ui.rounded(kind.colour, 18)
        addView(ui.text("▶", 13f, ui.strong, 0xFFFFFFFF.toInt()).apply {
            gravity = android.view.Gravity.CENTER; background = ui.rounded(0x38FFFFFF, 999)
        }, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), 0, 0, 0)
            addView(ui.text(label, 15f, ui.strong, 0xFFFFFFFF.toInt()))
            addView(ui.text(under, 12f, ui.body, 0xE6FFFFFF.toInt()))
        })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) }
        setOnClickListener { Logbook.start(this@NowActivity, kind); openSession() }
    }

    private fun openSession() = startActivity(Intent(this, SessionActivity::class.java))

    /** Free time runs from the last session that ended today (or 07:00) to the next one (or the 20:00 lock). */
    private fun freeWindow(now: Long): SessionWindow {
        val all = Field.today(this) + Study.today(this) + WorkTime.today(this) + BankTime.today(this)
        val from = all.filter { it.end <= now }.maxOfOrNull { it.end } ?: (startOfDay(0) + 7 * 3_600_000L)
        val to = all.filter { it.start > now }.minOfOrNull { it.start } ?: (startOfDay(0) + 20 * 3_600_000L)
        return SessionWindow(from, maxOf(to, now + 60_000L))
    }

    companion object {
        val COLOURS = mapOf(
            "free" to 0xFFC8323F.toInt(), "field" to Kind.FIELD.colour, "study" to Kind.STUDY.colour,
            "work" to Kind.WORK.colour, "bank" to Kind.BANK.colour, "entry" to 0xFFC76A24.toInt(),
            "morning" to 0xFFC76A24.toInt(), "night" to 0xFF17263F.toInt(),
        )
        val NAMES = mapOf(
            "free" to "Free time", "field" to "Field service", "study" to "Study", "work" to "Work", "bank" to "Kowane",
            "entry" to "Today's entry first", "morning" to "Bible hour", "night" to "Night · rest",
        )

        fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.UK).format(Date(t))

        /** "3 h 30 left", or with suffix = false "1:27" (hours:minutes). */
        fun left(ms: Long, suffix: Boolean = true): String {
            val m = (maxOf(0L, ms) / 60_000L).toInt()
            if (!suffix) return "%d:%02d".format(m / 60, m % 60)
            return when { m >= 60 && m % 60 == 0 -> "${m / 60} h left"; m >= 60 -> "${m / 60} h ${m % 60} left"; else -> "$m m left" }
        }
    }
}
