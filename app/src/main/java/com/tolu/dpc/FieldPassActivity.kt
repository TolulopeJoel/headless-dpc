package com.tolu.dpc

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The field pass: for a morning he can't go out because of work. It opens the hotspot for the rest of today's field
 * sessions, after two hours of public witnessing (06:30–10:00), or once he's been out and work has pulled him back.
 * The reason goes in his own words and stays in the week's list for the Sunday numbers.
 */
class FieldPassActivity : Activity() {

    private var kind: String? = null

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val ui = Ui(this)
        val green = Kind.FIELD.colour
        // A preview (adb, look only) shows the choices as on a field morning with none taken; nothing can be taken.
        val preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        val (witnessOpen, pulledOpen) = if (preview) false to true else Field.passesOpen(this)
        val ends = if (preview) "14:00" else Field.endsAt(this)?.let { NowActivity.hhmm(it) }
        val emergencyOpen = preview || Field.emergencyOpen(this)
        if (kind == null) kind = if (pulledOpen) "pulled back" else if (witnessOpen) "witnessing" else if (emergencyOpen) "emergency" else null

        val content = ui.column()
        content.addView(ui.kick("Field pass", green))
        content.addView(ui.big("Can't go out this morning?"))
        val footer = ui.footer()
        when {
            Field.passedToday(this) && !preview -> content.addView(ui.sub("Today's pass is taken. The hotspot's back for the rest of today's field time.", 8))
            ends == null -> content.addView(ui.sub("There's no field session left today.", 8))
            else -> {
                content.addView(ui.sub("The hotspot comes back till $ends."))
                // The two ways in, each with what it needs.
                val away = Away.minutesAwayThisMorning(this)
                content.addView(option(ui, "witnessing", "2 h public witnessing", witnessOpen,
                    ui.bar(away / Away.NEEDED_MINUTES.toFloat(), green, 5, 8),
                    ui.ends("away ${away / 60} h ${away % 60} m", "of 2 h · 06:30–10:00")))
                content.addView(option(ui, "pulled back", "Went out, work pulled me back", pulledOpen,
                    ui.ends(if (pulledOpen) "you've been out" else "needs time away from home", "since 30 m before the session")))
                content.addView(option(ui, "emergency", "Emergency", emergencyOpen,
                    ui.ends(if (emergencyOpen) "once a week · no walk needed" else "used this week", "goes on the honest list")))
                val reason = ui.input("What's the emergency?")
                content.addView(reason)
                val take = ui.button("Take the pass", green, 0xFFFFFFFF.toInt()) {
                    val why = reason.text.toString().trim()
                    val open = when (kind) { "witnessing" -> witnessOpen; "emergency" -> emergencyOpen; else -> pulledOpen }
                    if (kind == null || !open || preview) return@button
                    if (why.length < 8) { reason.error = "Say what it is, in a few words"; return@button }
                    Field.takePass(this, kind!!, why)
                    ScheduleReceiver.enforce(this)
                    Toast.makeText(this, "Pass taken. The hotspot's back.", Toast.LENGTH_LONG).show()
                    finish()
                }
                val sync = { take.alpha = if (kind != null && reason.text.trim().length >= 8) 1f else 0.45f }
                reason.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) = sync()
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                sync()
                val week = Field.passesThisWeek(this)
                footer.addView(ui.text("This week  ${week.size}  pass${if (week.size == 1) "" else "es"}", 12.5f, ui.body, ui.ink2).apply {
                    gravity = android.view.Gravity.CENTER; setPadding(0, 0, 0, ui.dp(4))
                })
                footer.addView(take)
            }
        }
        // This week's list, as it'll read on Sunday.
        val week = Field.passesThisWeek(this)
        if (week.isNotEmpty()) {
            content.addView(ui.kick("This week", top = 22))
            week.forEach { (at, k, why) ->
                content.addView(ui.text("${SimpleDateFormat("EEE HH:mm", Locale.UK).format(Date(at))} · ${when (k) { "witnessing" -> "2 h witnessing"; "emergency" -> "emergency"; else -> "pulled back" }} · $why", 13.5f, ui.body, ui.ink, 6))
            }
        }
        setContentView(ui.page(content, footer))
    }

    /** One way in: a card, outlined in green when chosen, faded when not met yet. */
    private fun option(ui: Ui, key: String, title: String, open: Boolean, vararg detail: View) = ui.card(
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.text(title, 14f, ui.strong), LinearLayout.LayoutParams(0, -2, 1f))
            if (open) addView(ui.text("✓", 11f, ui.strong, ui.good).apply {
                gravity = android.view.Gravity.CENTER; background = ui.rounded(ui.goodBg, 999)
            }, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
        }, *detail, pad = 13,
    ).apply {
        if (open && kind == key) background = ui.rounded(ui.card, 16, Kind.FIELD.colour, 1.5f)
        alpha = if (open) 1f else 0.55f
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) }
        if (open) setOnClickListener { kind = key; build() }
    }

    companion object { const val EXTRA_PREVIEW = "preview" }
}
