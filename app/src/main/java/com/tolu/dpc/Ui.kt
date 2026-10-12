package com.tolu.dpc

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The look shared by the newer screens (Now, sessions, Where were you?, the review, the field pass): a cream page,
 * white cards, Fraunces titles, Work Sans text, and one colour per kind of time. Night screens use the dark page.
 * Built in code like the rest of the DPC.
 */
class Ui(private val a: Activity, val dark: Boolean = false) {
    val page = if (dark) 0xFF101A2E.toInt() else 0xFFFBF6EC.toInt()
    val card = if (dark) 0x0FFFFFFF else 0xFFFFFDF8.toInt()
    val ink = if (dark) 0xFFEFE6D8.toInt() else 0xFF17263F.toInt()
    val ink2 = if (dark) 0xFFC9CFDB.toInt() else 0xFF52514E.toInt()
    val muted = if (dark) 0xFFA9B0C2.toInt() else 0xFF898781.toInt()
    val line = if (dark) 0x1AFFFFFF else 0xFFE6DCCB.toInt()
    val good = 0xFF0B7A52.toInt()
    val goodBg = 0xFFDCF1E7.toInt()
    val bad = 0xFFA32B2B.toInt()
    val badBg = 0xFFF6DEDE.toInt()

    val display: Typeface = a.resources.getFont(R.font.fraunces_black)
    val body: Typeface = a.resources.getFont(R.font.work_sans)
    val strong: Typeface = a.resources.getFont(R.font.work_sans_semibold)

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    fun dpf(v: Float) = v * a.resources.displayMetrics.density

    /**
     * A full-height page: `content` scrolls, `footer` (buttons) stays at the bottom. Sets the bars to match.
     */
    fun page(content: LinearLayout, footer: LinearLayout? = null): View {
        a.window.statusBarColor = page
        a.window.navigationBarColor = page
        @Suppress("DEPRECATION")
        a.window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(page) }
        root.addView(ScrollView(a).apply { isFillViewport = true; addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        footer?.let { root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        // Android 15 draws apps edge to edge: keep clear of the status bar, the navigation bar and the keyboard.
        if (android.os.Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
            v.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }
        return root
    }

    fun column(padTop: Int = 28, padBottom: Int = 16) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(padTop), dp(22), dp(padBottom))
    }

    fun footer() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), dp(22)) }

    fun text(s: CharSequence, sp: Float, face: Typeface = body, color: Int = ink, top: Int = 0) = TextView(a).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = face; setTextColor(color)
        setLineSpacing(0f, 1.2f); setPadding(0, dp(top), 0, 0)
    }

    /** Small capitals above a title: "NOW", "YOU'RE BACK". */
    fun kick(s: String, color: Int = muted, top: Int = 0) = text(s.uppercase(), 11.5f, strong, color, top).apply { letterSpacing = 0.12f }

    fun big(s: String, top: Int = 6) = text(s, 30f, display, ink, top).apply { setLineSpacing(0f, 1.05f) }

    fun sub(s: String, top: Int = 4) = text(s, 14f, body, ink2, top)

    fun rounded(fill: Int, radius: Int = 16, stroke: Int = 0, strokeWidth: Float = 1f, dashed: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dpf(radius.toFloat()); setColor(fill)
        if (stroke != 0) if (dashed) setStroke(dpf(strokeWidth).toInt(), stroke, dpf(5f), dpf(4f)) else setStroke(dpf(strokeWidth).toInt(), stroke)
    }

    /** A white card holding `children` vertically. */
    fun card(vararg children: View, pad: Int = 14) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad - 2), dp(pad), dp(pad - 2))
        background = rounded(card, 16, line)
        children.forEach { addView(it) }
    }

    /** A pill to choose: a coloured dot, the label; dark when chosen. */
    fun chip(label: String, dot: Int? = null, on: Boolean = false, onClick: (() -> Unit)? = null) = TextView(a).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f); typeface = strong
        setTextColor(if (on) page else ink)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = rounded(if (on) ink else card, 999, if (on) ink else line, 1.5f)
        dot?.let {
            val d = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(it); setSize(dp(9), dp(9)) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null); compoundDrawablePadding = dp(7)
        }
        onClick?.let { f -> setOnClickListener { f() } }
    }

    /** Chips that wrap onto new lines. */
    fun chips(vararg views: View, top: Int = 14): ViewGroup = FlowLayout(a, dp(8)).apply {
        setPadding(0, dp(top), 0, 0); views.forEach { addView(it) }
    }

    fun button(label: String, fill: Int = ink, fg: Int = page, top: Int = 10, onClick: () -> Unit) = TextView(a).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = strong; setTextColor(fg); gravity = Gravity.CENTER
        setPadding(dp(18), dp(14), dp(18), dp(14)); background = rounded(fill, 999)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    }

    fun link(label: String, top: Int = 10, onClick: () -> Unit) = text(label, 13.5f, body, muted, 0).apply {
        gravity = Gravity.CENTER; setPadding(0, dp(top), 0, dp(4)); setOnClickListener { onClick() }
    }

    fun input(hint: String, value: String = "") = EditText(a).apply {
        this.hint = hint; setText(value); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = body
        setTextColor(ink); setHintTextColor(muted); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(14), dp(13), dp(14), dp(13)); background = rounded(card, 14, ink, 1.5f)
        backgroundTintList = null
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
    }

    /** A thin progress bar in `color`, `fraction` full. */
    fun bar(fraction: Float, color: Int, height: Int = 7, top: Int = 12): View = FrameLayout(a).apply {
        background = rounded(if (dark) 0x1FFFFFFF else 0x1717263F, 999)
        addView(View(a).apply { background = rounded(color, 999) },
            FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)).apply { topMargin = dp(top) }
        val track = this
        post { val fill = getChildAt(0); fill.layoutParams = fill.layoutParams.apply { width = (track.width * fraction.coerceIn(0f, 1f)).toInt() }; fill.requestLayout() }
    }

    /** Two labels at either end of a line ("till 14:00" … "3 h 30 left"). */
    fun ends(left: String, right: String, top: Int = 5) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(top), 0, 0)
        addView(text(left, 12f, body, ink2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(right, 12f, body, ink2))
    }

    /** A row: label on the left, `value` on the right (text or a pill). */
    fun row(label: String, value: View, top: Int = 6) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13), dp(10), dp(13), dp(10)); background = rounded(card, 13, line)
        addView(text(label, 13.5f, strong), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(value)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    }

    fun pill(s: String, ok: Boolean) = text(s.uppercase(), 11f, strong, if (ok) good else bad).apply {
        letterSpacing = 0.05f; setPadding(dp(9), dp(3), dp(9), dp(3)); background = rounded(if (ok) goodBg else badBg, 999)
    }

    /** A line with a coloured edge on the left: "15:00  Work". */
    fun edged(time: String, label: String, color: Int, top: Int = 7) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), 0, 0, 0)
        background = android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply { setColor(color) })).apply {
            setLayerWidth(0, dp(3)); setLayerGravity(0, Gravity.START or Gravity.FILL_VERTICAL)
        }
        addView(text(time, 14f, body, muted), LinearLayout.LayoutParams(dp(50), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(text(label, 14f, body, ink))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    }

    fun gap(h: Int) = View(a).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    fun tint(v: View, color: Int) { v.backgroundTintList = ColorStateList.valueOf(color) }
}

/** Lays children left to right, wrapping onto new lines, `gap` apart. For the chips. */
class FlowLayout(context: android.content.Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += c.measuredWidth + gap; rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap; rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}
