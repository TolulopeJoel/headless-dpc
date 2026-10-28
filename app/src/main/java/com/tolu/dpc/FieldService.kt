package com.tolu.dpc

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.hypot

/**
 * His people, read on the phone from Field Service itself. Field Service saves its backups (gzip + Smile, Jackson's
 * binary JSON) in its own folder: once a day on its schedule (one file per week, AutoBackup_WeekNN, rewritten), and
 * whenever he taps "Backup now". No app may open that folder, but the phone's own debugging service may, so the DPC
 * connects to it on the phone (127.0.0.1:5555, the way Shizuku works), pulls the newest backup, decodes it here and
 * writes files/people.json for People. Read a few seconds after he leaves Field Service, and every half hour as a
 * backstop; FieldBackup makes Field Service save a fresh one when he comes home from field service. Needs
 * `adb tcpip 5555` once after each restart (the laptop does it when he plugs in) and one "Always allow" for this
 * app's key.
 */
object FieldService {
    private const val TAG = "FieldService"
    const val PKG = "com.lostpixels.fieldservice"
    /** Where Field Service keeps its backups: the automatic one, the weekly ones and any he makes by hand. */
    const val DIR = "/sdcard/Android/data/$PKG/files"
    const val PORT = 5555
    private val HOME = 6.53205 to 3.34726

    @Volatile private var running = false
    @Volatile private var lastTry = 0L

