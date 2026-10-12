package com.tolu.dpc

import android.app.Activity
import android.app.TimePickerDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import java.util.Calendar

/**
 * Personal study and meeting prep. While one runs: the time so far and how much of it JW Library was open. Stopping
 * asks the one thing a machine can't know: the topic (study) or which meeting (prep). With EXTRA_ADD it's "Add a
 * session" instead, for one he forgot to start; that's marked as added later.
 */
class SessionActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var stopping = false

    override fun onResume() {
        super.onResume()
        if (intent.getBooleanExtra(EXTRA_ADD, false)) add()
        else if (Logbook.running(this) == null) finish()
        else if (stopping || intent.getBooleanExtra(EXTRA_STOP, false)) { stopping = true; stopForm() } else running()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    // ── Running ───────────────────────────────────────────────────────────────

    private fun running() {
        val (kind, since) = Logbook.running(this) ?: return finish()
        val ui = Ui(this)
        val content = ui.column()
        content.addView(ui.kick(if (kind == Kind.STUDY) "Studying" else "Meeting prep", kind.colour))
        val clock = ui.text("", 64f, ui.display, ui.ink, 40).apply { gravity = android.view.Gravity.CENTER }
        content.addView(clock)
        content.addView(ui.sub("since ${NowActivity.hhmm(since)}").apply { gravity = android.view.Gravity.CENTER })
        val ring = JwRing(this, ui, kind.colour)
        content.addView(ring, LinearLayout.LayoutParams(ui.dp(190), ui.dp(190)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(28) })
        content.addView(ui.sub("JW Library time, from the phone. Time on wol.jw.org on the laptop counts too.", 14).apply { gravity = android.view.Gravity.CENTER })
        val tick = object : Runnable {
            override fun run() {
                clock.text = NowActivity.left(System.currentTimeMillis() - since, suffix = false)
                val total = maxOf(1L, (System.currentTimeMillis() - since) / 60_000L)
                val jw = Usage.minutes(this@SessionActivity, "org.jw.jwlibrary.mobile", since, System.currentTimeMillis())
                ring.show(jw, jw / total.toFloat())
                handler.postDelayed(this, 20_000)
            }
        }
        tick.run()
        val footer = ui.footer()
        footer.addView(ui.button("Stop") { stopping = true; handler.removeCallbacksAndMessages(null); stopForm() })
        footer.addView(ui.link("Discard this session") { Logbook.cancel(this); finish() })
        setContentView(ui.page(content, footer))
    }

    // ── Stopping ──────────────────────────────────────────────────────────────

    private fun stopForm() {
        val (kind, since) = Logbook.running(this) ?: return finish()
        val ui = Ui(this)
        val content = ui.column()
        val took = NowActivity.left(System.currentTimeMillis() - since, suffix = false).let { val (h, m) = it.split(":"); if (h == "0") "${m.toInt()} m" else "$h h $m" }
        content.addView(ui.kick("${kind.label} · $took"))
        val footer = ui.footer()
        if (kind == Kind.STUDY) {
            content.addView(ui.big("What did you study?"))
            val topic = ui.input("A few words")
            content.addView(topic)
            topicChips(ui, content, topic)
            footer.addView(ui.button("Done") {
                if (topic.text.trim().length < 2) { topic.error = "A few words"; return@button }
                Logbook.stop(this, topic.text.toString()); finish()
            })
        } else {
            content.addView(ui.big("Which meeting?"))
            var meeting = Logbook.nextMeeting()
            val holder = LinearLayout(this)
            fun draw() {
                holder.removeAllViews()
                holder.addView(ui.chips(*Logbook.Meeting.entries.map { m ->
                    ui.chip("${m.label} · ${if (m == Logbook.Meeting.MIDWEEK) "Thu" else "Sun"}", on = m == meeting) { meeting = m; draw() }
                }.toTypedArray()))
            }
            draw()
            content.addView(holder)
            footer.addView(ui.button("Done") { Logbook.stop(this, meeting.label); finish() })
        }
        footer.addView(ui.link("Keep going") { stopping = false; running() })
        setContentView(ui.page(content, footer))
    }

    private fun topicChips(ui: Ui, content: LinearLayout, topic: EditText) {
        val recent = Logbook.recentTopics(this)
        if (recent.isNotEmpty()) content.addView(ui.chips(*recent.map { t -> ui.chip(t) { topic.setText(t); topic.setSelection(t.length) } }.toTypedArray()))
    }

    // ── Adding one he forgot ──────────────────────────────────────────────────

    private var addKind = Kind.STUDY
    private var addFrom = -1
    private var addTo = -1
    private var addMeeting = Logbook.nextMeeting()

    private fun add() {
        val ui = Ui(this)
        val content = ui.column()
        content.addView(ui.kick("Forgot to tap"))
        content.addView(ui.big("Add a session"))
        content.addView(ui.chips(
            ui.chip("Study", Kind.STUDY.colour, addKind == Kind.STUDY) { addKind = Kind.STUDY; add() },
            ui.chip("Meeting prep", Kind.PREP.colour, addKind == Kind.PREP) { addKind = Kind.PREP; add() },
        ))
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        if (addTo < 0) { addTo = now; addFrom = maxOf(0, now - 45) }
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        times.addView(timeBox(ui, "From", addFrom) { addFrom = it; add() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
        times.addView(timeBox(ui, "To", addTo) { addTo = it; add() }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14) })
        var topic: EditText? = null
        if (addKind == Kind.STUDY) {
            topic = ui.input("Topic")
            content.addView(topic)
            topicChips(ui, content, topic)
        } else {
            content.addView(ui.chips(*Logbook.Meeting.entries.map { m ->
                ui.chip("${m.label} · ${if (m == Logbook.Meeting.MIDWEEK) "Thu" else "Sun"}", on = m == addMeeting) { addMeeting = m; add() }
            }.toTypedArray()))
        }
        content.addView(ui.sub("Marked as added later.", 12))
        val footer = ui.footer()
        footer.addView(ui.button("Add") {
            val t = topic?.text?.toString()?.trim() ?: addMeeting.label
            if (addKind == Kind.STUDY && t.length < 2) { topic?.error = "A few words"; return@button }
            if (addTo <= addFrom) return@button
            val day = startOfDay(0)
            Logbook.add(this, Logbook.Entry(addKind, day + addFrom * 60_000L, day + addTo * 60_000L, t, late = true))
            finish()
        })
        setContentView(ui.page(content, footer))
    }

    private fun timeBox(ui: Ui, label: String, minute: Int, set: (Int) -> Unit) = ui.card(
        ui.kick(label), ui.text("%02d:%02d".format(minute / 60, minute % 60), 22f, ui.display, ui.ink, 2), pad = 12,
    ).apply {
        setOnClickListener { TimePickerDialog(this@SessionActivity, { _, h, m -> set(h * 60 + m) }, minute / 60, minute % 60, true).show() }
    }

    override fun onStop() {
        super.onStop()
        NowBar.refresh(this)
    }

    companion object {
        const val EXTRA_ADD = "add"
        /** From the bar's Stop: straight to the stop question. */
        const val EXTRA_STOP = "stop"
    }
}

