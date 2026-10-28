package com.tolu.dpc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sqrt

/** What a stretch of time was. The review, the Happened calendar and the weekly report all use these names. */
enum class Kind(val label: String, val colour: Int) {
    FIELD("Field", 0xFF1BAF7A.toInt()),
    WORK("Work", 0xFF2A78D6.toInt()),
    BANK("Kowane", 0xFFEDA100.toInt()),
    HALL("Kingdom Hall", 0xFF7A5CC2.toInt()),
    STUDY("Study", 0xFFEB6834.toInt()),
    PREP("Meeting prep", 0xFF7A5CC2.toInt()),
    ERRAND("Errand", 0xFF898781.toInt()),
    WALK("Walk", 0xFF898781.toInt()),
    OTHER("Other", 0xFF898781.toInt()),
    AWAY("Away", 0xFF898781.toInt());

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name }
        /** The answers to "Where were you?". */
        val PLACES = listOf(FIELD, WORK, BANK, HALL, ERRAND, WALK, OTHER)
    }
}

/**
 * Places he stops at, learned from his answers. The first time the phone stops somewhere new it asks; once a place has
 * had the same answer twice, it's labelled without asking. Field is never a place: a territory moves, so field is
 * known from the plan or from his answer for that morning. Coordinates stay on the phone.
 */
object Places {
    private const val PREFS = "places"
    /** A stop belongs to a place within this distance of it. */
    private const val NEAR_M = 80.0
    /** The same answer this many times and the phone stops asking. */
    const val SURE_AFTER = 2

    data class Place(val id: Int, val lat: Double, val lon: Double, val kind: Kind, val times: Int) {
        val sure get() = times >= SURE_AFTER
    }

    /** Known from the start: the Kingdom Hall (12 Oct). */
    private val SEEDS = listOf(Place(1, 6.5324226, 3.3488732, Kind.HALL, SURE_AFTER))

    fun all(context: Context): List<Place> {
        val a = JSONArray(prefs(context).getString("places", null) ?: return SEEDS)
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Place(it.getInt("id"), it.getDouble("lat"), it.getDouble("lon"), Kind.valueOf(it.getString("kind")), it.getInt("times"))
        }
    }

    /** The known place at this point, if any. */
    fun at(context: Context, lat: Double, lon: Double): Place? =
        all(context).map { it to metres(lat, lon, it.lat, it.lon) }.filter { it.second <= NEAR_M }.minByOrNull { it.second }?.first

    /** His answer for a stop: the place there learns it (a different answer starts its count again). */
    fun learn(context: Context, lat: Double, lon: Double, kind: Kind) {
        if (kind == Kind.FIELD || kind == Kind.AWAY || kind == Kind.WALK) return
        val places = all(context).toMutableList()
        val known = at(context, lat, lon)
        if (known == null) {
            places += Place((places.maxOfOrNull { it.id } ?: 0) + 1, lat, lon, kind, 1)
        } else {
            places[places.indexOf(known)] = known.copy(kind = kind, times = if (known.kind == kind) known.times + 1 else 1)
        }
        save(context, places)
    }

    private fun save(context: Context, places: List<Place>) {
        val a = JSONArray()
        places.forEach { a.put(JSONObject().put("id", it.id).put("lat", it.lat).put("lon", it.lon).put("kind", it.kind.name).put("times", it.times)) }
        prefs(context).edit().putString("places", a.toString()).apply()
    }

    /** Distance in metres; flat-earth is plenty at these distances. */
    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    /** For adb (ACTION_AWAY): what it has learned, without coordinates. */
    fun describe(context: Context) = all(context).joinToString("\n") { "  place ${it.id}: ${it.kind.label}, answered ${it.times}×${if (it.sure) " (sure)" else ""}" }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
