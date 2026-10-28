package com.tolu.dpc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar

/**
 * The bar: one 4×1 widget that shows what matters now. Most urgent wins:
 *   a session running  → its timer, how much of it JW Library was open, Stop
 *   from 20:01         → the review, until he says it looks right
 *   before the entry   → "Today's entry first", Write
 *   the night          → today in numbers: study, field or Kowane (only what the day had), planned blocks kept
 *   otherwise          → the mode, time left and today's strip; on the day's last block a single bar and what follows;
 *                        in Study time a big Start study; in free time two round starts (study, meeting prep)
 * Drawn as one image at the widget's size, with invisible tap zones over it. Like the ring, being placed keeps HiOS
 * from freezing the DPC.
 */
class NowBar : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        ScheduleReceiver.enforce(context)   // also redraws the bar
        KeepAliveService.start(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, widgetId: Int, options: Bundle) {
        refresh(context)
    }

    companion object {
        private const val CREAM = 0xFFFBF6EC.toInt()
        /** The mockup's own size: the bar is drawn in these units and scaled to the widget. */
        private const val W = 254f
        /** The field pill's width, in the mockup's units. */
        private const val FIELD_PILL_W = 78f
        private const val H = 87f
        private const val NIGHT = 0xFF101A2E.toInt()
        private const val INK = 0xFF17263F.toInt()
        private const val INK2 = 0xFF52514E.toInt()
        private const val MUTED = 0xFF898781.toInt()
        private const val TRACK = 0xFFEDE6D8.toInt()
        private const val GOLD = 0xFFE8D6A0.toInt()
        private const val NIGHT_SOFT = 0xFFA9B0C2.toInt()
        private const val NIGHT_TRACK = 0xFF26324A.toInt()
        private const val FREE = 0xFFC8323F.toInt()
        private const val BIBLE = 0xFFC76A24.toInt()
        private const val STRIP_FROM = 5 * 60
        private const val STRIP_TO = 21 * 60

        private enum class Face { RUNNING, REVIEW, ENTRY, NIGHT, STUDY, FREE, NORMAL, LAST }

        @Volatile private var lastRefresh = 0L
        /** adb (ACTION_PREVIEW_BAR): show one face for a minute, to look at it. */
        @Volatile var previewFace: String? = null
        @Volatile var previewUntil = 0L
        /** With a preview: the mode to draw it in (look only; sample times stand in for the calendar). */
        @Volatile var previewMode: String? = null
        private fun previewing() = SystemClock.elapsedRealtime() < previewUntil

        /** Redraw at most once a minute from the ticks (always when something happened). */
        fun tick(context: Context) {
            if (SystemClock.elapsedRealtime() - lastRefresh >= 60_000L) refresh(context)
        }

        fun refresh(context: Context) {
            lastRefresh = SystemClock.elapsedRealtime()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowBar::class.java))
            if (ids.isEmpty()) return
            val state = ScheduleReceiver.currentState(context)
            val face = previewFace?.takeIf { SystemClock.elapsedRealtime() < previewUntil }?.let { n -> Face.entries.firstOrNull { it.name == n } }
                ?: faceOf(context, state)
            ids.forEach { id ->
                runCatching { manager.updateAppWidget(id, views(context, manager, id, state, face)) }
            }
        }

        private fun minuteNow() = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

        private fun faceOf(context: Context, state: ScheduleReceiver.Companion.State): Face {
            val mode = ModeWallpaper.modeOf(context, state)
            return when {
                Logbook.running(context) != null -> Face.RUNNING
                Ask.reviewDue(context) -> Face.REVIEW
                state.gated -> Face.ENTRY
                mode == "night" -> Face.NIGHT
                mode == "study" -> Face.STUDY
                mode == "free" -> Face.FREE
                mode != "field" && lastBlock(context, mode) -> Face.LAST
                else -> Face.NORMAL
            }
        }

        // ── The day's sessions ────────────────────────────────────────────────

        private data class Planned(val window: SessionWindow, val kind: Kind)

        private fun planned(context: Context): List<Planned> = runCatching {
            Field.today(context).map { Planned(it, Kind.FIELD) } + Study.today(context).map { Planned(it, Kind.STUDY) } +
                WorkTime.today(context).map { Planned(it, Kind.WORK) } + BankTime.today(context).map { Planned(it, Kind.BANK) }
        }.getOrDefault(emptyList()).sortedBy { it.window.start }

        /** This stretch of the day, by mode (the same as the Now screen). */
        private fun window(context: Context, mode: String): SessionWindow? {
            val day = startOfDay(0); val now = System.currentTimeMillis(); val m = minuteNow()
            if (previewMode != null && previewing()) return SessionWindow(now - 90 * 60_000L, now + 50 * 60_000L)
            return when (mode) {
                "field" -> Field.holding(context)?.let { SessionWindow(it.start, Field.endsAt(context) ?: it.end) }
                "study" -> Study.holding(context)
                "work" -> WorkTime.holding(context)
                "bank" -> BankTime.holding(context)
                "morning" -> SessionWindow(day + 5 * 3_600_000L, day + 7 * 3_600_000L)
                "night" -> if (m >= 12 * 60) SessionWindow(day + 20 * 3_600_000L, day + 29 * 3_600_000L) else SessionWindow(day - 4 * 3_600_000L, day + 5 * 3_600_000L)
                "free" -> {
                    val all = planned(context).map { it.window }
                    val from = all.filter { it.end <= now }.maxOfOrNull { it.end } ?: (day + 7 * 3_600_000L)
                    val to = all.filter { it.start > now }.minOfOrNull { it.start } ?: (day + 20 * 3_600_000L)
                    SessionWindow(from, maxOf(to, now + 60_000L))
                }
                else -> null
            }
        }

        /** In a session with nothing planned after it today. */
        private fun lastBlock(context: Context, mode: String): Boolean {
            if (mode !in setOf("field", "work", "bank")) return false
            val w = window(context, mode) ?: return false
            return planned(context).none { it.window.start >= w.end - 60_000L }
        }

        // ── Drawing ───────────────────────────────────────────────────────────

        private fun views(context: Context, manager: AppWidgetManager, id: Int, state: ScheduleReceiver.Companion.State, face: Face): RemoteViews {
            val d = context.resources.displayMetrics.density
            val o = manager.getAppWidgetOptions(id)
            val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
            val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 80
            // The card: the mockup's 254 × 87, scaled to the widget's width, at the top; below it the widget stays clear
            // (two rows of space, one and a half rows of bar). At one row it shrinks to fit.
            val scale = minOf(wDp / W, hDp / H)
            val px = 12f
            val bmp = Bitmap.createBitmap((wDp * d).toInt().coerceAtLeast(10), (hDp * d).toInt().coerceAtLeast(10), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(d * scale, d * scale)
            canvas.translate((wDp / scale - W) / 2f, 0f)
            Painter(context, canvas).draw(face, state)

            return RemoteViews(context.packageName, R.layout.widget_bar).apply {
                setImageViewBitmap(R.id.bar_image, bmp)
                val main = when (face) {
                    Face.RUNNING -> activity(context, 30, Intent(context, SessionActivity::class.java))
                    Face.REVIEW -> activity(context, 31, Intent(context, ReviewActivity::class.java))
                    // While the entry is due: the reading check (where it stands, Try again, a way to Àṣàrò).
                    Face.ENTRY -> activity(context, 32, Intent(context, ReadingActivity::class.java))
                    else -> activity(context, 33, Intent(context, NowActivity::class.java))
                }
                setOnClickPendingIntent(R.id.bar_main, main)
                setOnClickPendingIntent(R.id.bar_top, main)
                setOnClickPendingIntent(R.id.bar_rest, main)
                // The tap zones follow the drawing (mockup units × scale).
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    val dp = android.util.TypedValue.COMPLEX_UNIT_DIP
                    setViewLayoutWidth(R.id.bar_prep, (px + 33f) * scale, dp)
                    setViewLayoutWidth(R.id.bar_study, 36f * scale, dp)
                    setViewLayoutHeight(R.id.bar_top, 44f * scale, dp)
                    setViewLayoutHeight(R.id.bar_buttons, 36f * scale, dp)
                    setViewLayoutWidth(R.id.bar_smallprep, (px + 66f) * scale, dp)
                }
                val studyRow = face == Face.STUDY
                setViewVisibility(R.id.bar_row, if (studyRow) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.bar_studyrow, if (studyRow) View.VISIBLE else View.GONE)
                val round = face == Face.FREE
                setViewVisibility(R.id.bar_study, if (round) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.bar_prep, if (round) View.VISIBLE else View.GONE)
                val mode = previewMode?.takeIf { previewing() } ?: ModeWallpaper.modeOf(context, state)
                val marks = Field.marks(context, startOfDay(0))
                val fieldTap = face == Face.NORMAL && mode == "field" && marks.second == null
                val action = face in setOf(Face.RUNNING, Face.REVIEW, Face.ENTRY) || fieldTap
                if (fieldTap && android.os.Build.VERSION.SDK_INT >= 31)
                    setViewLayoutWidth(R.id.bar_action, (FIELD_PILL_W + px) * scale, android.util.TypedValue.COMPLEX_UNIT_DIP)
                setViewVisibility(R.id.bar_action, if (action) View.VISIBLE else View.GONE)
                if (round || studyRow) {
                    setOnClickPendingIntent(R.id.bar_study, start(context, Kind.STUDY))
                    setOnClickPendingIntent(R.id.bar_prep, start(context, Kind.PREP))
                    setOnClickPendingIntent(R.id.bar_bigstudy, start(context, Kind.STUDY))
                    setOnClickPendingIntent(R.id.bar_smallprep, start(context, Kind.PREP))
                }
                if (action) setOnClickPendingIntent(R.id.bar_action, when (face) {
                    Face.RUNNING -> activity(context, 34, Intent(context, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_STOP, true))
                    Face.NORMAL -> PendingIntent.getBroadcast(context, 42, Intent(context, ScheduleReceiver::class.java)
                        .setAction(ScheduleReceiver.ACTION_FIELD_MARK).putExtra("which", if (marks.first == null) "start" else "end"),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    else -> main
                })
                setContentDescription(R.id.bar_root, "Now")
            }
        }

        private fun activity(context: Context, code: Int, intent: Intent) = PendingIntent.getActivity(context, code,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun start(context: Context, kind: Kind) = PendingIntent.getBroadcast(context, if (kind == Kind.STUDY) 40 else 41,
            Intent(context, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION_START).putExtra("kind", kind.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /**
         * Draws one face, following the mockup (justme/widget-ideas.html, "One bar") in its own units: 254 wide, 87
         * tall, scaled to the widget. The card sits at the top of the widget's two rows, one and a half rows tall, and
         * the rest is left clear.
         */
        private class Painter(val context: Context, val c: Canvas) {
            val display: Typeface = context.resources.getFont(R.font.fraunces_black)
            val body: Typeface = context.resources.getFont(R.font.work_sans)
            val strong: Typeface = context.resources.getFont(R.font.work_sans_semibold)
            val w = W; val h = H
            val px = 12f                         // side padding, as in the mockup

            fun draw(face: Face, state: ScheduleReceiver.Companion.State) {
                // About 20 dp on the phone, like Genesis and Field Service beside it.
                fill(RectF(0f, 0f, w, h), if (face == Face.NIGHT) NIGHT else CREAM, 13f)
                val mode = previewMode?.takeIf { previewing() } ?: ModeWallpaper.modeOf(context, state)
                when (face) {
                    Face.RUNNING -> running()
                    Face.REVIEW -> review()
                    Face.ENTRY -> entry()
                    Face.NIGHT -> night()
                    Face.STUDY -> { head(mode); studyButtons() }
                    Face.FREE -> { head(if (mode == "free") mode else "free", buttons = true); strip() }
                    Face.LAST -> last(mode)
                    Face.NORMAL -> { head(mode); strip(); if (mode == "field") fieldPill() }
                }
            }

            // ── Pieces ──

            fun paint(color: Int, size: Float, face: Typeface = body, align: Paint.Align = Paint.Align.LEFT) =
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = face; textAlign = align }

            fun fill(r: RectF, color: Int, radius: Float) = c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })

            fun colourOf(mode: String) = when (mode) {
                "field" -> Kind.FIELD.colour; "study" -> Kind.STUDY.colour; "work" -> Kind.WORK.colour; "bank" -> Kind.BANK.colour
                "free" -> FREE; "night" -> GOLD; else -> BIBLE
            }

            fun left(ms: Long): String {
                val m = (maxOf(0L, ms) / 60_000L).toInt()
                return when { m >= 60 && m % 60 == 0 -> "${m / 60} h left"; m >= 60 -> "${m / 60} h ${m % 60} left"; else -> "$m m left" }
            }

            fun till(mode: String) = window(context, mode)?.let { "till ${NowActivity.hhmm(it.end)} · ${left(it.end - System.currentTimeMillis())}" } ?: ""

            fun kicker(text: String, color: Int, x: Float, y: Float) =
                c.drawText(text.uppercase(), x, y, paint(color, 8.5f, strong).apply { letterSpacing = 0.12f })

            /** The mockup's head: "■ Title" (Fraunces 16), "till … left" under it (10); in free time two round starts on the right. */
            fun head(mode: String, buttons: Boolean = false) {
                val name = NowActivity.NAMES[mode] ?: mode
                c.drawRect(px, 13.5f, px + 6.5f, 20f, Paint().apply { color = colourOf(mode) })
                c.drawText(name, px + 11f, 23f, paint(INK, 16f, display))
                c.drawText(till(mode), px, 36f, paint(INK2, 10f))
                if (buttons) {
                    roundStart(w - px - 15f, 24f, Kind.PREP.colour)
                    roundStart(w - px - 15f - 36f, 24f, Kind.STUDY.colour)
                }
            }

            fun roundStart(cx: Float, cy: Float, color: Int) {
                c.drawCircle(cx, cy, 15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
                c.drawText("▶", cx + 1.2f, cy + 4.6f, paint(0xFFFFFFFF.toInt(), 13f, strong, Paint.Align.CENTER))
            }

            /** The mockup's strip, full width: today 05:00–22:00, planned dashed, done solid, a line for now; "Next" and the hours under it. */
            fun strip() {
                val x0 = px; val x1 = w - px; val top = 47f; val bh = 10f
                fun x(t: Long): Float {
                    val m = ((t - startOfDay(0)) / 60_000L).toFloat()
                    return (x0 + (m - STRIP_FROM) / (STRIP_TO - STRIP_FROM) * (x1 - x0)).coerceIn(x0, x1)
                }
                fill(RectF(x0, top, x1, top + bh), TRACK, 2.5f)
                val now = System.currentTimeMillis()
                val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; pathEffect = DashPathEffect(floatArrayOf(3f, 2f), 0f) }
                planned(context).filter { it.window.end > now }.forEach {
                    dashed.color = it.kind.colour
                    c.drawRoundRect(RectF(x(maxOf(it.window.start, now)) + 0.6f, top + 0.6f, x(it.window.end) - 0.6f, top + bh - 0.6f), 1.5f, 1.5f, dashed)
                }
                runCatching { Day.blocks(context) }.getOrDefault(emptyList())
                    .filter { it.status != Day.Status.MISSED && it.kind != Kind.AWAY }.forEach {
                        val a = x(it.from); val b = maxOf(x(minOf(it.to, now)), a + 2f)
                        fill(RectF(a, top, b, top + bh), it.kind.colour, 2f)
                    }
                c.drawLine(x(now), top - 4f, x(now), top + bh + 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; strokeWidth = 1.6f })
                // Under the strip: what's next, on the side of the day away from it (left in the evening, right in the
                // morning), and the hours every four, except where "Next" sits.
                val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCFC6B4.toInt(); strokeWidth = 1f }
                val next = planned(context).firstOrNull { it.window.start > now }
                var nextBox: RectF? = null
                if (next != null) {
                    val lead = "Next · ${NowActivity.hhmm(next.window.start)} "
                    val lp = paint(MUTED, 9.5f); val kp = paint(MUTED, 9.5f)
                    val tw = lp.measureText(lead) + kp.measureText(next.kind.label)
                    val at = x(next.window.start)
                    // On the side away from it: later in the day on the left, in the morning on the right.
                    val left = if (at >= (x0 + x1) / 2f) x0 else x1 - tw
                    c.drawText(lead, left, 74f, lp)
                    c.drawText(next.kind.label, left + lp.measureText(lead), 74f, kp)
                    nextBox = RectF(left - 6f, 60f, left + tw + 6f, 80f)
                }
                listOf(5, 9, 13, 17, 21).forEach { hr ->
                    val tx = x0 + (hr * 60f - STRIP_FROM) / (STRIP_TO - STRIP_FROM) * (x1 - x0)
                    c.drawLine(tx, top + bh + 2f, tx, top + bh + 5f, tick)
                    val align = when (hr) { 5 -> Paint.Align.LEFT; 21 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                    val hp = paint(MUTED, 9f, align = align)
                    val lw = hp.measureText("00")
                    val lx = when (align) { Paint.Align.LEFT -> tx; Paint.Align.RIGHT -> tx - lw; else -> tx - lw / 2f }
                    if (nextBox?.let { RectF.intersects(it, RectF(lx, 64f, lx + lw, 76f)) } != true) c.drawText("%02d".format(hr), tx, 74f, hp)
                }
            }

            /** The day's last block: title and time on one line, one bar for the block, "Last thing for the day". */
            fun last(mode: String) {
                val name = NowActivity.NAMES[mode] ?: mode
                c.drawRect(px, 22.5f, px + 6.5f, 29f, Paint().apply { color = colourOf(mode) })
                c.drawText(name, px + 11f, 32f, paint(INK, 16f, display))
                c.drawText(till(mode), w - px, 31f, paint(INK2, 10f, align = Paint.Align.RIGHT))
                val win = window(context, mode) ?: return
                val frac = ((System.currentTimeMillis() - win.start).toFloat() / (win.end - win.start)).coerceIn(0f, 1f)
                fill(RectF(px, 43f, w - px, 49f), TRACK, 2f)
                fill(RectF(px, 43f, px + (w - 2 * px) * frac, 49f), colourOf(mode), 2f)
                c.drawText("Last thing for the day 🎉", px, 66f, paint(INK2, 10.5f))
            }

            /** Study time: under the head, a wide Start study and a small Prep. */
            fun studyButtons() {
                val top = 47f; val bottom = 77f
                val prepW = 62f
                fill(RectF(px, top, w - px - prepW - 7f, bottom), Kind.STUDY.colour, 9f)
                fill(RectF(w - px - prepW, top, w - px, bottom), Kind.PREP.colour, 9f)
                val cy = (top + bottom) / 2f
                c.drawCircle(px + 20f, cy, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF })
                c.drawText("▶", px + 20.9f, cy + 3.9f, paint(0xFFFFFFFF.toInt(), 10.5f, strong, Paint.Align.CENTER))
                c.drawText("Start study", px + 38f, cy + 4.3f, paint(0xFFFFFFFF.toInt(), 12.5f, strong))
                c.drawText("▶ Prep", w - px - prepW / 2f, cy + 4f, paint(0xFFFFFFFF.toInt(), 11f, strong, Paint.Align.CENTER))
            }

            /** Field time: "First talk" until he's tapped it, then "Done", then nothing. Top right, beside the title. */
            fun fieldPill() {
                val (start, end) = Field.marks(context, startOfDay(0))
                val label = when { start == null -> "▶ First talk"; end == null -> "■ Done"; else -> return }
                val fillC = if (start == null) Kind.FIELD.colour else INK
                val p = paint(if (start == null) 0xFFFFFFFF.toInt() else CREAM, 10.5f, strong, Paint.Align.CENTER)
                val r = RectF(w - px - FIELD_PILL_W, 11f, w - px, 37f)
                fill(r, fillC, 13f)
                c.drawText(label, r.centerX(), r.centerY() + 3.7f, p)
            }

            /** A pill on the right, centred: Stop, Review, Write. */
            fun pill(label: String, fillColour: Int, text: Int) {
                val p = paint(text, 11f, strong, Paint.Align.CENTER)
                val pw = p.measureText(label) + 26f
                val r = RectF(w - px - pw, h / 2f - 14f, w - px, h / 2f + 14f)
                fill(r, fillColour, 14f)
                c.drawText(label, r.centerX(), r.centerY() + 3.9f, p)
            }

            /** A session running: the JW Library ring, "Studying", the timer, JW Library minutes, Stop. */
            fun running() {
                val (kind, since) = Logbook.running(context)
                    ?: (if (previewing()) Kind.STUDY to (System.currentTimeMillis() - 42 * 60_000L) else return)
                val now = System.currentTimeMillis()
                val total = maxOf(1L, (now - since) / 60_000L)
                val jw = Usage.minutes(context, "org.jw.jwlibrary.mobile", since, now)
                val cy = h / 2f; val r = 16f; val cx = px + 20f
                val box = RectF(cx - r, cy - r, cx + r, cy + r)
                c.drawOval(box, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFFE6DCCB.toInt() })
                c.drawArc(box, -90f, maxOf(6f, 360f * (jw / total.toFloat()).coerceIn(0f, 1f)), false,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; color = kind.colour })
                val tx = cx + r + 11f
                kicker(if (kind == Kind.STUDY) "Studying" else "Meeting prep", kind.colour, tx, cy - 11f)
                c.drawText(NowActivity.left(now - since, suffix = false), tx, cy + 7f, paint(INK, 21f, display))
                c.drawText("JW Library $jw m", tx, cy + 19f, paint(INK2, 9.5f))
                pill("Stop", INK, CREAM)
            }

            /** From 20:01: "2 to check · 5 sure", Review. */
            fun review() {
                val blocks = runCatching { Day.blocks(context) }.getOrDefault(emptyList())
                val check = Day.toCheck(blocks).size; val sure = blocks.count { it.status == Day.Status.SURE }
                val cy = h / 2f
                kicker("Your day", MUTED, px, cy - 8f)
                var x = px; val y = cy + 13f
                fun part(n: Int, word: String) {
                    val big = paint(INK, 22f, display); c.drawText("$n", x, y, big); x += big.measureText("$n") + 5f
                    val small = paint(INK2, 11f); c.drawText(word, x, y, small); x += small.measureText(word) + 5f
                }
                part(check, "to check ·"); part(sure, "sure")
                pill("Review", INK, CREAM)
            }

            fun entry() {
                val cy = h / 2f
                kicker("Before anything", BIBLE, px, cy - 8f)
                c.drawText("Today's entry first", px, cy + 11f, paint(INK, 16f, display))
                pill("Write", BIBLE, 0xFFFFFFFF.toInt())
            }

            /** The night: the day, then today's numbers (only what the day had), fun apps under them. */
            fun night() {
                val late = minuteNow() < 5 * 60
                val day = startOfDay(if (late) -1 else 0)
                val end = day + 86_400_000L
                val blocks = runCatching { Day.blocks(context, day) }.getOrDefault(emptyList())
                val logged = Logbook.between(context, day, end)
                val plan = if (late) emptyList() else planned(context)
                val hours = { kind: Kind -> blocks.filter { it.kind == kind && it.status == Day.Status.SURE }.sumOf { it.to - it.from } / 3_600_000.0 }
                fun hrs(x: Double) = if (x < 0.05) "0 h" else if (x % 1.0 < 0.05) "${x.toInt()} h" else "%.1f h".format(x)
                val cells = mutableListOf<Pair<String, String>>()
                val studyH = logged.sumOf { it.to - it.from } / 3_600_000.0
                if (studyH > 0 || plan.any { it.kind == Kind.STUDY }) cells += "Study" to hrs(studyH)
                val fieldH = hours(Kind.FIELD)
                if (fieldH > 0 || plan.any { it.kind == Kind.FIELD }) cells += "Field" to hrs(fieldH)
                val bankM = (hours(Kind.BANK) * 60).toInt()
                if (bankM > 0 || plan.any { it.kind == Kind.BANK }) cells += "Kowane" to "$bankM m"
                if (!late) {
                    val keep = plan.filter { it.kind != Kind.WORK && it.window.end <= System.currentTimeMillis() }
                    val kept = keep.count { p -> blocks.any { b -> b.status == Day.Status.SURE && (b.kind == p.kind || (p.kind == Kind.STUDY && b.kind == Kind.PREP)) && b.from < p.window.end && b.to > p.window.start } }
                    if (keep.isNotEmpty()) cells += "Kept" to "$kept / ${keep.size}"
                }
                val dayName = java.text.SimpleDateFormat("EEEE", java.util.Locale.UK).format(java.util.Date(day))
                kicker(dayName, GOLD, px, 20f)
                c.drawText("till 05:00 · laptop offline", w - px, 20f, paint(NIGHT_SOFT, 9.5f, align = Paint.Align.RIGHT))
                val cw = (w - 2 * px) / maxOf(cells.size, 1)
                cells.forEachIndexed { i, (k, v) ->
                    val x = px + i * cw
                    kicker(k, NIGHT_SOFT, x, 36f)
                    val parts = v.split(" ", limit = 2)
                    val big = paint(0xFFEFE6D8.toInt(), 20f, display)
                    c.drawText(parts[0], x, 56f, big)
                    if (parts.size > 1) c.drawText(" " + parts[1], x + big.measureText(parts[0]), 56f, paint(NIGHT_SOFT, 10f))
                }
                if (cells.isEmpty()) c.drawText("Rest well.", px, 54f, paint(0xFFEFE6D8.toInt(), 16f, display))
                val fun_ = Usage.minutesByApp(context, day, end).filterKeys { ColourKeeper.isDrift(context, it) }.values.sum()
                c.drawText("Fun apps ${if (fun_ >= 60) "${fun_ / 60} h ${fun_ % 60}" else "$fun_ m"}", px, 73f, paint(NIGHT_SOFT, 9.5f))
            }
        }
    }
}
