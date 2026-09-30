package com.tolu.dpc

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import kotlin.random.Random

/**
 * The screen a locked app shows instead of Android's dialog. It takes its mood from the part of the day:
 * stars at night, dawn from 05:00, dusk in the evening, Àṣàrò's paper while today's entry is still unwritten.
 * The ring fills as the lock runs and counts down to when things open.
 */
class BlockedActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ring: RingView
    private lateinit var mood: Mood

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = ScheduleReceiver.currentState(this)
        mood = Mood.of(state)
        window.statusBarColor = mood.top
        window.navigationBarColor = mood.bottom
        if (mood.lightBackground) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val display = resources.getFont(R.font.fraunces_black)
        val body = resources.getFont(R.font.work_sans)
        val strong = resources.getFont(R.font.work_sans_semibold)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), 0, dp(32), 0)
        }

        val eyebrow = text(mood.eyebrow, 12f, mood.soft, strong).apply { letterSpacing = 0.32f }
        ring = RingView(this, mood, display, strong)
        val title = text("Not now, Tolu.", 40f, mood.ink, display).apply { setPadding(0, dp(28), 0, 0) }
        val message = text(ScheduleReceiver.blockedMessage(state), 17f, mood.soft, body).apply {
            setPadding(0, dp(12), 0, 0)
            setLineSpacing(0f, 1.25f)
        }
        val verse = text("“${mood.verse}”", 15f, mood.soft, body).apply {
            setPadding(0, dp(36), 0, 0)
            setLineSpacing(0f, 1.3f)
        }
        val reference = text(mood.reference.uppercase(), 11f, mood.accent, strong).apply {
            letterSpacing = 0.24f
            setPadding(0, dp(8), 0, 0)
        }
        val home = text("Go home", 15f, mood.ink, strong).apply {
            setPadding(dp(36), dp(14), dp(36), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(2), mood.ink)
            }
            setOnClickListener { goHome() }
        }

        column.addView(eyebrow)
        column.addView(ring, LinearLayout.LayoutParams(dp(236), dp(236)).apply { topMargin = dp(28) })
        listOf(title, message, verse, reference).forEach { column.addView(it) }
        column.addView(home, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(40) })

        val root = FrameLayout(this)
        root.addView(SkyView(this, mood))
        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)

        // Everything eases up into place, one after another.
        listOf(eyebrow, ring, title, message, verse, reference, home).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dp(18).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(90L * i).setDuration(620).setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    override fun onResume() {
        super.onResume()
        tick()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    /** The countdown moves on its own while the screen is open. */
    private fun tick() {
        ring.refresh()
        handler.postDelayed({ tick() }, 20_000)
    }

    @Deprecated("Back goes home, never to the locked app.")
    override fun onBackPressed() = goHome()

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun text(value: String, sp: Float, color: Int, face: Typeface) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = face
        gravity = Gravity.CENTER
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** One look per part of the day. `start`/`end` are minutes after midnight; null means there's no countdown. */
private enum class Mood(
    val eyebrow: String,
    val top: Int, val bottom: Int,
    val ink: Int, val soft: Int, val accent: Int, val track: Int,
    val stars: Boolean, val lightBackground: Boolean,
    val start: Int?, val end: Int?, val until: String,
    val verse: String, val reference: String,
) {
    NIGHT(
        "NIGHT", 0xFF070D19.toInt(), 0xFF17263F.toInt(),
        0xFFEFE6D8.toInt(), 0xB8EFE6D8.toInt(), 0xFFE8D6A0.toInt(), 0x1FFFFFFF,
        stars = true, lightBackground = false,
        start = 21 * 60, end = 5 * 60, until = "until 5AM",
        verse = "For he provides sleep for those he loves.", reference = "Psalm 127:2",
    ),
    MORNING(
        "BIBLE HOUR", 0xFF17263F.toInt(), 0xFFE18F43.toInt(),
        0xFFFFFFFF.toInt(), 0xD9FFFFFF.toInt(), 0xFFFFE2B8.toInt(), 0x33FFFFFF,
        stars = false, lightBackground = false,
        start = 5 * 60, end = 7 * 60, until = "until 7AM",
        verse = "Your word is a lamp to my foot, and a light for my path.", reference = "Psalm 119:105",
    ),
    EVENING(
        "EVENING", 0xFF17263F.toInt(), 0xFF6E3448.toInt(),
        0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0xFFE18F43.toInt(), 0x26FFFFFF,
        stars = false, lightBackground = false,
        start = 17 * 60 + 30, end = 7 * 60, until = "until 7AM",
        verse = "For everything there is an appointed time.", reference = "Ecclesiastes 3:1",
    ),
    ENTRY(
        "BEFORE ANYTHING", 0xFFF4ECDF.toInt(), 0xFFE6D6BE.toInt(),
        0xFF17263F.toInt(), 0xB317263F.toInt(), 0xFFC76A24.toInt(), 0x2217263F,
        stars = false, lightBackground = true,
        start = null, end = null, until = "then it opens",
        verse = "Man must live, not on bread alone, but on every word coming from Jehovah’s mouth.", reference = "Matthew 4:4",
    );

    companion object {
        fun of(state: ScheduleReceiver.Companion.State): Mood = when {
            state.gated -> ENTRY
            state.phase == ScheduleReceiver.Phase.MORNING -> MORNING
            state.phase == ScheduleReceiver.Phase.EVENING -> EVENING
            else -> NIGHT
        }
    }
}

/** The background: the mood's gradient, and a fixed scatter of stars at night. */
private class SkyView(context: Context, private val mood: Mood) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val stars = List(90) { Triple(Random(it * 31 + 7).nextFloat(), Random(it * 17 + 3).nextFloat(), Random(it * 13 + 1).nextFloat()) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), mood.top, mood.bottom, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        if (!mood.stars) return
        val d = resources.displayMetrics.density
        stars.forEach { (x, y, s) ->
            // The sky above the words and along the edges only, so no star ever sits on a letter.
            val clear = y < 0.15f || ((x < 0.09f || x > 0.91f) && y < 0.8f)
            if (!clear) return@forEach
            star.alpha = (60 + 195 * s * (1 - y)).toInt().coerceIn(30, 255)
            canvas.drawCircle(x * width, y * height, (0.6f + 1.4f * s) * d, star)
        }
    }
}

