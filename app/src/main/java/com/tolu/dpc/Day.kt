package com.tolu.dpc

import android.content.Context

/**
 * A day as it happened, for the 20:01 review and the Happened calendar: study and prep he logged, his outings (labelled
 * by the plan, by a known place, or by his answer), and what the plan had that didn't happen.
 */
object Day {

    enum class Status { SURE, GUESS, UNKNOWN, MISSED }

    /**
     * One row. `outing` and `key` point at what an answer changes: a stop (its start) or the whole outing ("all").
     * `guess` is the likely answer for a GUESS.
     */
    data class Block(
        val kind: Kind, val from: Long, val to: Long, val status: Status, val title: String,
        val outing: Long? = null, val key: String? = null, val guess: Kind? = null, val late: Boolean = false,
        /** From the plan, not seen happen (work: the laptop checks it). Not written to Happened. */
        val fromPlan: Boolean = false,
    ) {
        val minutes get() = ((to - from) / 60_000L).toInt()
    }

    /** The day starting at `dayStart`. The plan (missed sessions, work) is only known for today. */
    fun blocks(context: Context, dayStart: Long = startOfDay(0)): List<Block> {
        val dayEnd = dayStart + 86_400_000L
        val out = mutableListOf<Block>()
        Logbook.between(context, dayStart, dayEnd).forEach {
            out += Block(it.kind, it.from, it.to, Status.SURE, it.title, late = it.late)
        }
        Trail.between(context, dayStart, dayEnd).forEach { out += outingBlocks(context, it) }
        if (dayStart == startOfDay(0)) out += planned(context, out)
        return out.sortedBy { it.from }
    }

    /** One outing's rows: the whole trip if it has a whole-trip answer, otherwise one per stop. */
    fun outingBlocks(context: Context, o: Trail.Outing): List<Block> {
        o.answers["all"]?.let { return listOf(Block(it, o.from, o.to, Status.SURE, it.label, o.id, "all")) }
        val stops = o.stops
        if (stops.isEmpty()) return listOf(Block(Kind.AWAY, o.from, o.to, Status.UNKNOWN, "Away", o.id, "all", guess = fieldGuess(o)))
        return stops.map { s ->
            val answered = o.answers[s.key]
            val place = Places.at(context, s.lat, s.lon)
            when {
                answered != null -> Block(answered, s.from, s.to, Status.SURE, answered.label, o.id, s.key)
                place?.sure == true -> Block(place.kind, s.from, s.to, Status.SURE, place.kind.label, o.id, s.key)
                place != null -> Block(place.kind, s.from, s.to, Status.GUESS, place.kind.label + "?", o.id, s.key, guess = place.kind)
                else -> Block(Kind.AWAY, s.from, s.to, Status.UNKNOWN, "Away · new place", o.id, s.key, guess = fieldGuess(o))
            }
        }
    }

    /** A morning out with several stops looks like field service: offered first, never assumed. */
    private fun fieldGuess(o: Trail.Outing): Kind? {
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = o.from }.get(java.util.Calendar.HOUR_OF_DAY)
        return if (hour in 6..13 && o.stops.size >= 3) Kind.FIELD else null
    }

    /** Today's plan: work (the laptop checks it), and field or study that didn't happen. */
    private fun planned(context: Context, happened: List<Block>): List<Block> {
        val now = System.currentTimeMillis()
        val out = mutableListOf<Block>()
        fun covered(kind: Kind, w: SessionWindow) = happened.any { it.kind == kind && it.from < w.end && it.to > w.start }
        runCatching {
            WorkTime.today(context).filter { it.start < now }.forEach { w ->
                if (!covered(Kind.WORK, w)) out += Block(Kind.WORK, w.start, minOf(w.end, now), Status.SURE, "Work", fromPlan = true)
            }
            Field.today(context).filter { it.end < now }.forEach { w ->
                if (!covered(Kind.FIELD, w) && !Field.passedToday(context)) out += Block(Kind.FIELD, w.start, w.end, Status.MISSED, "Field · missed", fromPlan = true)
            }
            Study.today(context).filter { it.end < now }.forEach { w ->
                if (!covered(Kind.STUDY, w)) out += Block(Kind.STUDY, w.start, w.end, Status.MISSED, "Study · nothing logged", fromPlan = true)
            }
        }
        return out
    }

    /** Rows that need him: guesses and unknowns. */
    fun toCheck(blocks: List<Block>) = blocks.filter { it.status == Status.GUESS || it.status == Status.UNKNOWN }

    // ── Reviewed ──────────────────────────────────────────────────────────────

    fun reviewed(context: Context, dayStart: Long = startOfDay(0)) =
        context.getSharedPreferences("review", Context.MODE_PRIVATE).getBoolean("reviewed_$dayStart", false)

    fun markReviewed(context: Context, dayStart: Long = startOfDay(0)) {
        context.getSharedPreferences("review", Context.MODE_PRIVATE).edit().putBoolean("reviewed_$dayStart", true).apply()
    }
}