    /** He just left Field Service: note when (FieldBackup asks), and read any backup it made a few seconds later. */
    fun leftApp(context: Context) {
        context.getSharedPreferences("fieldservice", Context.MODE_PRIVATE).edit().putLong("left_at", System.currentTimeMillis()).apply()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ sync(context, force = true) }, 8_000L)
    }

    /** Read the backup if it's newer than the last one read. At most every 30 minutes unless forced. */
    fun sync(context: Context, force: Boolean = false) {
        if (running) return
        if (!force && SystemClock.elapsedRealtime() - lastTry < 30 * 60_000L) return
        lastTry = SystemClock.elapsedRealtime()
        running = true
        val app = context.applicationContext
        kotlin.concurrent.thread(name = "fieldservice-sync") {
            try { syncNow(app) } catch (e: Throwable) { Log.e(TAG, "sync failed", e); state(app, "failed: ${e.javaClass.simpleName} ${e.message?.take(80)}") }
            finally { running = false }
        }
    }

    fun keys(context: Context): AdbKeyPair {
        val priv = File(context.filesDir, "adbkey"); val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private fun syncNow(context: Context) {
        val prefs = context.getSharedPreferences("fieldservice", Context.MODE_PRIVATE)
        Dadb.create("127.0.0.1", PORT, keys(context)).use { adb ->
            // The newest backup of any kind: "<seconds> <path>".
            val stamp = adb.shell("stat -c '%Y %n' $DIR/*.mabackup | sort -n | tail -1").output.trim()
            val backup = stamp.substringAfter(' ', "")
            if (!backup.endsWith(".mabackup") || !stamp.substringBefore(' ').all { it.isDigit() }) { state(context, "no backup found"); return }
            val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            if (stamp == prefs.getString("stamp", null) && prefs.getLong("version", 0L) == version) { state(context, "up to date"); return }
            val local = File(context.cacheDir, "fs.mabackup")
            adb.pull(local, backup)
            val people = people(context, decode(local.readBytes()))
            local.delete()
            File(context.filesDir, "people.json.tmp").apply { writeText(people.toString()); renameTo(People.file(context)) }
            prefs.edit().putString("stamp", stamp).putLong("version", version).apply()
            state(context, "read ${people.length()} people from ${backup.substringAfterLast('/')}")
        }
    }

    /**
     * A backup handed over through the share sheet (Field Service → Backup → "E-mail etc." → the DPC), read the same way
     * as one pulled over the debugging port. The share sheet needs no port, so it works after a restart and after the
     * night has switched debugging off (28 Oct 2026). True if people came out of it.
     */
    fun readShared(context: Context, raw: ByteArray, name: String): Boolean {
        val people = people(context, decode(raw))
        if (people.length() == 0) { state(context, "shared $name: no people in it"); return false }
        File(context.filesDir, "people.json.tmp").apply { writeText(people.toString()); renameTo(People.file(context)) }
        // The stamp FieldBackup compares with: when this backup was read, as "<seconds> <name>".
        context.getSharedPreferences("fieldservice", Context.MODE_PRIVATE).edit()
            .putString("stamp", "${System.currentTimeMillis() / 1000} shared:$name").apply()
        state(context, "read ${people.length()} people from a shared $name")
        return true
    }

    private fun state(context: Context, s: String) {
        context.getSharedPreferences("fieldservice", Context.MODE_PRIVATE).edit()
            .putString("state", s).putLong("state_at", System.currentTimeMillis()).apply()
        Log.d(TAG, s)
    }

    fun describe(context: Context): String = context.getSharedPreferences("fieldservice", Context.MODE_PRIVATE).let {
        "field service sync: ${it.getString("state", "never")} (${java.util.Date(it.getLong("state_at", 0L))})"
    }

    // ── Smile: Jackson's binary JSON ──────────────────────────────────────────

    /** A key that appeared more than once in one object (the format came from XML: one "P" per person). */
    class Many : ArrayList<Any?>()

    fun decode(raw: ByteArray): Any? {
        val d = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) GZIPInputStream(raw.inputStream()).readBytes() else raw
        return Smile(d).value()
    }

    private class Smile(val d: ByteArray) {
        var i = 4
        val sharedNames: Boolean
        val sharedValues: Boolean
        val names = ArrayList<String>(); val values = ArrayList<String>()

        init {
            check(d[0] == ':'.code.toByte() && d[1] == ')'.code.toByte() && d[2] == '\n'.code.toByte()) { "not Smile" }
            sharedNames = d[3].toInt() and 1 != 0; sharedValues = d[3].toInt() and 2 != 0
        }

        fun byte(): Int = d[i++].toInt() and 0xFF
        fun vint(): Long { var v = 0L; while (true) { val b = byte(); if (b and 0x80 != 0) return (v shl 6) or (b and 0x3F).toLong(); v = (v shl 7) or b.toLong() } }
        fun zz(n: Long) = (n ushr 1) xor -(n and 1)
        fun text(n: Int): String { val s = String(d, i, n, Charsets.UTF_8); i += n; return s }
        fun untilFC(): String { var j = i; while (d[j] != 0xFC.toByte()) j++; val s = String(d, i, j - i, Charsets.UTF_8); i = j + 1; return s }
        fun seven(n: Int): ByteArray {
            val groups = (n * 8 + 6) / 7
            var bits = java.math.BigInteger.ZERO
            repeat(groups) { bits = bits.shiftLeft(7).or(java.math.BigInteger.valueOf((byte() and 0x7F).toLong())) }
            val bytes = bits.shiftRight(groups * 7 - n * 8).toByteArray()
            return ByteArray(n) { k -> bytes.getOrElse(bytes.size - n + k) { 0 } }
        }
        fun remember(s: String): String { if (sharedValues && s.toByteArray().size <= 64) { if (values.size >= 1024) values.clear(); values += s }; return s }

        fun key(): String? {
            val b = byte()
            if (b == 0xFB) return null
            if (b == 0x20) return ""
            if (b in 0x30..0x33) return names[((b and 3) shl 8) or byte()]
            if (b in 0x40..0x7F) return names[b and 0x3F]
            val s = when (b) { 0x34 -> untilFC(); in 0x80..0xBF -> text((b and 0x3F) + 1); in 0xC0..0xF7 -> text((b and 0x3F) + 2); else -> error("bad key $b at ${i - 1}") }
            if (sharedNames && s.toByteArray().size <= 64) { if (names.size >= 1024) names.clear(); names += s }
            return s
        }

        fun value(): Any? {
            val b = byte()
            return when {
                b < 0x20 -> values[b]
                b == 0x20 -> ""; b == 0x21 -> null; b == 0x22 -> false; b == 0x23 -> true
                b == 0x24 || b == 0x25 -> zz(vint())
                b == 0x26 -> java.math.BigInteger(seven(vint().toInt())).toLong()
                b == 0x28 -> java.lang.Float.intBitsToFloat(java.nio.ByteBuffer.wrap(seven(4)).int).toDouble()
                b == 0x29 -> java.lang.Double.longBitsToDouble(java.nio.ByteBuffer.wrap(seven(8)).long)
                b == 0x2A -> { val scale = zz(vint()).toInt(); java.math.BigDecimal(java.math.BigInteger(seven(vint().toInt())), scale).toDouble() }
                b in 0x40..0x5F -> remember(text((b and 0x1F) + 1))
                b in 0x60..0x7F -> remember(text((b and 0x1F) + 33))
                b in 0x80..0x9F -> remember(text((b and 0x1F) + 2))
                b in 0xA0..0xBF -> remember(text((b and 0x1F) + 34))
                b in 0xC0..0xDF -> zz((b and 0x1F).toLong())
                b == 0xE0 || b == 0xE4 -> untilFC()
                b == 0xE8 -> seven(vint().toInt())
                b in 0xEC..0xEF -> values[((b and 3) shl 8) or byte()]
                b == 0xF8 -> ArrayList<Any?>().also { l -> while (d[i] != 0xF9.toByte()) l += value(); i++ }
                b == 0xFA -> LinkedHashMap<String, Any?>().also { m ->
                    while (true) {
                        val k = key() ?: break
                        val v = value()
                        if (m.containsKey(k)) {
                            val prev = m[k]
                            m[k] = (prev as? Many) ?: Many().also { it += prev }
                            (m[k] as Many) += v
                        } else m[k] = v
                    }
                }
                else -> error("bad value $b at ${i - 1}")
            }
        }
    }

    // ── People: what People needs, from the decoded backup ────────────────────

    private fun many(v: Any?): List<Any?> = when (v) { null -> emptyList(); is Many -> v; else -> listOf(v) }

    @Suppress("UNCHECKED_CAST")
    private fun map(v: Any?) = v as? Map<String, Any?>

    private val SUFFIX = Regex("\\b(street|st|road|rd|close|cl|avenue|ave|crescent|cres|lane|ln|way|drive|dr)\\b\\.?", RegexOption.IGNORE_CASE)
    private fun norm(s: String) = SUFFIX.replace(s.lowercase(), "").replace(Regex("[^a-z]"), "")

    private fun lev1(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var prev = IntArray(b.length + 1) { it }
        for (x in a.indices) {
            val cur = IntArray(b.length + 1); cur[0] = x + 1
            for (y in b.indices) cur[y + 1] = minOf(prev[y + 1] + 1, cur[y] + 1, prev[y] + if (a[x] == b[y]) 0 else 1)
            prev = cur
        }
        return prev[b.length] <= 1
    }

    private fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>) =
        hypot((a.first - b.first) * 111_320.0, (a.second - b.second) * 111_320.0 * cos(Math.toRadians(a.first)))

    /** The decoded backup → the people file: the same rules as tools/fieldservice.py. */
    fun people(context: Context, tree: Any?): JSONArray {
        val roads = MapView.Roads.load(context)
        val lines = HashMap<String, MutableList<List<Pair<Double, Double>>>>()
        roads.ways.forEach { w -> if (w.name >= 0) lines.getOrPut(norm(roads.names[w.name])) { ArrayList() } += w.lat.indices.map { w.lat[it] to w.lon[it] } }
        fun street(addr: String): List<List<Pair<Double, Double>>> {
            val k = norm(addr); if (k.length < 3) return emptyList()
            lines[k]?.let { return it }
            val near = if (k.length >= 5) lines.keys.filter { lev1(k, it) } else emptyList()
            return if (near.size == 1) lines[near[0]]!! else emptyList()
        }
        val out = JSONArray()
        many(map(map(tree)?.get("PersonMap"))?.get("Pe")).forEach { item ->
            val p = map(map(item)?.get("P")) ?: return@forEach
            if (p["Hidden"] == true) return@forEach
            val visits = many(map(p["Inf"])?.get("PI")).mapNotNull { map(it) }.sortedBy { ms(it["D"]) }
            val addr = (p["Add"] as? String).orEmpty().trim()
            val st = street(addr)
            var pin: Pair<Double, Double>? = null
            val la = (p["Lat"] as? Long); val lo = (p["Lon"] as? Long)
            if (la != null && lo != null && la != 0L) {
                val pt = la / 1e6 to lo / 1e6
                val onStreet = st.isEmpty() || st.minOf { line -> line.minOf { metres(pt, it) } } < 250
                if (metres(pt, HOME) < 3500 && onStreet) pin = pt
            }
            // No pin and a street the map doesn't know: still listed (by the address he wrote), just never "nearby".
            val where = JSONArray()
            if (pin != null) where.put(JSONArray().put(pin.first).put(pin.second))
            else st.forEach { line -> where.put(JSONArray().apply { line.forEach { (a, b) -> put(JSONArray().put(a).put(b)) } }) }
            val next = visits.reversed().mapNotNull { (it["Nx"] as? String)?.takeIf { s -> s.length > 6 } }.firstOrNull().orEmpty()
            out.put(JSONObject()
                .put("name", (p["N"] as? String).orEmpty().trim())
                .put("kind", if ((p["Type"] as? Long) in setOf(2L, 3L)) "study" else "rv")
                .put("street", addr).put("pin", pin != null).put("where", where)
                .put("last", ms(visits.lastOrNull()?.get("D")))
                .put("visits", visits.size).put("next", next.take(600))
                .put("note", visits.reversed().mapNotNull { (it["I"] as? String)?.takeIf { s -> s.isNotBlank() } }.firstOrNull().orEmpty().take(1200))
                .put("dates", JSONArray().apply { visits.takeLast(6).reversed().forEach { v ->
                    put(JSONObject().put("d", ms(v["D"])).put("held", v["St"] != false && v["St"] != "False")) } }))
        }
        return out
    }

    /** A visit date in ms, whether the backup holds it as a number or as text. */
    private fun ms(v: Any?): Long = (v as? Number)?.toLong() ?: (v as? String)?.toLongOrNull() ?: 0L
}
