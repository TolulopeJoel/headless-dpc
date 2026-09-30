package com.tolu.dpc

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** Drawing shared by the blocked screen and the widget: the real moon, and colour mixing for the moving skies. */
internal object SkyArt {

    /** Where the moon is in its month: 0 new, 0.5 full. From a known new moon (6 Jan 2000, 18:14 UTC). */
    fun moonPhase(now: Long = System.currentTimeMillis()): Float {
        val days = (now - 947_182_440_000L) / 86_400_000.0
        return ((days / 29.530588853) % 1.0).toFloat()
    }

    /** The share of the moon that is lit, 0..1. */
    fun moonLit(phase: Float): Float = (1 - cos(2 * PI * phase).toFloat()) / 2

    /**
     * The lit part: the outer edge on one side, and the terminator, an ellipse whose width shrinks to nothing at the
     * quarters. Waxing is lit on the right, waning on the left. Writes into `out` and returns it.
     */
    fun moonPath(cx: Float, cy: Float, r: Float, phase: Float, out: Path = Path()): Path {
        val k = cos(2 * PI * phase).toFloat()          // 1 new, 0 quarter, -1 full
        val a = abs(k) * r
        out.reset()
        out.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 180f, true)            // top, round the right edge
        out.arcTo(RectF(cx - a, cy - r, cx + a, cy + r), 90f, if (k > 0) -180f else 180f) // crescent right, gibbous left
        out.close()
        if (phase > 0.5f) out.transform(Matrix().apply { setScale(-1f, 1f, cx, cy) })
        return out
    }
}

/** Mixes two colours, `t` of the way from `a` to `b`. */
internal fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt() and 0xFF
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}
