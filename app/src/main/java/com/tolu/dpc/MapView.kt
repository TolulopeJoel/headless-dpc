package com.tolu.dpc

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos

/**
 * The little map on "Where were you?": his trail on plain ground, drawn from that trip's own location notes, with home
 * and the stop being asked about. No streets: "See in Google Maps" shows the spot with its street names. Nothing
 * leaves the phone to draw it.
 */
class MapView(context: Context, private val ui: Ui) : View(context) {

    private var home: Pair<Double, Double>? = null
    private var trail: List<Trail.Point> = emptyList()
    private var stops: List<Trail.Stop> = emptyList()
    private var asked: Trail.Stop? = null

    fun show(home: Pair<Double, Double>?, trail: List<Trail.Point>, stops: List<Trail.Stop>, asked: Trail.Stop?) {
        this.home = home; this.trail = trail; this.stops = stops; this.asked = asked
        invalidate()
    }

    private val ground = Paint().apply { color = 0xFFEAE5D8.toInt() }
    private val path = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = ui.ink; strokeWidth = ui.dpf(2f); pathEffect = DashPathEffect(floatArrayOf(ui.dpf(4f), ui.dpf(4f)), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.dpf(10.5f); typeface = ui.strong; color = ui.ink }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, ground)
        val pts = trail.map { it.lat to it.lon } + listOfNotNull(home) + listOfNotNull(asked?.let { it.lat to it.lon })
        if (pts.isEmpty()) return
        // Fit everything, at least 500 m across, with a margin.
        val s = pts.minOf { it.first }; val n = pts.maxOf { it.first }
        val wst = pts.minOf { it.second }; val e = pts.maxOf { it.second }
        val k = cos(Math.toRadians((s + n) / 2))
        val minSpan = 500.0 / 111_320.0
        val latSpan = maxOf(n - s, minSpan); val lonSpan = maxOf((e - wst) * k, minSpan)
        val scale = minOf(w / lonSpan, h / latSpan) / 1.25
        val cLat = (s + n) / 2; val cLon = (wst + e) / 2
        fun x(lon: Double) = (w / 2 + (lon - cLon) * k * scale).toFloat()
        fun y(lat: Double) = (h / 2 - (lat - cLat) * scale).toFloat()
        // The trail, the stops, home, and the stop in question.
        if (trail.size > 1) canvas.drawPath(Path().apply { trail.forEachIndexed { i, t -> if (i == 0) moveTo(x(t.lon), y(t.lat)) else lineTo(x(t.lon), y(t.lat)) } }, path)
        dot.color = ui.muted
        stops.filter { it != asked }.forEach { canvas.drawCircle(x(it.lon), y(it.lat), ui.dpf(4f), dot) }
        home?.let { (la, lo) ->
            dot.color = ui.ink; canvas.drawCircle(x(lo), y(la), ui.dpf(6f), dot)
            label.color = ui.ink2; canvas.drawText("home", x(lo) + ui.dpf(9f), y(la) + ui.dpf(4f), label)
        }
        asked?.let {
            dot.color = 0x40EB6834; canvas.drawCircle(x(it.lon), y(it.lat), ui.dpf(12f), dot)
            dot.color = 0xFFEB6834.toInt(); canvas.drawCircle(x(it.lon), y(it.lat), ui.dpf(6.5f), dot)
        }
    }
}
