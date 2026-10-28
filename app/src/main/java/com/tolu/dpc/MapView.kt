package com.tolu.dpc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow

/**
 * The map on "Where were you?", in the Newsprint look of the weekly report (his pick, 14 Oct, "1B"): ruled paper, ink
 * roads with typewriter street names (stored in the app from OpenStreetMap, plus names read off Google Maps by hand, so
 * nothing about where he went leaves the phone), the route as red footprints, each stop a small tilted numbered stamp.
 * The minutes per stop are in the strip under the map (WhereActivity), not on it, so the map stays clear.
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

    // ── Zoom and move: pinch to zoom (1× to 8×), drag to move, double-tap to fit the trip again ──

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private val pinch = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            val next = (zoom * d.scaleFactor).coerceIn(1f, 8f)
            val f = next / zoom
            // Keep the point under the fingers where it is.
            panX = (panX - (d.focusX - width / 2f)) * f + (d.focusX - width / 2f)
            panY = (panY - (d.focusY - height / 2f)) * f + (d.focusY - height / 2f)
            zoom = next; clampPan(); invalidate(); return true
        }
    })

    private val gestures = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent) = true
        override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, dx: Float, dy: Float): Boolean {
            panX -= dx; panY -= dy; clampPan(); invalidate(); return true
        }
        override fun onDoubleTap(e: android.view.MotionEvent): Boolean { zoom = 1f; panX = 0f; panY = 0f; invalidate(); return true }
    })

    /** Never drag the trip entirely out of view. */
    private fun clampPan() {
        val lim = (zoom - 1f) * maxOf(width, height) / 2f + width / 3f
        panX = panX.coerceIn(-lim, lim); panY = panY.coerceIn(-lim, lim)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)      // the page doesn't scroll while he's on the map
        pinch.onTouchEvent(event)
        if (!pinch.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL)
            parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }

    init {
        if (roads == null) kotlin.concurrent.thread {
            roads = runCatching { Roads.load(context) }.getOrNull() ?: Roads(emptyList(), emptyList()); post { invalidate() }
        }
    }

    private val elite = context.resources.getFont(R.font.special_elite)
    private val anton = context.resources.getFont(R.font.anton)
    private val ground = Paint().apply { color = PAPER }
    private val ruled = Paint().apply { color = 0x09151515; strokeWidth = 1f }
    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val roadName = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; typeface = elite }
    private val print = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.strong; textSize = ui.dpf(10.5f) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, ground)
        run { var ry = 0f; while (ry < h) { c.drawLine(0f, ry, w, ry, ruled); ry += ui.dpf(3f) } }
        taken.clear(); pending.clear()
        val homeP = home
        val places = Places.all(context).filter { it.sure && it.kind != Kind.FIELD }
        val pts = trail.map { it.lat to it.lon } + listOfNotNull(homeP) + listOfNotNull(asked?.let { it.lat to it.lon })
        if (pts.isEmpty()) return

        // Fit the trip (and home), at least 400 m across, with room around it.
        val s = pts.minOf { it.first }; val n = pts.maxOf { it.first }
        val wst = pts.minOf { it.second }; val e = pts.maxOf { it.second }
        val k = cos(Math.toRadians((s + n) / 2))
        val mPerDeg = 111_320.0
        // Each direction fitted on its own, so a tall trip uses the map's height (with room for the stamps).
        val spanNS = maxOf((n - s) * mPerDeg, 400.0); val spanEW = maxOf((e - wst) * k * mPerDeg, 400.0)
        val scale = minOf(w / (spanEW * 1.25), h / (spanNS * 1.25)).toFloat() * zoom      // px per metre, with his pinch
        val cLat = (s + n) / 2; val cLon = (wst + e) / 2
        fun x(lon: Double) = (w / 2 + panX + (lon - cLon) * k * mPerDeg * scale).toFloat()
        fun y(lat: Double) = (h / 2 + panY - (lat - cLat) * mPerDeg * scale).toFloat()

        // 1. Keep street names off the stamps, home and known places.
        val stamp = ui.dpf(17f)
        stops.forEach { reserve(x(it.lon), y(it.lat), stamp * 0.75f) }
        homeP?.let { (la, lo) -> reserve(x(lo), y(la), ui.dpf(7f)) }
        places.forEach { reserve(x(it.lon), y(it.lat), ui.dpf(7f)) }
        homeP?.let { (la, lo) -> place("home", x(lo), y(la) + ui.dpf(17f), TEAL, ui.dpf(7f)) }
        places.forEach { place(it.kind.label.lowercase(), x(it.lon), y(it.lat) - ui.dpf(11f), INK, ui.dpf(7f)) }

        // 2. The roads in ink: main roads black, streets brown-grey, lanes faint; thin, as on newsprint.
        roads?.let { r ->
            val view = RectF(-30f, -30f, w + 30f, h + 30f)
            val paths = r.ways.mapNotNull { way ->
                val path = Path(); var inView = false
                way.lat.indices.forEach { i -> val px = x(way.lon[i]); val py = y(way.lat[i]); if (view.contains(px, py)) inView = true
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                if (inView) Triple(way, path, way.cls) else null
            }
            val colours = intArrayOf(INK, INK, 0xFF8A8478.toInt(), 0xFFC4BCAE.toInt())
            val base = floatArrayOf(2.2f, 1.6f, 0.8f, 0.5f)
            val grow = zoom.coerceIn(1f, 3f)                               // a little bolder as he zooms in
            for (cls in 3 downTo 0) paths.filter { it.third == cls }.forEach { (_, path, _) ->
                road.color = colours[cls]; road.strokeWidth = ui.dpf(base[cls] * grow); c.drawPath(path, road) }
            // Names: each street once, on its longest stretch in view, shortened, slid along to the first free spot.
            roadName.textSize = ui.dpf(8.5f)
            val measure = android.graphics.PathMeasure()
            val byName = paths.filter { it.first.name >= 0 }.groupBy { it.first.name }
            byName.entries.sortedBy { (_, ws) -> ws.minOf { it.third } }.forEach { (nameIdx, ws) ->
                val nm = shortName(r.names[nameIdx])
                val tw = roadName.measureText(nm)
                val (way, path, _) = ws.maxByOrNull { measure.setPath(it.second, false); measure.length } ?: return@forEach
                measure.setPath(path, false); val len = measure.length
                if (len < tw + ui.dpf(12f)) return@forEach
                val forward = x(way.lon.last()) >= x(way.lon.first())
                val along = if (forward) path else Path().apply { way.lat.indices.reversed().forEachIndexed { j, i -> if (j == 0) moveTo(x(way.lon[i]), y(way.lat[i])) else lineTo(x(way.lon[i]), y(way.lat[i])) } }
                measure.setPath(along, false)
                val pos = FloatArray(2)
                val room = len - tw
                for (off in listOf(0.5f, 0.3f, 0.7f, 0.15f, 0.85f, 0f, 1f).map { it * room }) {
                    measure.getPosTan(off + tw / 2, pos, null)
                    val box = RectF(pos[0] - tw / 2 - ui.dpf(3f), pos[1] - ui.dpf(8f), pos[0] + tw / 2 + ui.dpf(3f), pos[1] + ui.dpf(8f))
                    if (box.left < 0 || box.right > w || box.top < 0 || box.bottom > h || taken.any { RectF.intersects(it, box) }) continue
                    taken += box
                    c.drawTextOnPath(nm, along, off, -ui.dpf(2.5f), roadName)
                    break
                }
            }
        }

        // 3. Home: a teal diamond, and its fence, faint.
        homeP?.let { (la, lo) ->
            val hx = x(lo); val hy = y(la)
            p.style = Paint.Style.STROKE; p.strokeWidth = ui.dpf(1f); p.color = 0x661C6E70
            p.pathEffect = DashPathEffect(floatArrayOf(ui.dpf(3f), ui.dpf(3f)), 0f)
            c.drawCircle(hx, hy, Away.radius(context) * scale, p); p.pathEffect = null
            p.style = Paint.Style.FILL; p.color = TEAL
            c.save(); c.rotate(45f, hx, hy); val d = ui.dpf(5f); c.drawRect(hx - d, hy - d, hx + d, hy + d, p); c.restore()
        }

        // 4. Places he knows: a small ink square.
        places.forEach { pl ->
            p.style = Paint.Style.FILL; p.color = INK
            val px = x(pl.lon); val py = y(pl.lat); val d = ui.dpf(4f)
            c.drawRect(px - d, py - d, px + d, py + d, p)
        }

        // 5. The route as footprints: left, right, left, toes the way he walked. Time spent standing at a stop leaves
        //    no prints (those notes are skipped), so the prints only show the walking.
        if (trail.size > 1) {
            val walk = trail.filter { t -> stops.none { st -> t.at in st.from..st.to } }
            val path = (listOf(trail.first()) + walk + listOf(trail.last())).map { x(it.lon) to y(it.lat) }
            val gap = ui.dpf(11f); var carry = gap / 2; var left = true
            for (i in 1 until path.size) {
                val (x1, y1) = path[i - 1]; val (x2, y2) = path[i]
                val dx = x2 - x1; val dy = y2 - y1; val len = kotlin.math.hypot(dx, dy)
                if (len < 0.5f) continue
                val ang = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
                var d = carry
                while (d < len) {
                    val f = d / len; val side = if (left) -1f else 1f
                    val px = x1 + dx * f - dy / len * ui.dpf(2.6f) * side
                    val py = y1 + dy * f + dx / len * ui.dpf(2.6f) * side
                    footprint(c, px, py, ang)
                    left = !left; d += gap
                }
                carry = d - len
            }
        }

        // 6. Stops: small tilted stamps, numbered in order, each in its own paper; the one being asked about gets a
        //    red outline round it.
        stops.forEachIndexed { i, st ->
            val px = x(st.lon); val py = y(st.lat)
            val (bg, fg) = PAPERS[i % PAPERS.size]
            c.save(); c.rotate(TILT[i % TILT.size], px, py)
            val hs = stamp / 2
            if (st == asked) { p.style = Paint.Style.STROKE; p.strokeWidth = ui.dpf(2f); p.color = RED
                c.drawRect(px - hs - ui.dpf(4f), py - hs - ui.dpf(4f), px + hs + ui.dpf(4f), py + hs + ui.dpf(4f), p) }
            p.style = Paint.Style.FILL; p.color = 0x4D151515; c.drawRect(px - hs + ui.dpf(1.5f), py - hs + ui.dpf(1.5f), px + hs + ui.dpf(1.5f), py + hs + ui.dpf(1.5f), p)
            p.color = bg; c.drawRect(px - hs, py - hs, px + hs, py + hs, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = ui.dpf(1.2f); p.color = INK; c.drawRect(px - hs, py - hs, px + hs, py + hs, p)
            text.style = Paint.Style.FILL; text.typeface = anton; text.textSize = ui.dpf(12f); text.color = fg; text.textAlign = Paint.Align.CENTER
            c.drawText("${i + 1}", px, py + ui.dpf(4.3f), text)
            c.restore()
        }

        // 7. Labels (home, places), on top.
        pending.forEach { (s, lx, ly, color) -> drawLabel(s, lx, ly, color) }

        // 8. A scale bar, bottom left, on a tape strip.
        val target = 90f / scale
        val nice = 10f.pow(log10(target).toInt()).let { b -> listOf(1f, 2f, 5f, 10f).map { it * b }.last { it <= target.coerceAtLeast(b) } }
        val bx = ui.dpf(12f); val by = h - ui.dpf(14f)
        text.typeface = elite; text.textSize = ui.dpf(10f); text.textAlign = Paint.Align.LEFT
        val lbl = if (nice >= 1000) "${(nice / 1000).toInt()} km" else "${nice.toInt()} m"
        p.style = Paint.Style.FILL; p.color = TAPE
        c.drawRect(RectF(bx - ui.dpf(6f), by - ui.dpf(11f), bx + nice * scale + text.measureText(lbl) + ui.dpf(14f), by + ui.dpf(7f)), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = ui.dpf(1.6f); p.color = INK
        c.drawLine(bx, by, bx + nice * scale, by, p)
        c.drawLine(bx, by - ui.dpf(4f), bx, by, p); c.drawLine(bx + nice * scale, by - ui.dpf(4f), bx + nice * scale, by, p)
        text.style = Paint.Style.FILL; text.color = INK
        c.drawText(lbl, bx + nice * scale + ui.dpf(6f), by + ui.dpf(3.5f), text)
    }

    /** One footprint at (x, y), toes pointing along `angle` (degrees): heel, sole and three toes. */
    private fun footprint(c: Canvas, x: Float, y: Float, angle: Float) {
        c.save(); c.translate(x, y); c.rotate(angle + 90f)
        val u = ui.dpf(1f) * zoom.coerceIn(1f, 1.8f)
        c.drawOval(RectF(-1.6f * u, -3.8f * u, 1.6f * u, 1.0f * u), print)       // sole
        c.drawOval(RectF(-1.15f * u, 1.3f * u, 1.15f * u, 3.8f * u), print)      // heel
        c.drawCircle(-1.1f * u, -4.6f * u, 0.45f * u, print)
        c.drawCircle(-0.2f * u, -4.9f * u, 0.5f * u, print)
        c.drawCircle(0.8f * u, -4.7f * u, 0.45f * u, print)
        c.restore()
    }

    /** "Ojekunle Street" → "Ojekunle St", so more names fit along short streets. */
    private fun shortName(n: String) = n
        .replace(Regex("\\bStreet\\b"), "St").replace(Regex("\\bRoad\\b"), "Rd").replace(Regex("\\bAvenue\\b"), "Ave")
        .replace(Regex("\\bCrescent\\b"), "Cres").replace(Regex("\\bClose\\b"), "Cl").replace(Regex("\\bExpressway\\b"), "Expy")

    /** Spots taken by markers, so labels and street names keep off them. */
    private fun reserve(x: Float, y: Float, r: Float) { taken += RectF(x - r, y - r, x + r, y + r) }

    /** Labels chosen this frame, drawn last. */
    private val pending = mutableListOf<Quadruple>()
    private data class Quadruple(val s: String, val x: Float, val y: Float, val color: Int)

    /**
     * Find a free spot for a label near (x, y): below, above, right, left, then further out, and reserve it. `clear`
     * is how far its marker reaches, so the alternatives keep off the marker. Left out if nothing is free.
     */
    private fun place(s: String, x: Float, y: Float, color: Int, clear: Float) {
        text.typeface = elite; text.textAlign = Paint.Align.CENTER; text.textSize = ui.dpf(10f)
        val tw = text.measureText(s); val th = ui.dpf(11f)
        val cy = y - clear - ui.dpf(12f)                       // the marker's centre, roughly
        val spots = listOf(x to y, x to cy - clear - ui.dpf(4f), x + clear + tw / 2 + ui.dpf(6f) to cy + ui.dpf(4f),
            x - clear - tw / 2 - ui.dpf(6f) to cy + ui.dpf(4f), x to y + ui.dpf(13f))
        val spot = spots.firstOrNull { (sx, sy) ->
            val r = RectF(sx - tw / 2 - 2, sy - th, sx + tw / 2 + 2, sy + 3)
            r.left >= 0 && r.right <= width && r.top >= 0 && r.bottom <= height && taken.none { RectF.intersects(it, r) }
        } ?: return
        taken += RectF(spot.first - tw / 2 - 2, spot.second - th, spot.first + tw / 2 + 2, spot.second + 3)
        pending += Quadruple(s, spot.first, spot.second, color)
    }

    /** A small label with a cream halo, so it reads over the roads and the route. */
    private fun drawLabel(s: String, x: Float, y: Float, color: Int) {
        text.typeface = elite; text.textAlign = Paint.Align.CENTER; text.textSize = ui.dpf(10f)
        text.style = Paint.Style.STROKE; text.strokeWidth = ui.dpf(3.5f); text.color = PAPER; c(s, x, y)
        text.style = Paint.Style.FILL; text.color = color; c(s, x, y)
    }

    /** Space taken this frame by markers, labels and street names. */
    private val taken = mutableListOf<RectF>()

    /** The stored roads: names, and each way's class (0 main … 3 service) and points. */
    class Roads(val names: List<String>, val ways: List<Way>) {
        class Way(val name: Int, val cls: Int, val lat: DoubleArray, val lon: DoubleArray)

        companion object {
            /** assets/roads.bin, written by tools/roads.py. */
            fun load(context: Context): Roads = java.io.DataInputStream(context.assets.open("roads.bin").buffered()).use { inp ->
                check(inp.readInt() == 0x524F4144)
                val names = List(inp.readInt()) { inp.readUTF() }
                val ways = List(inp.readInt()) {
                    val nm = inp.readShort().toInt(); val cls = inp.readByte().toInt(); val n = inp.readShort().toInt()
                    val lat = DoubleArray(n); val lon = DoubleArray(n)
                    var la = inp.readInt(); var lo = inp.readInt()
                    lat[0] = la / 1e5; lon[0] = lo / 1e5
                    for (i in 1 until n) { la += inp.readShort(); lo += inp.readShort(); lat[i] = la / 1e5; lon[i] = lo / 1e5 }
                    Way(nm, cls, lat, lon)
                }
                Roads(names, ways)
            }
        }
    }

    companion object {
        @Volatile private var roads: Roads? = null
        const val PAPER = 0xFFECE8DF.toInt()
        const val INK = 0xFF151515.toInt()
        const val RED = 0xFFC8352A.toInt()
        const val TEAL = 0xFF1C6E70.toInt()
        const val TAPE = 0xFFF3E6BF.toInt()
        /** Each stop's paper and ink, in order, as in the weekly report's cut-outs; the strip under the map uses them too. */
        val PAPERS = listOf(INK to PAPER, 0xFFD79A1E.toInt() to INK, RED to Color.WHITE, TEAL to Color.WHITE, TAPE to INK, INK to 0xFFD79A1E.toInt())
        val TILT = floatArrayOf(-6f, 4f, -3f, 5f, -4f, 3f)
    }

    private var canvasRef: Canvas? = null
    private fun c(s: String, x: Float, y: Float) { canvasRef?.drawText(s, x, y, text) }

    override fun draw(canvas: Canvas) { canvasRef = canvas; super.draw(canvas) }
}
