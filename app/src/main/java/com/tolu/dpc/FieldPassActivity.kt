package com.tolu.dpc

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    private lateinit var reason: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mood = Mood.FIELD
        window.statusBarColor = mood.top
        window.navigationBarColor = mood.bottom
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val display = resources.getFont(R.font.fraunces_black)
        val body = resources.getFont(R.font.work_sans)
        val strong = resources.getFont(R.font.work_sans_semibold)
        val (witnessOpen, pulledOpen) = Field.passesOpen(this)
        val ends = Field.endsAt(this)?.let { SimpleDateFormat("HH:mm", Locale.UK).format(Date(it)) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(56), dp(28), dp(40))
        }
        column.addView(text("FIELD PASS", 13f, mood.accent, strong).apply { letterSpacing = 0.12f })
        column.addView(text("Can't go out this morning?", 30f, mood.ink, display).apply { setPadding(0, dp(10), 0, 0) })

        when {
            Field.passedToday(this) -> column.addView(text("You've taken today's pass. The hotspot's back for the rest of today's field time.", 16f, mood.soft, body).pad())
            ends == null -> column.addView(text("There's no field session left today.", 16f, mood.soft, body).pad())
            else -> {
                column.addView(text(
                    "The hotspot comes back for the rest of today's field time (until $ends). " +
                        "First, the two hours: public witnessing between 06:30 and 10:00, shown by your phone being away from home. " +
                        "Or, if you went out and work pulled you back, say so.",
                    16f, mood.soft, body).pad())

                reason = EditText(this).apply {
                    hint = "What's the emergency? In your own words"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(mood.ink)
                    setHintTextColor(mood.soft)
                    typeface = body
                    minLines = 2
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setStroke(dp(2), mood.soft) }
                }
                column.addView(reason, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })

                val witness = pill("I did 2 hours of public witnessing", strong, mood, filled = true, enabled = witnessOpen) { take("witnessing") }
                val pulled = pill("I went out, and work pulled me back", strong, mood, filled = false, enabled = pulledOpen) { take("pulled back") }
                column.addView(witness, full().apply { topMargin = dp(22) })
                if (!witnessOpen) {
                    val away = Away.minutesAwayThisMorning(this)
                    val note = if (!Away.knowsHome(this)) "Your phone doesn't know where home is yet. It learns it during the Bible hour."
                        else "Away from home this morning: ${away / 60}h ${away % 60}m of 2h (06:30–10:00)."
                    column.addView(text(note, 13f, mood.soft, body).apply { setPadding(0, dp(6), 0, 0) })
                }
                column.addView(pulled, full().apply { topMargin = dp(12) })
                if (!pulledOpen) column.addView(text("Opens once the field session has begun and your phone has been away from home during it.",
                    13f, mood.soft, body).apply { setPadding(0, dp(6), 0, 0) })

                // Both wait for a reason.
                val sync = { val ok = reason.text.trim().length >= 8; witness.alpha = if (witnessOpen && ok) 1f else 0.4f; pulled.alpha = if (pulledOpen && ok) 1f else 0.4f }
                reason.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) = sync()
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                sync()
            }
        }

        // This week, as it'll read on Sunday.
        val week = Field.passesThisWeek(this)
        column.addView(text("THIS WEEK", 13f, mood.accent, strong).apply { letterSpacing = 0.12f; setPadding(0, dp(36), 0, 0) })
        column.addView(text(
            if (week.isEmpty()) "No passes." else week.joinToString("\n") { (at, kind, why) ->
                "${SimpleDateFormat("EEE HH:mm", Locale.UK).format(Date(at))} · ${if (kind == "witnessing") "2 h public witnessing" else "went out, pulled back"} · $why"
            }, 15f, mood.ink, body).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.3f) })

        setContentView(ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(mood.top, mood.bottom))
            addView(column)
        })
    }

    private fun take(kind: String) {
        val (witnessOpen, pulledOpen) = Field.passesOpen(this)
        val open = if (kind == "witnessing") witnessOpen else pulledOpen
        val why = reason.text.toString().trim()
        if (!open) return
        if (why.length < 8) {
            reason.error = "Say what it is, in a few words"
            return
        }
        Field.takePass(this, kind, why)
        ScheduleReceiver.enforce(this)
        Toast.makeText(this, "Pass taken. The hotspot's back.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun pill(label: String, face: Typeface, mood: Mood, filled: Boolean, enabled: Boolean, onClick: () -> Unit) =
        text(label, 16f, if (filled) mood.onAccent else mood.ink, face).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(15), dp(20), dp(15))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                if (filled) setColor(mood.accent) else setStroke(dp(2), mood.ink)
            }
            alpha = if (enabled) 1f else 0.4f
            setOnClickListener { onClick() }
        }

    private fun TextView.pad() = apply { setPadding(0, dp(12), 0, 0); setLineSpacing(0f, 1.3f) }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun text(value: String, sp: Float, color: Int, face: Typeface) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = face
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
