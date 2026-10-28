package com.tolu.dpc

import android.app.Activity
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * His people, from Field Service (read on the phone by FieldService), as a card file: each street a tab, each person a
 * ruled index card under it, nearest street first. The people he's near (from the reminder) sit on top, tilted. A card
 * shows study or return visit, how long since the last visit, the "next time" note and the last six visits as dots;
 * tap it for the whole note, what happened last time and the dates. Read-only: visits are still logged in Field
 * Service. Opened from the nearby reminder or from the Now screen.
 */
class PeopleActivity : Activity() {

    private val open = HashSet<String>()
    private lateinit var ui: Ui
    private lateinit var elite: Typeface
    private var query = ""

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        ui = Ui(this, dark = true)
        elite = resources.getFont(R.font.special_elite)
        val all = People.all(this)
        val here = Away.lastSeen(this)
        val nearby = intent.getStringArrayExtra(EXTRA_NAMES)?.toSet().orEmpty()
        fun dist(p: People.Person) = here?.let { (la, lo) -> People.distance(p, la, lo) } ?: Double.MAX_VALUE
        val content = ui.column(padTop = 22).apply { setPadding(ui.dp(14), paddingTop, ui.dp(14), paddingBottom) }
        val studies = all.count { it.study }
        content.addView(ui.text(if (nearby.isNotEmpty()) "${nearby.size} near you" else "${all.size} people", 28f, ui.display, CREAM).apply {
            setPadding(ui.dp(6), 0, 0, 0)
        })
        content.addView(ui.text("$studies studies · ${all.size - studies} return visits", 12.5f, ui.body, CREAM_DIM, 2).apply {
            setPadding(ui.dp(6), 0, 0, 0)
        })
        if (all.isEmpty()) content.addView(ui.text("Nothing read from Field Service yet.", 14f, ui.body, CREAM_DIM, 16))

