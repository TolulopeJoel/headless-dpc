package com.tolu.dpc

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The screen a locked app shows instead of Android's dialog. It takes its mood from the part of the day, draws a sky
 * that follows the real clock (stars and a moon at night, a sun that rises from 05:00 and sinks in the evening,
 * Àṣàrò's ruled paper while today's entry is unwritten), and offers the thing worth doing instead.
 */
class BlockedActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ring: RingView
    private lateinit var mood: Mood
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var displayFace: Typeface
    private var minuteOverride = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preview = ScheduleReceiver.previewMood()
        val real = ScheduleReceiver.currentState(this)
        // A preview (adb, look only) shows another mood at a pretend time; it never touches what is locked.
        mood = preview?.let { name -> Mood.entries.firstOrNull { it.name == name } } ?: Mood.of(real)
        val previewing = preview != null && Mood.entries.any { it.name == preview }   // "DAY" previews only the widget
        val state = if (previewing) mood.asState() else real
        if (previewing) minuteOverride = ScheduleReceiver.previewMinute
        else if (real.test) minuteOverride = ScheduleReceiver.testMinute
        val (skyTop, skyBottom) = mood.sky(minute())
        window.statusBarColor = skyTop
        window.navigationBarColor = skyBottom
        if (mood.lightBackground) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        val display = resources.getFont(R.font.fraunces_black)
        displayFace = display
        val body = resources.getFont(R.font.work_sans)
        val strong = resources.getFont(R.font.work_sans_semibold)

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), 0, dp(32), dp(40))
        }

        // One of each: the ring says when, the title says no, Àṣàrò says why, one button says what instead.
        ring = RingView(this, mood, display, strong, ringLabel(previewing)) { minute() }
        val title = text("Not now, Tolu.", 36f, mood.ink, display).apply { setPadding(0, dp(30), 0, 0) }

        // Under the title, the verse for this part of the day (the ring already says until when).
        val verse = TextView(this).apply {
            val words = "“${mood.verse}”"
            val ref = "  ${mood.reference}"
            text = android.text.SpannableStringBuilder(words + ref).apply {
                // Where the sun sits behind the words (dawn and dusk), the reference is white, not a warm colour lost in the glow.
                val refColour = if (mood == Mood.MORNING || mood == Mood.EVENING) 0xFFFFFFFF.toInt() else mood.accent
                setSpan(android.text.style.ForegroundColorSpan(refColour), words.length, length, 0)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(mood.soft)
            typeface = body
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(14), 0, 0)
            maxWidth = dp(300)
            // A soft shadow keeps it readable over any sky, without a box.
            if (!mood.lightBackground) setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0x80000000.toInt())
        }

        // One button for the good thing; everything else is a quiet link.
        // 20:00–22:00 the night has begun but the Bible is still open, so that's the thing to offer.
        val bibleEvening = mood == Mood.NIGHT && !previewing && ScheduleReceiver.bibleEvening()
        val first = if (bibleEvening) Action.JW_LIBRARY else mood.primary
        val second = if (bibleEvening) Action.GOODNIGHT else mood.secondary
        val primary = pill(first.label, filled = true, strong) { run(first) }
        val links = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOfNotNull(second, Action.HOME.takeIf { first != Action.HOME }).forEachIndexed { i, action ->
            if (i > 0) links.addView(text("·", 14f, mood.soft, strong).apply { setPadding(dp(6), 0, dp(6), 0) })
            links.addView(text(action.label, 14f, mood.soft, strong).apply {
                setPadding(dp(10), dp(12), dp(10), dp(12))
                setOnClickListener { run(action) }
            })
        }

        column.addView(ring, LinearLayout.LayoutParams(dp(200), dp(200)))
        column.addView(title)
        column.addView(verse, wrap())
        column.addView(primary, wrap().apply { topMargin = dp(36) })
        if (links.childCount > 0) column.addView(links, wrap().apply { topMargin = dp(6) })

        root = FrameLayout(this)
        root.addView(SkyView(this, mood) { minute() })
        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)

        // Everything eases up into place, one after another, with a soft tap to say "I stopped you".
        val order = listOfNotNull(ring, title, verse, primary, links.takeIf { it.childCount > 0 })
        order.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dp(16).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(90L * i).setDuration(620).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        root.post { root.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }

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

    /** What the ring says under its countdown. At low tide it names the app that's out. */
    private fun ringLabel(previewing: Boolean): String {
        if (mood != Mood.TIDE) return mood.until
        val names = if (previewing) "Chrome" else ColourKeeper.tidedNames(this)
        return if (names.contains(" and ")) "until they're back" else "until $names is back"
    }

    /** Minutes after midnight: the real clock, or the pretend one during an adb test. */
    private fun minute(): Int {
        if (minuteOverride >= 0) return minuteOverride
        val now = Calendar.getInstance()
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    }

    @Deprecated("Back goes home, never to the locked app.")
    override fun onBackPressed() = run(Action.HOME)

    private fun run(action: Action) {
        when (action) {
            Action.HOME -> goHome()
            Action.GOODNIGHT -> {
                goodnight()
                return
            }
            Action.ALARM -> launch(Intent(AlarmClock.ACTION_SHOW_ALARMS), "com.transsion.deskclock")
            Action.JW_LIBRARY -> launch(null, "org.jw.jwlibrary.mobile")
            Action.WRITE -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("asaro://addEntry")).setPackage(ASARO), ASARO)
            Action.CALL -> launch(Intent(Intent.ACTION_DIAL), "com.sh.smart.caller")
            Action.ASARO -> launch(null, ASARO)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    /** The words fade, "Goodnight, Tolu." rises for a beat, then home (so tomorrow wakes to it) and the screen locks. */
    private fun goodnight() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        column.isEnabled = false
        // Everything but the sky fades.
        for (i in 1 until root.childCount) root.getChildAt(i).animate().alpha(0f).setDuration(380).start()
        val words = text("Goodnight, Tolu.", 34f, mood.ink, displayFace).apply {
            alpha = 0f
            translationY = dp(14).toFloat()
        }
        root.addView(words, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        words.animate().alpha(1f).translationY(0f).setStartDelay(260).setDuration(700).setInterpolator(DecelerateInterpolator(2f)).start()
        root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        handler.postDelayed({
            goHome()
            // The accessibility service locks like the power button; the device-owner lock is the fallback.
            if (!BlockScreenService.lockScreen()) runCatching { dpm.lockNow() }
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }, 1900)
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Try the specific intent, then the app's own launcher entry. */
    private fun launch(intent: Intent?, pkg: String) {
        val tries = listOfNotNull(intent, packageManager.getLaunchIntentForPackage(pkg))
        for (i in tries) {
            if (runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    private fun pill(label: String, filled: Boolean, face: Typeface, onClick: () -> Unit) =
        text(label, 16f, if (filled) mood.onAccent else mood.ink, face).apply {
            setPadding(dp(34), dp(15), dp(34), dp(15))
            minWidth = dp(220)
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                if (filled) setColor(mood.accent) else setStroke(dp(2), mood.ink)
            }
            pressable(this)
            setOnClickListener { onClick() }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun pressable(v: View) = v.setOnTouchListener { view, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        false
    }

    private fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun text(value: String, sp: Float, color: Int, face: Typeface) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = face
        gravity = Gravity.CENTER
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ASARO = "com.asaro.meditation"
    }
}

/** What a button does. */
internal enum class Action(val label: String) {
    HOME("Go home"),
    GOODNIGHT("Goodnight"),
    ALARM("Set an alarm"),
    JW_LIBRARY("Open JW Library"),
    WRITE("Write today's entry"),
    CALL("Call someone"),
    ASARO("Open Àṣàrò"),
}

/** One look per part of the day. `start`/`end` are minutes after midnight; null means there's no countdown. */
internal enum class Mood(
    val eyebrow: String,
    val top: Int, val bottom: Int,
    val ink: Int, val soft: Int, val accent: Int, val onAccent: Int, val track: Int,
    val lightBackground: Boolean,
    val start: Int?, val end: Int?, val until: String,
    val verse: String, val reference: String,
    val primary: Action, val secondary: Action?,
) {
    NIGHT(
        "NIGHT", 0xFF050A14.toInt(), 0xFF17263F.toInt(),
        0xFFEFE6D8.toInt(), 0xB8EFE6D8.toInt(), 0xFFE8D6A0.toInt(), 0xFF17263F.toInt(), 0x1FFFFFFF,
        lightBackground = false,
        start = 20 * 60, end = 5 * 60, until = "until 5AM",
        verse = "For he provides sleep for those he loves.", reference = "Psalm 127:2",
        primary = Action.GOODNIGHT, secondary = Action.ALARM,
    ),
    MORNING(
        "BIBLE HOUR", 0xFF17263F.toInt(), 0xFFE18F43.toInt(),
        0xFFFFFFFF.toInt(), 0xD9FFFFFF.toInt(), 0xFFFFE2B8.toInt(), 0xFF17263F.toInt(), 0x33FFFFFF,
        lightBackground = false,
        start = 5 * 60, end = 7 * 60, until = "until 7AM",
        verse = "Your word is a lamp to my foot, and a light for my path.", reference = "Psalm 119:105",
        primary = Action.JW_LIBRARY, secondary = Action.WRITE,
    ),
    EVENING(
        "EVENING", 0xFF17263F.toInt(), 0xFF6E3448.toInt(),
        0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0xFFE18F43.toInt(), 0xFF17263F.toInt(), 0x26FFFFFF,
        lightBackground = false,
        start = 17 * 60 + 30, end = 7 * 60, until = "until 7AM",
        verse = "For everything there is an appointed time.", reference = "Ecclesiastes 3:1",
        primary = Action.HOME, secondary = null,
    ),
    ENTRY(
        "BEFORE ANYTHING", 0xFFF6EFE3.toInt(), 0xFFE9DBC5.toInt(),
        0xFF17263F.toInt(), 0xB317263F.toInt(), 0xFFC76A24.toInt(), 0xFFFFF8EE.toInt(), 0x1A17263F,
        lightBackground = true,
        start = null, end = null, until = "then it opens",
        verse = "Man must live, not on bread alone, but on every word coming from Jehovah’s mouth.", reference = "Matthew 4:4",
        primary = Action.WRITE, secondary = Action.JW_LIBRARY,
    ),
    /** The tide is out: the drift apps are locked for a while after a long unbroken session. */
    TIDE(
        "LOW TIDE", 0xFF051C24.toInt(), 0xFF0E4652.toInt(),
        0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0xFF8FE3D6.toInt(), 0xFF051C24.toInt(), 0x26FFFFFF,
        lightBackground = false,
        start = null, end = null, until = "till the tide's back",
        verse = "Then your peace would become just like a river, and your righteousness like the waves of the sea.", reference = "Isaiah 48:18",
        primary = Action.JW_LIBRARY, secondary = Action.CALL,
    ),
    /** An app parked until a date: the Àṣàrò dev build, until 15 Nov. */
    PARKED(
        "PARKED", 0xFFFBF6EC.toInt(), 0xFFEEDFC6.toInt(),
        0xFF17263F.toInt(), 0xB317263F.toInt(), 0xFFC76A24.toInt(), 0xFFFFF8EE.toInt(), 0x1A17263F,
        lightBackground = true,
        start = null, end = null, until = "until 15 Nov",
        verse = "The plans of the diligent surely lead to success.", reference = "Proverbs 21:5",
        primary = Action.ASARO, secondary = null,
    );

    /** How far through the lock the clock is, 0..1; the sun and the ring both use it. */
    fun progress(minute: Int): Float {
        val s = start ?: return 1f
        val e = end ?: return 1f
        val total = (e - s + 1440) % 1440
        val passed = ((minute - s + 1440) % 1440).coerceAtMost(total)
        return passed / total.toFloat()
    }

    fun minutesLeft(minute: Int): Int {
        val s = start ?: return 0
        val e = end ?: return 0
        val total = (e - s + 1440) % 1440
        return total - ((minute - s + 1440) % 1440).coerceAtMost(total)
    }

    /**
     * The sky at a given minute. Dawn goes from pre-dawn dark (05:00) to morning blue over gold (07:00); dusk from
     * golden hour (17:30) down to night (21:00). The others keep their own two colours.
     */
    fun sky(minute: Int): Pair<Int, Int> {
        val p = skyProgress(minute)
        return when (this) {
            MORNING -> blend(0xFF0B1322.toInt(), 0xFF3F5E80.toInt(), p) to blend(0xFF3A2A3A.toInt(), 0xFFF2A65A.toInt(), p)
            EVENING -> blend(0xFF3A5577.toInt(), 0xFF0E1626.toInt(), p) to blend(0xFFE8935A.toInt(), 0xFF4A2840.toInt(), p)
            else -> top to bottom
        }
    }

    /**
     * How far through its own light the sky is: dawn is 05:00–07:00, dusk 17:30–20:00. (The evening lock itself runs
     * to 07:00 for Slack, but the evening sky ends when the night takes over.)
     */
    fun skyProgress(minute: Int): Float = when (this) {
        EVENING -> ((minute - (17 * 60 + 30)) / 150f).coerceIn(0f, 1f)
        else -> progress(minute)
    }

    /** A state that shows this mood's message, for previews. */
    fun asState() = when (this) {
        NIGHT -> ScheduleReceiver.Companion.State(ScheduleReceiver.Phase.NIGHT, test = true)
        MORNING -> ScheduleReceiver.Companion.State(ScheduleReceiver.Phase.MORNING, test = true)
        EVENING -> ScheduleReceiver.Companion.State(ScheduleReceiver.Phase.EVENING, test = true)
        ENTRY -> ScheduleReceiver.Companion.State(ScheduleReceiver.Phase.MORNING, test = true, gated = true)
        TIDE -> ScheduleReceiver.Companion.State(ScheduleReceiver.Phase.DAY, test = true, tide = true)
        PARKED -> ScheduleReceiver.Companion.State(ScheduleReceiver.Phase.DAY, test = true)
    }

    companion object {
        fun of(state: ScheduleReceiver.Companion.State): Mood = when {
            state.gated -> ENTRY
            state.tide -> TIDE
            state.phase == ScheduleReceiver.Phase.MORNING -> MORNING
            state.phase == ScheduleReceiver.Phase.EVENING -> EVENING
            // In the day, the only thing locked outside a tide is a parked app.
            state.phase == ScheduleReceiver.Phase.DAY -> PARKED
            else -> NIGHT
        }
    }
}

/**
 * The background, animated: twinkling stars, a crescent moon and the odd shooting star at night; a sun that rises from
 * 05:00 to 07:00 and sinks through the evening, placed by the clock; ruled paper for the entry.
 */
private class SkyView(context: Context, private val mood: Mood, private val minute: () -> Int) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = d }
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 2.2f * d }
    private val rnd = Random(20260930)
    // x, y, size, twinkle phase, twinkle speed
    private val stars = List(110) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat() * 6.3f, 0.6f + rnd.nextFloat() * 1.6f) }
    private val started = System.nanoTime()
    private var shootAt = 2.5f
    private var shootFrom = floatArrayOf(0.2f, 0.08f)
    private val moon = Path()
    private val wave = Path()

    private fun moonPhase() = SkyArt.moonPhase()

    private fun moonPath(cx: Float, cy: Float, r: Float, phase: Float) = SkyArt.moonPath(cx, cy, r, phase, moon)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paintSky(h)
    }

    /** The gradient itself follows the clock at dawn and dusk; night and paper keep their own. */
    private fun paintSky(h: Int) {
        val (top, bottom) = mood.sky(minute())
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = (System.nanoTime() - started) / 1e9f
        if (mood == Mood.MORNING || mood == Mood.EVENING) paintSky(height)
        canvas.drawRect(0f, 0f, w, h, paint)
        when (mood) {
            Mood.NIGHT -> night(canvas, w, h, t)
            Mood.MORNING -> sun(canvas, w, h, rising = true)
            Mood.EVENING -> sun(canvas, w, h, rising = false)
            Mood.ENTRY -> paper(canvas, w, h)
            Mood.TIDE -> sea(canvas, w, h, t)
            Mood.PARKED -> daylight(canvas, w, h)
        }
        // Only the night sky moves every frame; the sun is redrawn now and then as the clock moves it.
        if (mood == Mood.NIGHT || mood == Mood.TIDE) postInvalidateOnAnimation() else postInvalidateDelayed(20_000)
    }

    private fun night(canvas: Canvas, w: Float, h: Float, t: Float) {
        // Stars in the sky above the words and along the edges only, so none ever sits on a letter.
        stars.forEach { s ->
            val x = s[0]
            val y = s[1]
            val clear = y < 0.14f || ((x < 0.08f || x > 0.92f) && y < 0.85f)
            if (!clear) return@forEach
            val twinkle = 0.55f + 0.45f * sin(t * s[4] + s[3])
            star.alpha = (255 * twinkle * (0.35f + 0.65f * s[2])).toInt().coerceIn(20, 255)
            canvas.drawCircle(x * w, y * h, (0.6f + 1.5f * s[2]) * d, star)
        }
        // A crescent moon, high on the right: a lit disc with the sky's own colour laid over most of it.
        val mx = w * 0.82f
        val my = h * 0.085f
        val mr = 22 * d
        val phase = moonPhase()
        val lit = (1 - cos(2 * PI * phase).toFloat()) / 2
        glow.shader = RadialGradient(mx, my, mr * (1.6f + 2.2f * lit), 0x33E8D6A0, 0x00E8D6A0, Shader.TileMode.CLAMP)
        canvas.drawCircle(mx, my, mr * (1.6f + 2.2f * lit), glow)
        glow.shader = null
        glow.color = 0x1FEFE2B8
        canvas.drawCircle(mx, my, mr, glow)
        glow.color = 0xFFEFE2B8.toInt()
        canvas.drawPath(moonPath(mx, my, mr, phase), glow)
        // Now and then, a shooting star across the top.
        val age = t - shootAt
        if (age in 0f..0.9f) {
            val p = age / 0.9f
            val sx = shootFrom[0] * w + p * w * 0.35f
            val sy = shootFrom[1] * h + p * h * 0.06f
            val tail = 70 * d
            streak.shader = LinearGradient(sx, sy, sx - tail, sy - tail * 0.17f, 0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
            streak.alpha = (255 * sin(p * PI).toFloat()).toInt()
            canvas.drawLine(sx, sy, sx - tail, sy - tail * 0.17f, streak)
        } else if (age > 0.9f) {
            shootAt = t + 5f + rnd.nextFloat() * 7f
            shootFrom = floatArrayOf(0.05f + rnd.nextFloat() * 0.45f, 0.03f + rnd.nextFloat() * 0.07f)
        }
    }

    /** The sun sits by the clock: rising from below the screen at 05:00 to low in the sky at 07:00, or sinking from 17:30. */
    private fun sun(canvas: Canvas, w: Float, h: Float, rising: Boolean) {
        val p = mood.skyProgress(minute())
        val up = if (rising) p else 1f - p
        val cx = w * 0.5f
        val cy = h * (1.14f - 0.20f * up)
        val core = if (rising) 0xFFFFD28A.toInt() else 0xFFF3A25B.toInt()
        glow.shader = RadialGradient(cx, cy, w * 0.9f, intArrayOf(0x66FFD28A, 0x22FFB36B, 0x00FFB36B), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, w * 0.9f, glow)
        glow.shader = RadialGradient(cx, cy, w * 0.2f, intArrayOf(core, core, 0x00FFFFFF), floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, w * 0.2f, glow)
        glow.shader = null
    }

    /** Three layers of sea, rolling slowly; the water rises as the tide comes back. */
    private fun sea(canvas: Canvas, w: Float, h: Float, t: Float) {
        val (start, until) = ColourKeeper.tideWindow(context)
        val now = System.currentTimeMillis()
        val back = if (until > now && until > start) ((now - start).toFloat() / (until - start)).coerceIn(0f, 1f) else 0.4f
        val level = h * (0.93f - 0.22f * back)
        val layers = listOf(
            Triple(0x5538B2AC, 16f, 0.35f),
            Triple(0x6628908F, 12f, -0.5f),
            Triple(0x99125E68.toInt(), 9f, 0.7f),
        )
        layers.forEachIndexed { i, (colour, amp, speed) ->
            wave.reset()
            val top = level + i * 14 * d
            wave.moveTo(0f, h)
            var x = 0f
            while (x <= w + 8) {
                val y = top + amp * d * sin(x / w * 2.4f * PI.toFloat() + t * speed + i * 1.7f)
                wave.lineTo(x, y)
                x += 8f
            }
            wave.lineTo(w, h)
            wave.close()
            glow.color = colour
            canvas.drawPath(wave, glow)
        }
    }

    /** Daylight: a soft sun in the corner, for a parked app in the day. */
    private fun daylight(canvas: Canvas, w: Float, h: Float) {
        glow.shader = RadialGradient(w * 0.88f, h * 0.06f, w * 0.8f, intArrayOf(0x55FFD28A, 0x18FFD28A, 0x00FFD28A), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(w * 0.88f, h * 0.06f, w * 0.8f, glow)
        glow.shader = null
    }

    /** Àṣàrò's notebook: faint ruled lines and a margin. */
    private fun paper(canvas: Canvas, w: Float, h: Float) {
        line.color = 0x1417263F
        var y = 120 * d
        while (y < h) {
            canvas.drawLine(0f, y, w, y, line)
            y += 34 * d
        }
        line.color = 0x33C76A24
        canvas.drawLine(26 * d, 56 * d, 26 * d, h, line)
    }
}

/** The ring: fills with the share of the lock that has passed, with hour ticks, a glowing tip, and the countdown. */
private class RingView(
    context: Context, private val mood: Mood, display: Typeface, strong: Typeface, private val label: String, private val minute: () -> Int,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7 * d; color = mood.track }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7 * d; strokeCap = Paint.Cap.ROUND; color = mood.accent }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 18 * d; strokeCap = Paint.Cap.ROUND; color = mood.accent }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND; color = mood.soft }
    private val tip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseSize = 44 * d * resources.configuration.fontScale
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = display; textSize = baseSize; color = mood.ink; textAlign = Paint.Align.CENTER }
    private val smallSize = 11.5f * d * resources.configuration.fontScale
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = strong; textSize = smallSize; color = mood.soft; textAlign = Paint.Align.CENTER; letterSpacing = 0.14f }
    private val box = RectF()

    private var shown = 0f      // animated share of the ring
    private var breath = 0f     // 0..1, the glow's pulse
    private var target = 0f
    private var centre = ""

    init {
        refresh()
        ValueAnimator.ofFloat(0f, target).apply {
            duration = 1500
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
        val m = minute()
        target = mood.progress(m)
        val left = mood.minutesLeft(m)
        centre = when {
            mood == Mood.TIDE -> tideCountdown()
            mood == Mood.PARKED -> parkedCountdown()
            mood.start == null -> "✎"
            left >= 60 -> "${left / 60}h ${left % 60}m"
            else -> "${left}m"
        }
        contentDescription = if (mood == Mood.ENTRY) "Locked until today's entry" else "$centre $label"
        if (shown > 0f) shown = target
        invalidate()
    }

    /** Minutes until the tide is back; the ring fills as it comes in. (A preview with no tide shows a sample.) */
    private fun tideCountdown(): String {
        val (start, until) = ColourKeeper.tideWindow(context)
        val now = System.currentTimeMillis()
        if (until <= now || until <= start) { target = 0.4f; return "9m" }
        target = ((now - start).toFloat() / (until - start)).coerceIn(0f, 1f)
        val left = ((until - now + 59_999) / 60_000).toInt()
        return "${left}m"
    }

    /** Days until 15 Nov; the ring fills from 30 Sep, when the Six Anchors plan began. */
    private fun parkedCountdown(): String {
        val from = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 30, 0, 0, 0) }.timeInMillis
        val to = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 15, 0, 0, 0) }.timeInMillis
        val now = System.currentTimeMillis()
        target = ((now - from).toFloat() / (to - from)).coerceIn(0f, 1f)
        val days = ((to - now + 86_399_999) / 86_400_000).toInt().coerceAtLeast(0)
        return "${days}d"
    }

    override fun onDraw(canvas: Canvas) {
        val inset = 16 * d
        box.set(inset, inset, width - inset, height - inset)
        val cx = width / 2f
        val cy = height / 2f
        val r = box.width() / 2f
        canvas.drawOval(box, track)

        val sweep = 360f * shown.coerceIn(0f, 1f)
        if (sweep > 16f) {   // a sliver of arc reads as a toggle switch; the glowing tip alone says "just started"
            glow.alpha = (16 + 28 * breath).toInt()
            canvas.drawArc(box, -90f, sweep, false, glow)
            canvas.drawArc(box, -90f, sweep, false, arc)
        }

        // A glowing tip where the ring has got to.
        val a = (-90 + sweep) * (PI.toFloat() / 180f)
        val tx = cx + r * cos(a)
        val ty = cy + r * sin(a)
        tip.shader = RadialGradient(tx, ty, 16 * d, mood.accent, mood.accent and 0x00FFFFFF, Shader.TileMode.CLAMP)
        tip.alpha = (140 + 90 * breath).toInt()
        canvas.drawCircle(tx, ty, 16 * d, tip)
        tip.shader = null
        tip.color = mood.onAccent
        tip.alpha = 255
        canvas.drawCircle(tx, ty, 3.2f * d, tip)

        // The countdown shrinks to sit inside the ring with room to spare ("10h 16m" is wide).
        val room = box.width() - 2 * 28 * d
        big.textSize = baseSize
        val wide = big.measureText(centre)
        if (wide > room) big.textSize = baseSize * room / wide
        canvas.drawText(centre, cx, cy + big.textSize * 0.2f, big)
        // The label shrinks too if a long app name would reach the ring.
        small.textSize = smallSize
        val labelWide = small.measureText(label.uppercase())
        if (labelWide > room) small.textSize = smallSize * room / labelWide
        canvas.drawText(label.uppercase(), cx, cy + big.textSize * 0.2f + 26 * d, small)
    }
}
