package com.tolu.dpc

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Minutes per app and when the screen went dark, for the review and the weekly report. BlockScreenService already sees
 * which app is in front and when the screen goes on or off; this writes those down as stretches, one line each, in a
 * file per day ("pkg,start,end" and "off,at" / "on,at"). Nothing about what's inside an app is kept. Five weeks kept.
 */
object Usage {
    private const val DIR = "usage"
    private const val KEEP_DAYS = 35
    /** Shells that come and go over whatever is open (same as ColourKeeper's). */
    private val OVERLAYS = setOf("com.android.systemui", "com.tolu.dpc", "android")

    @Volatile private var front: String? = null
    @Volatile private var since = 0L
    @Volatile private var screenOn = true

    fun onForeground(context: Context, pkg: String?) {
        if (pkg == null || pkg in OVERLAYS || pkg == front) return
        close(context)
        front = pkg
        since = System.currentTimeMillis()
    }

    fun onScreen(context: Context, on: Boolean) {
        if (on == screenOn) return
        screenOn = on
        val now = System.currentTimeMillis()
        if (!on) close(context)
        else since = now
        append(context, now, "${if (on) "on" else "off"},$now")
    }

    /** Close the stretch in front (on screen off, switching apps, or every few minutes so a freeze loses little). */
    fun close(context: Context) {
        val pkg = front ?: return
        val now = System.currentTimeMillis()
        if (screenOn && now - since >= 5_000L) append(context, since, "$pkg,$since,$now")
        since = now
    }

    /** Called on each 30-second tick: closes the stretch every five minutes. */
    private var lastFlush = 0L
    fun tick(context: Context) {
        if (SystemClock.elapsedRealtime() - lastFlush < 5 * 60_000L) return
        lastFlush = SystemClock.elapsedRealtime()
        close(context)
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    data class Stretch(val pkg: String, val from: Long, val to: Long)

    /** Stretches overlapping [from, to), clipped to it. */
    fun stretches(context: Context, from: Long, to: Long): List<Stretch> = days(from, to).flatMap { lines(context, it) }
        .mapNotNull { l -> l.split(",").takeIf { it.size == 3 }?.let { Stretch(it[0], it[1].toLong(), it[2].toLong()) } }
        .filter { it.from < to && it.to > from }.map { it.copy(from = maxOf(it.from, from), to = minOf(it.to, to)) }

    /** Minutes per app over [from, to). */
    fun minutesByApp(context: Context, from: Long, to: Long): Map<String, Int> =
        stretches(context, from, to).groupBy { it.pkg }.mapValues { (_, s) -> (s.sumOf { it.to - it.from } / 60_000L).toInt() }.filterValues { it > 0 }

    fun minutes(context: Context, pkg: String, from: Long, to: Long) = minutesByApp(context, from, to)[pkg] ?: 0

    /**
     * The night that began on the day starting at `dayStart`: the longest stretch with the screen off between 19:00 that
     * evening and 11:00 the next morning. (dark, light), or null if it can't tell.
     */
    fun night(context: Context, dayStart: Long): Pair<Long, Long>? {
        val from = dayStart + 19 * 3_600_000L
        val to = dayStart + 35 * 3_600_000L
        val events = days(from, to).flatMap { lines(context, it) }
            .mapNotNull { l -> l.split(",").takeIf { it.size == 2 }?.let { (it[0] == "on") to it[1].toLong() } }
            .filter { it.second in from until to }.sortedBy { it.second }
        var best: Pair<Long, Long>? = null
        var offAt: Long? = null
        for ((on, at) in events) {
            if (!on) { if (offAt == null) offAt = at }
            else if (offAt != null) {
                if (best == null || at - offAt > best.second - best.first) best = offAt to at
                offAt = null
            }
        }
        return best?.takeIf { it.second - it.first >= 3 * 3_600_000L }
    }

    // ── Files ─────────────────────────────────────────────────────────────────

    private val dayName = SimpleDateFormat("yyyy-MM-dd", Locale.UK)

    private fun file(context: Context, at: Long) = File(File(context.filesDir, DIR).apply { mkdirs() }, dayName.format(Date(at)) + ".txt")

    @Synchronized private fun append(context: Context, at: Long, line: String) {
        runCatching { file(context, at).appendText(line + "\n") }
        if (Calendar.getInstance().get(Calendar.MINUTE) == 0) prune(context)
    }

    private fun lines(context: Context, day: Long): List<String> = runCatching { file(context, day).readLines() }.getOrDefault(emptyList())

    /** One timestamp in each local day touched by [from, to). */
    private fun days(from: Long, to: Long): List<Long> {
        val out = mutableListOf<Long>()
        val c = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.HOUR_OF_DAY, 12) }
        while (c.timeInMillis - 12 * 3_600_000L < to) { out += c.timeInMillis; c.add(Calendar.DAY_OF_YEAR, 1) }
        return out
    }

    private fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        File(context.filesDir, DIR).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