        // Typing refills only the list below, so the search box keeps its focus and keyboard.
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fill() {
            list.removeAllViews()
            val q = plain(query.trim())
            val shown = if (q.isEmpty()) all else all.filter { p -> listOf(p.name, p.street, p.next, p.note).any { plain(it).contains(q) } }
            if (shown.isEmpty() && all.isNotEmpty()) list.addView(ui.text("No one matches “${query.trim()}”.", 14f, ui.body, CREAM_DIM, 22).apply {
                setPadding(ui.dp(6), 0, 0, 0)
            })
            // Nearby people first (if opened from the reminder), then everyone else; a tab per street, nearest first.
            val (first, rest) = shown.partition { it.name in nearby }
            listOf(first, rest).filter { it.isNotEmpty() }.forEachIndexed { i, group ->
                val near = i == 0 && first.isNotEmpty()
                if (i == 1 && first.isNotEmpty()) list.addView(ui.text("EVERYONE ELSE", 10.5f, ui.strong, CREAM_DIM, 32).apply {
                    letterSpacing = 0.14f; setPadding(ui.dp(6), ui.dp(30), 0, 0)
                })
                group.groupBy { it.street.ifBlank { "Pinned" } }.entries
                    .sortedBy { (_, ps) -> ps.minOf { dist(it) } }
                    .forEach { (street, ps) ->
                        list.addView(tab(street, ps.minOf { dist(it) }))
                        ps.sortedWith(compareBy({ !it.study }, { -it.last })).forEachIndexed { j, p -> list.addView(card(p, j == 0, near)) }
                    }
            }
        }
        if (all.isNotEmpty()) content.addView(search { query = it; fill() })
        content.addView(list)
        fill()
        val footer = ui.footer()
        footer.addView(ui.text("Log a visit in Field Service  →", 13.5f, ui.body, CREAM, 0).apply {
            gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, ui.dp(4))
            setOnClickListener { packageManager.getLaunchIntentForPackage(FieldService.PKG)?.let { startActivity(it) } }
        })
        val root = ui.page(content, footer)
        root.setBackgroundColor(NAVY); window.statusBarColor = NAVY; window.navigationBarColor = NAVY
        setContentView(root)
    }

    /** The search box: a cream field with a magnifier and a clear button. */
    private fun search(changed: (String) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = ui.rounded(PAPER, 10)
        setPadding(ui.dp(12), 0, ui.dp(4), 0)
        layoutParams = LinearLayout.LayoutParams(-1, ui.dp(46)).apply { topMargin = ui.dp(14) }
        addView(ui.text("⌕", 20f, ui.body, GREY).apply { setPadding(0, 0, ui.dp(8), ui.dp(2)) })
        val clear = ui.text("✕", 15f, ui.body, GREY).apply {
            gravity = Gravity.CENTER; visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        }
        val field = android.widget.EditText(context).apply {
            hint = "Search name, street or notes"; setHintTextColor(GREY); setTextColor(INK)
            textSize = 15f; typeface = ui.body; background = null; setPadding(0, 0, 0, 0); isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setText(query); setSelection(query.length)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    changed(s?.toString().orEmpty())
                }
            })
            // The search key just puts the keyboard away; the list already shows the matches.
            setOnEditorActionListener { v, _, _ ->
                getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0); true
            }
        }
        clear.setOnClickListener { field.setText("") }
        addView(field, LinearLayout.LayoutParams(0, -1, 1f))
        addView(clear, LinearLayout.LayoutParams(ui.dp(40), -1))
    }

    /** Lower case without accents, so "segun" finds "Ṣẹgun". */
    private fun plain(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)

    /** The street's tab: a cream label with rounded top corners, sitting on the first card. */
    private fun tab(street: String, d: Double) = TextView(this).apply {
        text = SpannableStringBuilder(street).apply {
            if (d < 50_000) {
                val s = length
                append("   " + if (d < 1000) "${d.toInt()} m" else "%.1f km".format(d / 1000))
                setSpan(ForegroundColorSpan(0x9917263F.toInt()), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        textSize = 11.5f; typeface = ui.strong; setTextColor(INK)
        setPadding(ui.dp(11), ui.dp(5), ui.dp(11), ui.dp(4))
        val r = ui.dpf(6f)
        background = GradientDrawable().apply { setColor(TAB); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(14); marginStart = ui.dp(6) }
    }

    /** One text line on the card's ruling: every line is exactly LINE tall so the writing sits on the blue lines. */
    private fun lined(s: CharSequence, sp: Float, face: Typeface, colour: Int) = TextView(this).apply {
        text = s; textSize = sp; typeface = face; setTextColor(colour); includeFontPadding = false
        // Baseline 5dp above each rule: n lines are exactly n × LINE tall, so the next block starts on a line too.
        lineHeight = ui.dp(LINE); firstBaselineToTopHeight = ui.dp(LINE - 5); lastBaselineToBottomHeight = ui.dp(5)
    }

    private fun card(p: People.Person, underTab: Boolean, near: Boolean): LinearLayout {
        val expanded = p.name in open
        val kind = if (p.study) STUDY else RV
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(30), ui.dp(PAD_TOP), ui.dp(12), ui.dp(10 + SHADOW))
            background = Ruled(underTab)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (underTab) 0 else ui.dp(8) }
            if (near) rotation = -1.2f
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(lined(p.name, 16f, ui.display, INK).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(lined(if (p.study) "STUDY" else "RETURN VISIT", 9.5f, ui.strong, kind).apply {
                letterSpacing = 0.08f
            }, LinearLayout.LayoutParams(-2, -2))
        })
        card.addView(lined("${People.ago(p.last).replaceFirstChar { it.uppercase() }} · ${p.visits} visit${if (p.visits == 1) "" else "s"}",
            11.5f, ui.body, GREY))
        if (p.next.isNotBlank()) card.addView(lined(SpannableStringBuilder("NEXT  ").apply {
            setSpan(ForegroundColorSpan(RED), 0, 4, 0); setSpan(TypefaceSpan(ui.strong), 0, 4, 0)
            setSpan(android.text.style.RelativeSizeSpan(0.8f), 0, 4, 0)
            append(p.next)
        }, 12.5f, elite, INK).apply { if (!expanded) { maxLines = 2; ellipsize = TextUtils.TruncateAt.END } })
        if (p.dates.size > 1) card.addView(dots(p))
        if (expanded) {
            if (p.note.isNotBlank()) {
                card.addView(lined("LAST TIME", 9.5f, ui.strong, RED).apply { letterSpacing = 0.1f })
                card.addView(lined(p.note.trim(), 12.5f, elite, INK))
            }
            val df = SimpleDateFormat("EEE d MMM", Locale.UK)
            val known = p.dates.filter { it.first > 0 }
            if (known.isNotEmpty()) card.addView(lined(SpannableStringBuilder().apply {
                known.forEachIndexed { i, (d, held) ->
                    if (i > 0) append("  ·  ")
                    val s = length; append(df.format(Date(d)))
                    if (!held) setSpan(StrikethroughSpan(), s, length, 0)
                }
            }, 11.5f, ui.body, GREY))
        }
        // Swap just this card, so the page stays where he scrolled to.
        card.setOnClickListener { v ->
            if (!open.remove(p.name)) open += p.name
            val parent = v.parent as ViewGroup
            val i = parent.indexOfChild(v)
            parent.removeViewAt(i); parent.addView(card(p, underTab, near), i)
        }
        return card
    }

    /** The last six visits, newest first: a filled dot when it was held, a ring when it wasn't. */
    private fun dots(p: People.Person) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, ui.dp(LINE))
        p.dates.forEach { (_, held) ->
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (held) setColor(INK) else setStroke(ui.dp(1) + 1, INK)
                }
            }, LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)).apply { marginEnd = ui.dp(4) })
        }
        val missed = p.dates.any { !it.second }
        addView(lined("last ${p.dates.size}" + if (missed) " · ○ not held" else "", 10f, ui.body, GREY).apply {
            setPadding(ui.dp(4), 0, 0, 0)
        }, LinearLayout.LayoutParams(-2, -2))
    }

    /** The index card: cream, a red margin line, blue ruling every LINE, a hard shadow under it. */
    private inner class Ruled(private val underTab: Boolean) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PAPER }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
        private val rule = Paint().apply { color = RULE; strokeWidth = ui.dpf(1f) }
        private val margin = Paint().apply { color = MARGIN; strokeWidth = ui.dpf(1f) }

        override fun draw(c: Canvas) {
            val b = bounds; val sh = ui.dpf(SHADOW.toFloat()); val r = ui.dpf(4f)
            val radii = floatArrayOf(if (underTab) 0f else r, if (underTab) 0f else r, r, r, r, r, r, r)
            fun path(top: Float, bottom: Float) = Path().apply { addRoundRect(RectF(b.left.toFloat(), top, b.right.toFloat(), bottom), radii, Path.Direction.CW) }
            c.drawPath(path(b.top + sh, b.bottom.toFloat()), shadow)
            val face = path(b.top.toFloat(), b.bottom - sh)
            c.drawPath(face, fill)
            c.save(); c.clipPath(face)
            var y = b.top + ui.dpf(PAD_TOP.toFloat()) + ui.dpf(LINE.toFloat())
            while (y < b.bottom - sh - ui.dpf(4f)) { c.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, rule); y += ui.dpf(LINE.toFloat()) }
            val x = b.left + ui.dpf(22f)
            c.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), margin)
            c.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        const val EXTRA_NAMES = "names"
        private const val LINE = 22
        private const val PAD_TOP = 6
        private const val SHADOW = 3
        private const val NAVY = 0xFF2B3A52.toInt()
        private const val CREAM = 0xFFFBF6EC.toInt()
        private const val CREAM_DIM = 0xB3FBF6EC.toInt()
        private const val TAB = 0xFFE8D6A0.toInt()
        private const val PAPER = 0xFFFFFDF7.toInt()
        private const val RULE = 0xFFCFE0EE.toInt()
        private const val MARGIN = 0xFFE9A3A8.toInt()
        private const val INK = 0xFF17263F.toInt()
        private const val GREY = 0xFF6B6A66.toInt()
        private const val RED = 0xFFC8323F.toInt()
        private const val STUDY = 0xFFC8323F.toInt()
        private const val RV = 0xFF1E7A6E.toInt()
    }
}
