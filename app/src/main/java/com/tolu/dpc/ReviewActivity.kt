package com.tolu.dpc

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The 20:01 review: today, top to bottom. Sure rows have a tick; a guess is confirmed with Yes (No opens the full
 * question); an unknown is answered right there or with "…". "+ Add a session I forgot" for study or prep he didn't
 * start. "Looks right" confirms the day and writes it to the Happened calendar. Skipping is fine.
 */
class ReviewActivity : Activity() {

    override fun onStop() {
        super.onStop()
        NowBar.refresh(this)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val ui = Ui(this)
        val blocks = Day.blocks(this)
        val content = ui.column()
        content.addView(ui.kick(SimpleDateFormat("EEEE", Locale.UK).format(Date())))
        content.addView(ui.big("Your day"))
        val sure = blocks.count { it.status == Day.Status.SURE }
        val check = Day.toCheck(blocks).size
        content.addView(ui.text(android.text.SpannableStringBuilder().apply {
            fun number(n: Int) {
                val at = length; append("$n")
                setSpan(android.text.style.RelativeSizeSpan(1.25f), at, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(ui.ink), at, length, 0)
                if (android.os.Build.VERSION.SDK_INT >= 28) setSpan(android.text.style.TypefaceSpan(ui.display), at, length, 0)
            }
            number(sure); append(" sure   ·   "); number(check); append(" to check")
        }, 13.5f, ui.body, ui.ink2, 4))
        content.addView(ui.gap(8))
        if (blocks.isEmpty()) content.addView(ui.sub("Nothing logged or seen today.", 8))
        blocks.forEach { content.addView(row(ui, it)) }
        content.addView(ui.text("+ Add a session I forgot", 14f, ui.strong, ui.ink, 14).apply {
            setOnClickListener { startActivity(Intent(this@ReviewActivity, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_ADD, true)) }
        })
        val footer = ui.footer()
        footer.addView(ui.button("Looks right · plan tomorrow") {
            Day.markReviewed(this)
            Happened.write(this, startOfDay(0))
            FocusWidget.refresh(this)
            TodayLine.planTomorrow(this)
            finish()
        })
        setContentView(ui.page(content, footer))
    }

    private fun row(ui: Ui, b: Day.Block): View {
        val colour = if (b.status == Day.Status.UNKNOWN) ui.muted else b.kind.colour
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(14), ui.dp(9), ui.dp(10), ui.dp(9))
            val card = when (b.status) {
                Day.Status.GUESS -> ui.rounded(ui.card, 14, ui.ink, 1.5f)
                Day.Status.UNKNOWN -> ui.rounded(0, 14, ui.muted, 1.5f, dashed = true)
                else -> ui.rounded(ui.card, 14, ui.line)
            }
            // A coloured edge on the left, inside the card.
            val edge = GradientDrawable().apply { setColor(colour); cornerRadius = ui.dpf(3f) }
            background = LayerDrawable(arrayOf(card, edge)).apply {
                setLayerInset(1, 0, ui.dp(9), 0, ui.dp(9)); setLayerWidth(1, ui.dp(4)); setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
            }
            if (b.status == Day.Status.MISSED) alpha = 0.55f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(7) }
        }
        row.addView(ui.text(NowActivity.hhmm(b.from), 12f, ui.body, ui.muted), LinearLayout.LayoutParams(ui.dp(44), -2))
        val under = buildList {
            add(if (b.minutes >= 60) "${b.minutes / 60} h ${"%02d".format(b.minutes % 60)}" else "${b.minutes} m")
            if (b.late) add("added later")
            if (b.guessed) add("${NowActivity.hhmm(b.from)}–${NowActivity.hhmm(b.to)} guessed")
            if (b.status == Day.Status.UNKNOWN) add("new place")
        }.joinToString(" · ")
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text(b.title, 14f, ui.strong))
            addView(ui.text(under, 12f, ui.body, ui.ink2))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        when (b.status) {
            Day.Status.SURE -> if (b.guessed) row.addView(pills(ui, "Fix" to { fixField(b) })) else row.addView(ui.text("✓", 11f, ui.strong, ui.good).apply {
                gravity = Gravity.CENTER; background = ui.rounded(ui.goodBg, 999)
            }, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
            Day.Status.MISSED -> row.addView(ui.text("missed", 12f, ui.body, ui.muted))
            Day.Status.GUESS -> row.addView(pills(ui,
                "Yes" to { Trail.answer(this, b.outing!!, b.key!!, b.guess ?: b.kind); Ask.settle(this); build() },
                "No" to { where(b) }, firstOn = true))
            Day.Status.UNKNOWN -> row.addView(pills(ui,
                (b.guess ?: Kind.ERRAND).label to { Trail.answer(this, b.outing!!, if (b.guess == Kind.FIELD) "all" else b.key!!, b.guess ?: Kind.ERRAND); Ask.settle(this); build() },
                "…" to { where(b) }))
        }
        return row
    }

    private fun pills(ui: Ui, vararg items: Pair<String, () -> Unit>, firstOn: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEachIndexed { i, (label, go) ->
            val on = firstOn && i == 0
            addView(ui.text(label, 12f, ui.strong, if (on) ui.page else ui.ink).apply {
                setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
                background = ui.rounded(if (on) ui.ink else ui.card, 999, if (on) ui.ink else ui.line, 1.2f)
                setOnClickListener { go() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(4) })
        }
    }

    /** Guessed field times: set when the first conversation was, then the last call. */
    private fun fixField(b: Day.Block) {
        val cal = { t: Long -> java.util.Calendar.getInstance().apply { timeInMillis = t } }
        val day = startOfDay(0)
        val s0 = cal(b.from); val e0 = cal(b.to)
        android.app.TimePickerDialog(this, { _, h1, m1 ->
            val start = day + (h1 * 60 + m1) * 60_000L
            android.app.TimePickerDialog(this, { _, h2, m2 ->
                val end = day + (h2 * 60 + m2) * 60_000L
                if (end > start) { Field.setTimes(this, day, start, end); Happened.write(this, day); build() }
            }, e0.get(java.util.Calendar.HOUR_OF_DAY), e0.get(java.util.Calendar.MINUTE), true).apply { setTitle("Last call") }.show()
        }, s0.get(java.util.Calendar.HOUR_OF_DAY), s0.get(java.util.Calendar.MINUTE), true).apply { setTitle("First conversation") }.show()
    }

    private fun where(b: Day.Block) {
        b.outing ?: return
        startActivity(Intent(this, WhereActivity::class.java).putExtra(WhereActivity.EXTRA_OUTING, b.outing))
    }
}