/** The ring: fills with the share of the lock that has passed, breathes gently, and counts down in the middle. */
private class RingView(context: Context, private val mood: Mood, display: Typeface, strong: Typeface) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10 * d; color = mood.track }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10 * d; strokeCap = Paint.Cap.ROUND; color = mood.accent }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 26 * d; strokeCap = Paint.Cap.ROUND; color = mood.accent }
    private val baseSize = 44 * d * resources.configuration.fontScale
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = display; textSize = baseSize; color = mood.ink; textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = strong; textSize = 13 * d * resources.configuration.fontScale; color = mood.soft; textAlign = Paint.Align.CENTER; letterSpacing = 0.12f }
    private val box = RectF()

    private var shown = 0f      // animated share of the ring
    private var breath = 0f     // 0..1, the glow's pulse
    private var target = 0f
    private var centre = ""

    init {
        refresh()
        ValueAnimator.ofFloat(0f, target).apply {
            duration = 1400
            startDelay = 250
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { breath = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Work out how far through the lock we are and what the centre says. */
    fun refresh() {
        val start = mood.start
        val end = mood.end
        if (start == null || end == null) {
            target = 1f
            centre = "✎"
        } else {
            val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
            val total = (end - start + 1440) % 1440
            val passed = ((now - start + 1440) % 1440).coerceAtMost(total)
            val left = total - passed
            target = passed / total.toFloat()
            centre = if (left >= 60) "${left / 60}h ${left % 60}m" else "${left}m"
        }
        if (shown > 0f) shown = target
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = 16 * d
        box.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(box, track)
        val sweep = 360f * shown.coerceIn(0.02f, 1f)
        glow.alpha = (18 + 30 * breath).toInt()
        canvas.drawArc(box, -90f, sweep, false, glow)
        canvas.drawArc(box, -90f, sweep, false, arc)
        val cy = height / 2f
        // The countdown shrinks to sit inside the ring with room to spare ("10h 16m" is wide).
        val room = (box.width() - 2 * 26 * d)
        big.textSize = baseSize
        val wide = big.measureText(centre)
        if (wide > room) big.textSize = baseSize * room / wide
        canvas.drawText(centre, width / 2f, cy + big.textSize * 0.2f, big)
        canvas.drawText(mood.until.uppercase(), width / 2f, cy + big.textSize * 0.2f + 26 * d, small)
    }
}
