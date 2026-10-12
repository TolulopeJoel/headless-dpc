package com.tolu.dpc

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Behind the ring widget: what's going on right now, until when, what's resting, and what's next today. */
class NowActivity : Activity() {

    private val colours = mapOf(
        "free" to 0xFF8E1B2C.toInt(), "field" to 0xFF2F5326.toInt(), "study" to 0xFF7A4012.toInt(),
        "work" to 0xFF1F4E79.toInt(), "bank" to 0xFF3D2A66.toInt(), "entry" to 0xFF8C6A44.toInt(),
        "morning" to 0xFF3F3A4A.toInt(), "night" to 0xFF0E1626.toInt(),
    )
    private val names = mapOf(
        "free" to "Free time", "field" to "Field service", "study" to "Study", "work" to "Work", "bank" to "Banks",
        "entry" to "Today's entry first", "morning" to "Bible hour", "night" to "Night · rest",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = ScheduleReceiver.currentState(this)
        val mode = ModeWallpaper.modeOf(this, state)
        val bg = colours[mode] ?: 0xFF17263F.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val display = resources.getFont(R.font.fraunces_black)
        val body = resources.getFont(R.font.work_sans)
        val strong = resources.getFont(R.font.work_sans_semibold)
        val hhmm = { t: Long -> SimpleDateFormat("HH:mm", Locale.UK).format(Date(t)) }
        val now = System.currentTimeMillis()
        val minute = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val todayAt = { m: Int -> startOfDay(0) + m * 60_000L }

        val until: String = when (mode) {
            "field" -> Field.endsAt(this)?.let { "till ${hhmm(it)}" }
            "study" -> Study.holding(this)?.let { "till ${hhmm(it.end)}" }
            "work" -> WorkTime.holding(this)?.let { "till ${hhmm(it.end)}" }
            "bank" -> BankTime.holding(this)?.let { "till ${hhmm(it.end)}" }
            "night" -> "till 05:00"
            "morning" -> "till 07:00"
            "entry" -> "till you've written today's entry"
            else -> null
        } ?: ""
        val resting = when (mode) {
            "field" -> "Fun apps rest and the hotspot is off, so the laptop has no internet. Chrome keeps to jw.org."
            "study" -> "Fun apps rest. Chrome keeps to jw.org. The hotspot stays on."
            "work", "bank" -> "Your phone is open. On the laptop, your side projects rest."
            "night" -> "Everything rests except calls, the clock and your tools. JW Library and Àṣàrò are open until 22:00."
            "morning" -> "Only JW Library, Àṣàrò, jw.org (and Google search) and Field Service are open."
            "entry" -> "Everything waits for two answers in today's Àṣàrò entry."
            else -> if (state.phase == ScheduleReceiver.Phase.NIGHT)
                "Free on the laptop until it goes offline. On your phone the night has begun: JW Library and Àṣàrò (until 22:00), calls and your tools."
            else "Nothing's on. Your side projects are open on the laptop."
        }
        val tethering = (getSystemService(USER_SERVICE) as UserManager).hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING)
        val hotspot = if (tethering) "Hotspot: off" else "Hotspot: allowed"

        // What's next today, from the four kinds (the typical week fills unplanned days).
        val ahead = buildList {
            Field.today(this@NowActivity).forEach { add(Triple(it.start, it.end, "Field service")) }
            Study.today(this@NowActivity).forEach { add(Triple(it.start, it.end, "Study")) }
            WorkTime.today(this@NowActivity).forEach { add(Triple(it.start, it.end, "Work")) }
            BankTime.today(this@NowActivity).forEach { add(Triple(it.start, it.end, "Banks")) }
            if (minute < ScheduleReceiver.deviceRulesFrom()) add(Triple(todayAt(ScheduleReceiver.deviceRulesFrom()), todayAt(24 * 60), "Night: the laptop goes offline"))
        }.filter { it.first > now }.sortedBy { it.first }.take(4)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(60), dp(28), dp(40)) }
        fun text(s: String, sp: Float, face: Typeface, alpha: Float = 1f, top: Int = 0) = TextView(this).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = face; setTextColor(0xFFFFFFFF.toInt())
            this.alpha = alpha; setPadding(0, dp(top), 0, 0); setLineSpacing(0f, 1.25f)
        }
        column.addView(text("NOW", 13f, strong, 0.75f).apply { letterSpacing = 0.15f })
        column.addView(text(names[mode] ?: mode, 34f, display, top = 6))
        if (until.isNotEmpty()) column.addView(text(until, 18f, strong, 0.9f, 4))
        column.addView(text(resting, 16f, body, 0.9f, 18))
        column.addView(text(hotspot + (if (Field.passedToday(this)) "  ·  Field pass taken today" else ""), 14f, strong, 0.75f, 14))
        column.addView(text("NEXT TODAY", 13f, strong, 0.75f, 30).apply { letterSpacing = 0.15f })
        column.addView(text(if (ahead.isEmpty()) "Nothing else today." else ahead.joinToString("\n") { "${hhmm(it.first)}  ${it.third}" }, 16f, body, top = 6))

        // The one useful button for the moment.
        val action: Pair<String, () -> Unit>? = when (mode) {
            "field" -> "Can't go out? Field pass" to { startActivity(Intent(this, FieldPassActivity::class.java)) }
            "entry" -> "Write today's entry" to { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("asaro://addEntry")).setPackage("com.asaro.meditation").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "morning", "study" -> "Open JW Library" to { packageManager.getLaunchIntentForPackage("org.jw.jwlibrary.mobile")?.let { startActivity(it) }; Unit }
            else -> null
        }
        action?.let { (label, go) ->
            column.addView(TextView(this).apply {
                text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = strong; setTextColor(bg); gravity = Gravity.CENTER
                setPadding(dp(20), dp(15), dp(20), dp(15))
                background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(0xFFFFFFFF.toInt()) }
                setOnClickListener { go(); finish() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(34) })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(column) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
