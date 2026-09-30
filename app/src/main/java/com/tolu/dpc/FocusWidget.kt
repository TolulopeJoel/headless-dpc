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
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Home-screen widget, a 1×1 tile. Its real job is to be placed: Hiber doesn't freeze an app with a widget on the home
 * screen while the screen is on, which keeps the alarms and the tides working; each update also re-enforces the
 * schedule. So it only shows a ring on this part of the day's colours, counting down to the next change (or the
 * tide's return). A tap does the good thing: JW Library in the Bible hour, Àṣàrò while the entry is due.
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
        val ink: Int, val soft: Int, val accent: Int, val track: Int,
        val start: Int?, val end: Int?,
    ) {
        DAY(
            0xFF17263F.toInt(), 0xB317263F.toInt(), 0xFFC76A24.toInt(), 0x1F17263F, 7 * 60, 17 * 60 + 30),
        EVENING(
            0xFFFFFFFF.toInt(), 0xD9FFFFFF.toInt(), 0xFFE18F43.toInt(), 0x33FFFFFF, 17 * 60 + 30, 20 * 60),
        NIGHT(
            0xFFEFE6D8.toInt(), 0xC7EFE6D8.toInt(), 0xFFE8D6A0.toInt(), 0x26FFFFFF, 20 * 60, 5 * 60),
        MORNING(
            0xFFFFFFFF.toInt(), 0xE6FFFFFF.toInt(), 0xFFFFE2B8.toInt(), 0x40FFFFFF, 5 * 60, 7 * 60),
        ENTRY(
            0xFF17263F.toInt(), 0xB317263F.toInt(), 0xFFC76A24.toInt(), 0x1F17263F, null, null),
        TIDE(
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
            val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 64
            val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 64

            // Colour: in the day and evening the tile greys with the phone.
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
            val side = (minOf(wDp, hDp).coerceAtLeast(40) * density).toInt()

            return RemoteViews(context.packageName, R.layout.widget_focus).apply {
                setImageViewBitmap(R.id.focus_sky, desaturate(sky(look, minute, side, side), colour))
                setImageViewBitmap(R.id.focus_ring, ring(context, look, progress, label, (64 * density).toInt(), density))
                setContentDescription(R.id.focus_root, "Focus: $label")
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

        /** The background: this part of the day's gradient, at this minute. Nothing else, so the ring reads at a glance. */
        private fun sky(look: Look, minute: Int, w: Int, h: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val (top, bottom) = when (look) {
                Look.DAY -> 0xFFFBF6EC.toInt() to 0xFFEEDFC6.toInt()
                Look.EVENING -> Mood.EVENING.sky(minute)
                Look.NIGHT -> Mood.NIGHT.top to Mood.NIGHT.bottom
                Look.MORNING -> Mood.MORNING.sky(minute)
                Look.ENTRY -> Mood.ENTRY.top to Mood.ENTRY.bottom
                Look.TIDE -> Mood.TIDE.top to Mood.TIDE.bottom
            }
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP) }
            Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
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