/** The ring on the running session: how much of it JW Library has been open, with the minutes in the middle. */
private class JwRing(context: android.content.Context, private val ui: Ui, private val colour: Int) : android.view.View(context) {
    private var minutes = 0
    private var fraction = 0f
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = ui.dpf(10f); color = ui.line }
    private val arc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = ui.dpf(10f); strokeCap = android.graphics.Paint.Cap.ROUND; color = colour }
    private val small = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.body; textSize = ui.dpf(12f); color = ui.muted; textAlign = android.graphics.Paint.Align.CENTER }
    private val big = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.strong; textSize = ui.dpf(26f); color = ui.ink; textAlign = android.graphics.Paint.Align.CENTER }

    fun show(minutes: Int, fraction: Float) { this.minutes = minutes; this.fraction = fraction.coerceIn(0f, 1f); invalidate() }

    override fun onDraw(c: android.graphics.Canvas) {
        val inset = ui.dpf(8f)
        val box = android.graphics.RectF(inset, inset, width - inset, height - inset)
        c.drawOval(box, track)
        if (fraction > 0f) c.drawArc(box, -90f, maxOf(4f, 360f * fraction), false, arc)
        c.drawText("JW Library", width / 2f, height / 2f - ui.dpf(8f), small)
        c.drawText("$minutes m", width / 2f, height / 2f + ui.dpf(20f), big)
    }
}
