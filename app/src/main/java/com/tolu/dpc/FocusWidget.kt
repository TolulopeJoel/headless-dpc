package com.tolu.dpc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Home-screen widget. Hiber never freezes an app with a placed widget, so placing this one protects the alarms, and
 * each update also re-enforces the schedule. It shows the part of the day the way the blocked screen does: the sky
 * (the real moon at night, the sun at dawn and dusk), a ring counting down to the next change, and what it means.
 */
class FocusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        ScheduleReceiver.enforce(context)   // also redraws the widget
        KeepAliveService.start(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, widgetId: Int, options: Bundle) {
        refresh(context)
    }

    /** One look per part of the day. `start`/`end` bound the ring in minutes after midnight; null shows the pencil. */
    private enum class Look(
        val eyebrow: String, val title: String, val status: String,
        val ink: Int, val soft: Int, val accent: Int, val track: Int,
        val start: Int?, val end: Int?,
    ) {
        DAY("OPEN", "All clear.", "Slack locks at 5:30PM",
            0xFF17263F.toInt(), 0xB317263F.toInt(), 0xFFC76A24.toInt(), 0x1F17263F, 7 * 60, 17 * 60 + 30),
        EVENING("EVENING", "Work's done.", "Everything locks at 9PM",
            0xFFFFFFFF.toInt(), 0xD9FFFFFF.toInt(), 0xFFE18F43.toInt(), 0x33FFFFFF, 17 * 60 + 30, 21 * 60),
        NIGHT("NIGHT", "Rest, Tolu.", "JW Library and jw.org at 5AM",
            0xFFEFE6D8.toInt(), 0xC7EFE6D8.toInt(), 0xFFE8D6A0.toInt(), 0x26FFFFFF, 21 * 60, 5 * 60),
        MORNING("BIBLE HOUR", "Bible first.", "Everything opens at 7AM",
            0xFFFFFFFF.toInt(), 0xE6FFFFFF.toInt(), 0xFFFFE2B8.toInt(), 0x40FFFFFF, 5 * 60, 7 * 60),
        ENTRY("BEFORE ANYTHING", "Entry first.", "Write it and your phone opens",
            0xFF17263F.toInt(), 0xB317263F.toInt(), 0xFFC76A24.toInt(), 0x1F17263F, null, null),
        TIDE("LOW TIDE", "Tide's out.", "Chrome and co. come back by themselves",
            0xFFFFFFFF.toInt(), 0xD9FFFFFF.toInt(), 0xFF8FE3D6.toInt(), 0x33FFFFFF, null, null);

        fun progress(minute: Int): Float {
            val s = start ?: return 1f
            val e = end ?: return 1f
            val total = (e - s + 1440) % 1440
            return ((minute - s + 1440) % 1440).coerceAtMost(total) / total.toFloat()
        }

        fun left(minute: Int): Int {
            val s = start ?: return 0
            val e = end ?: return 0
            val total = (e - s + 1440) % 1440
            return total - ((minute - s + 1440) % 1440).coerceAtMost(total)
        }
    }

    companion object {
        private const val ASARO = "com.asaro.meditation"
        private const val JW_LIBRARY = "org.jw.jwlibrary.mobile"

        /** Show the current part of the day on every placed widget. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FocusWidget::class.java))
            if (ids.isEmpty()) return
            val state = ScheduleReceiver.currentState(context)
            val preview = ScheduleReceiver.previewMood()?.let { name -> Look.entries.firstOrNull { it.name == name } }
            val look = preview ?: when {
                state.gated -> Look.ENTRY
                state.tide -> Look.TIDE
                state.phase == ScheduleReceiver.Phase.MORNING -> Look.MORNING
                state.phase == ScheduleReceiver.Phase.EVENING -> Look.EVENING
                state.phase == ScheduleReceiver.Phase.NIGHT -> Look.NIGHT
                else -> Look.DAY
            }
            val now = Calendar.getInstance()
            val minute = if (preview != null && ScheduleReceiver.previewMinute >= 0) ScheduleReceiver.previewMinute
                else now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            ids.forEach { id -> manager.updateAppWidget(id, views(context, manager, id, look, minute)) }
        }

        private fun views(context: Context, manager: AppWidgetManager, id: Int, look: Look, minute: Int): RemoteViews {
            val density = context.resources.displayMetrics.density
            // Launchers report sizes loosely (HiOS reports too small); the sky is centre-cropped, so only its shape matters.
            val options = manager.getAppWidgetOptions(id)
            val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 190
            val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 94

            // Colour: in the day and evening the widget's sky drains with the phone's, and says how to get it back.
            val colour = if (look == Look.DAY || look == Look.EVENING) ColourKeeper.colour() else 1f
            // The ring: this part of the day, or the tide coming back in.
            val (tStart, tUntil) = ColourKeeper.tideWindow(context)
            val nowMs = System.currentTimeMillis()
            val tideLeft = ((tUntil - nowMs + 59_999) / 60_000).toInt().coerceAtLeast(0)
            val progress = if (look == Look.TIDE && tUntil > tStart) ((nowMs - tStart).toFloat() / (tUntil - tStart)).coerceIn(0f, 1f) else look.progress(minute)
            val left = look.left(minute)
            val label = when {
                look == Look.TIDE -> "${tideLeft}m"
                look.start == null -> "✎"
                left >= 60 -> "${left / 60}h ${left % 60}m"
                else -> "${left}m"
            }
            val status = when {
                look == Look.TIDE -> "Back in ${tideLeft}m · JW Library is open"
                colour <= 0f -> "All grey. Put it down to bring the colour back"
                colour < 1f -> "Colour ${(colour * 100).toInt()}% · put it down to refill"
                else -> look.status
            }
            val skyArt = desaturate(sky(look, minute, (wDp * density).toInt(), (hDp * density).toInt(), density), colour)

            return RemoteViews(context.packageName, R.layout.widget_focus).apply {
                setImageViewBitmap(R.id.focus_sky, skyArt)
                setImageViewBitmap(R.id.focus_ring, ring(context, look, progress, label, (60 * density).toInt(), density))
                setTextViewText(R.id.focus_eyebrow, look.eyebrow)
                setImageViewBitmap(R.id.focus_title, title(context, look, (30 * density).toInt()))
                setContentDescription(R.id.focus_title, look.title)
                setTextViewText(R.id.focus_status, status)
                setTextColor(R.id.focus_eyebrow, look.soft)
                setTextColor(R.id.focus_status, look.soft)
                // A tap does the good thing: the Bible in the Bible hour, the entry while it's due.
                setOnClickPendingIntent(R.id.focus_root, tap(context, look))
            }
        }

        private fun tap(context: Context, look: Look): PendingIntent? {
            val intent = when (look) {
                Look.MORNING -> context.packageManager.getLaunchIntentForPackage(JW_LIBRARY)
                Look.ENTRY -> Intent(Intent.ACTION_VIEW, Uri.parse("asaro://addEntry")).setPackage(ASARO)
                else -> null
            } ?: return null
            return PendingIntent.getActivity(context, look.ordinal, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** The background: the blocked screen's sky for this part of the day, at this minute. */
        private fun sky(look: Look, minute: Int, w: Int, h: Int, d: Float): Bitmap {
            val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val (top, bottom) = when (look) {
                Look.DAY -> 0xFFFBF6EC.toInt() to 0xFFEEDFC6.toInt()
                Look.EVENING -> Mood.EVENING.sky(minute)
                Look.NIGHT -> Mood.NIGHT.top to Mood.NIGHT.bottom
                Look.MORNING -> Mood.MORNING.sky(minute)
                Look.ENTRY -> Mood.ENTRY.top to Mood.ENTRY.bottom
                Look.TIDE -> Mood.TIDE.top to Mood.TIDE.bottom
            }
            p.shader = LinearGradient(0f, 0f, w * 0.35f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null

            when (look) {
                Look.NIGHT -> {
                    // A fixed scatter of stars, brighter towards the right, and tonight's moon.
                    val rnd = Random(930)
                    repeat(60) {
                        val x = rnd.nextFloat()
                        val y = rnd.nextFloat()
                        val s = rnd.nextFloat()
                        // The words sit in the middle; the stars keep to the right, round the moon.
                        if (x < 0.7f) return@repeat
                        p.color = 0xFFFFFFFF.toInt()
                        p.alpha = (40 + 170 * s * (0.4f + 0.6f * x)).toInt()
                        c.drawCircle(x * w, y * h, (0.5f + 1.1f * s) * d, p)
                    }
                    val mr = (h * 0.16f).coerceAtMost(17 * d)   // the same moon however tall the widget is
                    val mx = w - mr * 2.1f
                    val my = mr * 1.9f
                    val phase = SkyArt.moonPhase()
                    p.shader = RadialGradient(mx, my, mr * (1.8f + 2f * SkyArt.moonLit(phase)), 0x40E8D6A0, 0x00E8D6A0, Shader.TileMode.CLAMP)
                    c.drawCircle(mx, my, mr * 4f, p)
                    p.shader = null
                    p.color = 0x24EFE2B8
                    c.drawCircle(mx, my, mr, p)
                    p.color = 0xFFEFE2B8.toInt()
                    c.drawPath(SkyArt.moonPath(mx, my, mr, phase), p)
                }
                Look.MORNING, Look.EVENING -> {
                    // The sun low on the right: rising through the Bible hour, setting through the evening.
                    val mood = if (look == Look.MORNING) Mood.MORNING else Mood.EVENING
                    val t = mood.skyProgress(minute)
                    val up = if (look == Look.MORNING) t else 1f - t
                    val cx = w * 0.82f
                    val cy = h * (1.35f - 0.75f * up)
                    val core = if (look == Look.MORNING) 0xFFFFD28A.toInt() else 0xFFF3A25B.toInt()
                    p.shader = RadialGradient(cx, cy, h * 1.4f, intArrayOf(0x70FFD28A, 0x24FFB36B, 0x00FFB36B), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                    c.drawCircle(cx, cy, h * 1.4f, p)
                    p.shader = RadialGradient(cx, cy, h * 0.34f, intArrayOf(core, core, 0x00FFFFFF), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
                    c.drawCircle(cx, cy, h * 0.34f, p)
                    p.shader = null
                }
                Look.DAY -> {
                    // A soft daytime sun in the corner.
                    val cx = w * 0.9f
                    val cy = h * 0.12f
                    p.shader = RadialGradient(cx, cy, h * 1.1f, intArrayOf(0x55FFD28A, 0x18FFD28A, 0x00FFD28A), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                    c.drawCircle(cx, cy, h * 1.1f, p)
                    p.shader = null
                }
                Look.TIDE -> {
                    // Low water across the bottom, in three layers.
                    listOf(0x5538B2AC to 0.72f, 0x6628908F to 0.8f, 0x99125E68.toInt() to 0.88f).forEachIndexed { i, (colour, level) ->
                        val wave = android.graphics.Path()
                        wave.moveTo(0f, h.toFloat())
                        var x = 0f
                        while (x <= w + 6) {
                            wave.lineTo(x, h * level + 4 * d * sin(x / w * 5f * PI.toFloat() + i * 1.7f))
                            x += 6f
                        }
                        wave.lineTo(w.toFloat(), h.toFloat())
                        wave.close()
                        p.color = colour
                        c.drawPath(wave, p)
                    }
                }
                Look.ENTRY -> {
                    // Àṣàrò's notebook: ruled lines and a margin.
                    p.strokeWidth = d
                    p.color = 0x1717263F
                    var y = 18 * d
                    while (y < h) { c.drawLine(0f, y, w.toFloat(), y, p); y += 18 * d }
                    p.color = 0x40C76A24
                    c.drawLine(8 * d, 0f, 8 * d, h.toFloat(), p)
                }
            }
            return bmp
        }

        /** The sky at `colour` saturation: 1 leaves it as it is, 0 is grey. */
        private fun desaturate(bmp: Bitmap, colour: Float): Bitmap {
            if (colour >= 1f) return bmp
            val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(colour.coerceIn(0f, 1f)) }) }
            Canvas(out).drawBitmap(bmp, 0f, 0f, paint)
            return out
        }

        /** The title in Fraunces, drawn exactly as tall as its slot so the launcher never has to scale it up. */
        private fun title(context: Context, look: Look, height: Int): Bitmap {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = context.resources.getFont(R.font.fraunces_black)
                color = look.ink
                textSize = height * 0.78f
            }
            val fm = paint.fontMetrics
            val width = paint.measureText(look.title).toInt() + 4
            val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height, Bitmap.Config.ARGB_8888)
            val baseline = (height - (fm.descent - fm.ascent)) / 2 - fm.ascent
            Canvas(bmp).drawText(look.title, 0f, baseline, paint)
            return bmp
        }

        /** The ring: how much of this part of the day has gone, a tip where it's got to, and the countdown inside. */
        private fun ring(context: Context, look: Look, progress: Float, label: String, size: Int, d: Float): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val stroke = 5.5f * d
            val inset = stroke / 2 + 4 * d
            val box = RectF(inset, inset, size - inset, size - inset)
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = look.track }
            c.drawOval(box, track)

            val sweep = 360f * progress
            if (sweep > 16f) {
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke * 2.4f; strokeCap = Paint.Cap.ROUND; color = look.accent; alpha = 40 }
                val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = look.accent }
                c.drawArc(box, -90f, sweep, false, glow)
                c.drawArc(box, -90f, sweep, false, arc)
            }
            val a = (-90 + sweep) * (PI.toFloat() / 180f)
            val r = box.width() / 2
            val tip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.accent }
            c.drawCircle(size / 2f + r * cos(a), size / 2f + r * sin(a), stroke * 0.9f, tip)

            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = context.resources.getFont(R.font.fraunces_black)
                color = look.ink
                textAlign = Paint.Align.CENTER
                textSize = 15 * d
            }
            val room = box.width() - 12 * d
            val wide = text.measureText(label)
            if (wide > room) text.textSize *= room / wide
            c.drawText(label, size / 2f, size / 2f + text.textSize * 0.35f, text)
            return bmp
        }
    }
}
