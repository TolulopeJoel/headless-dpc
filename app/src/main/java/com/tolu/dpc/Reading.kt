package com.tolu.dpc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Was it read? (20 Oct: for six days the entries were written without the reading.) Àṣàrò hands over today's entry
 * (the passage and the answers); Gemini says whether the entry matches what is in that passage. A pass counts as
 * today's entry; a "doesn't match" doesn't, and editing the entry in Àṣàrò sends it again.
 *
 * Gemini is only asked while ReadingActivity or the entry screen is in front: in the background Android blocks the
 * DPC's internet. 22 Oct, his ask: it has to be transparent and it mustn't stress his life. So every attempt is logged in plain words
 * (ReadingActivity shows them, with what was sent), and the plumbing can't hold him: when Gemini can't be reached
 * (no internet, an error, no key) for UNCHECKED_AFTER_MS after the entry arrived, it opens unchecked, and the weekly
 * report says so. Only Gemini's own "doesn't match" keeps the phone shut.
 */
object Reading {
    private const val TAG = "Reading"
    private const val PREFS = "reading"
    private const val MODEL = "gemini-flash-latest"
    private const val RETRY_GAP_MS = 20_000L
    private const val AUTO_RETRY_MS = 60_000L
    const val UNCHECKED_AFTER_MS = 10 * 60_000L
    private const val LOG_KEEP = 40

    enum class Status { NONE, SHORT, CHECKING, OFFLINE, REJECTED, PASSED, UNCHECKED }

    /** One try: Gemini's verdict, or why it couldn't be asked. */
    private sealed class Outcome {
        data class Verdict(val pass: Boolean, val reason: String) : Outcome()
        data class Problem(val what: String) : Outcome()
    }

    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastTry = 0L
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private val RULES = """
        You check one morning's Bible-reading journal entry. It names the passage that was read and gives the answers to
        a few reflection questions. Answer one question: does this entry match what is in that passage?

        Pass when the answers fit that passage. Readers often read a few chapters around the one they name, so content
        from neighbouring chapters still fits. Fail only when the answers clearly don't belong to this part of the Bible,
        or are filler, nonsense, or copies of the questions. When unsure, pass. Don't judge doctrine, depth, length,
        spelling or grammar.

        reason: one short sentence. On a fail, say why it doesn't match.
    """.trimIndent()

    // ── From Àṣàrò ────────────────────────────────────────────────────────────

    /**
     * ACTION_ENTRY_SAVED: all of today's entries as one, JSON {reading, answers: [{question, answer}], answered, needed}.
     * Àṣàrò sends it even when it's short (22 Oct: two one-answer entries were never sent, and nothing said why), so
     * the check can say what's missing; only an entry with enough answers goes to Gemini.
     */
    fun received(context: Context, json: String?) {
        if (json.isNullOrBlank()) {
            note(context, "Àṣàrò's signal came without the entry (an old Àṣàrò?). Update Àṣàrò.")
            return
        }
        val hash = hash(json)
        val p = prefs(context)
        p.edit().putString("last_sent", json).putLong("last_sent_at", System.currentTimeMillis()).apply()
        val sent = runCatching { JSONObject(json) }.getOrNull()
        val answered = sent?.optInt("answered", -1) ?: -1
        val needed = sent?.optInt("needed", 0) ?: 0
        if (answered in 0 until needed) {
            val fresh = hash != p.getString("short_hash", null)
            p.edit().putString("short_hash", hash).putString("short", "$answered of the $needed").putLong("short_at", System.currentTimeMillis())
                .apply()
            if (fresh) note(context, "Àṣàrò has today's entries, but only $answered of the $needed answers needed (notes don't count). Not sent to Gemini.")
            changed()
            return
        }
        if (hash == p.getString("judged", null)) {
            Log.d(TAG, "entry already judged (${status(context)}): ignored")
            return
        }
        // A new wording replaces what was pending; the same wording keeps the time it was first handed over.
        if (hash != p.getString("pending_hash", null)) {
            p.edit().putString("pending", json).putString("pending_hash", hash)
                .putLong("pending_at", System.currentTimeMillis()).putString("status", Status.CHECKING.name)
                .remove("reason").remove("problem").apply()
            note(context, "Entry received from Àṣàrò: ${runCatching { JSONObject(json).optString("reading") }.getOrDefault("?")}")
        }
        // Android cuts a background app's internet (blocked=APP_BACKGROUND, 22 Oct), so the check runs with its screen
        // in front: ReadingActivity asks as it opens, and he watches it happen. A device owner may open it from here.
        val shown = runCatching {
            context.startActivity(android.content.Intent(context, ReadingActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.isSuccess
        if (!shown) check(context, force = true)
    }

    /** Settled in the last minute: the check screen closes itself after showing it. */
    fun justSettled(context: Context) = System.currentTimeMillis() - prefs(context).getLong("judged_at", 0L) < 60_000L

    /** Whether an entry is waiting for Gemini. */
    fun pending(context: Context) = prefs(context).getString("pending", null) != null

    // ── Retrying ──────────────────────────────────────────────────────────────

    /** Asks again if an entry is waiting (at most every 20 s unless forced). Safe to call from anywhere. */
    fun retry(context: Context) = check(context, force = false)

    /** ReadingActivity's "Try again now". */
    fun retryNow(context: Context) = check(context, force = true)

    private fun check(context: Context, force: Boolean) {
        val app = context.applicationContext
        val p = prefs(app)
        val json = p.getString("pending", null) ?: return
        val hash = p.getString("pending_hash", null) ?: return
        val at = p.getLong("pending_at", 0L)
        // Yesterday's entry can't open today; today's own entry replaces it.
        if (at < ScheduleReceiver.entryCountsFrom()) {
            p.edit().remove("pending").remove("pending_hash").remove("pending_at").remove("status").apply()
            return
        }
        if (!force && SystemClock.elapsedRealtime() - lastTry < RETRY_GAP_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        lastTry = SystemClock.elapsedRealtime()
        p.edit().putString("status", Status.CHECKING.name).apply()
        changed()
        Thread {
            val started = SystemClock.elapsedRealtime()
            try {
                val outcome = try { ask(json) } catch (e: Exception) { Outcome.Problem(plain(e)) }
                val took = (SystemClock.elapsedRealtime() - started) / 1000
                // Something newer arrived while this one was out: it gets its own check.
                if (p.getString("pending_hash", null) != hash) return@Thread
                when (outcome) {
                    is Outcome.Problem -> {
                        note(app, "Couldn't check (${took}s): ${outcome.what}")
                        if (System.currentTimeMillis() - at >= UNCHECKED_AFTER_MS) {
                            finish(app, hash, Status.UNCHECKED, "Opened unchecked: Gemini couldn't be reached for 10 minutes (${outcome.what}).")
                            ScheduleReceiver.recordEntry(app, at)
                            ScheduleReceiver.enforce(app)
                        } else {
                            p.edit().putString("status", Status.OFFLINE.name).putString("problem", outcome.what).apply()
                            main.postDelayed({ retry(app) }, AUTO_RETRY_MS)
                        }
                    }
                    is Outcome.Verdict -> {
                        note(app, "Gemini (${took}s): ${if (outcome.pass) "match" else "doesn't match"}. ${outcome.reason}")
                        finish(app, hash, if (outcome.pass) Status.PASSED else Status.REJECTED, outcome.reason)
                        if (outcome.pass) {
                            ScheduleReceiver.recordEntry(app, at)
                            ScheduleReceiver.enforce(app)
                        }
                    }
                }
            } finally {
                inFlight.set(false)
                changed()
            }
        }.start()
    }

    /** The entry is settled: remember the verdict, and for a day that opened, how (the weekly report reads it). */
    private fun finish(context: Context, hash: String, status: Status, reason: String) {
        val e = prefs(context).edit().remove("pending").remove("pending_hash").remove("pending_at").remove("problem")
            .putString("judged", hash).putLong("judged_at", System.currentTimeMillis()).putString("reason", reason)
            .putString("status", status.name)
        if (status == Status.PASSED || status == Status.UNCHECKED) e.putString("day_${startOfDay(0)}", status.name)
        e.apply()
    }

    // ── Gemini ────────────────────────────────────────────────────────────────

    private fun ask(json: String): Outcome {
        val key = BuildConfig.GEMINI_API_KEY
        if (key.isBlank()) return Outcome.Problem("this DPC was built without a Gemini key")
        val entry = JSONObject(json)
        val answers = entry.optJSONArray("answers") ?: JSONArray()
        val text = buildString {
            append("Passage read: ").append(entry.optString("reading")).append("\n\n")
            for (i in 0 until answers.length()) {
                val a = answers.getJSONObject(i)
                append("Q: ").append(a.optString("question")).append('\n')
                append("A: ").append(a.optString("answer")).append("\n\n")
            }
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", RULES))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0)
                .put("responseMimeType", "application/json")
                .put("responseSchema", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("pass", JSONObject().put("type", "BOOLEAN"))
                        .put("reason", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("pass").put("reason"))))

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val message = runCatching { JSONObject(err).getJSONObject("error").optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
                return Outcome.Problem("Gemini said $code" + (message?.let { ": ${it.take(160)}" } ?: ""))
            }
            val reply = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val parts = reply.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: return Outcome.Problem("Gemini answered without a verdict")
            val answer = (0 until parts.length()).map { parts.getJSONObject(it) }
                .filterNot { it.optBoolean("thought") }.joinToString("") { it.optString("text") }
            val verdict = runCatching { JSONObject(answer) }.getOrNull()
            if (verdict == null || !verdict.has("pass")) return Outcome.Problem("Gemini's answer couldn't be read")
            return Outcome.Verdict(verdict.getBoolean("pass"), verdict.optString("reason").trim())
        } finally {
            conn.disconnect()
        }
    }

    /** A network failure in words. */
    private fun plain(e: Exception): String = when (e) {
        is UnknownHostException -> "no internet (Google's address couldn't be looked up; is mobile data on, or is NextDNS blocking generativelanguage.googleapis.com?)"
        is ConnectException -> "no internet (couldn't connect)"
        is SocketTimeoutException -> "timed out waiting for Gemini"
        else -> "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
    }

    // ── The log ───────────────────────────────────────────────────────────────

    private fun note(context: Context, line: String) {
        Log.d(TAG, line)
        val p = prefs(context)
        val log = runCatching { JSONArray(p.getString("log", "[]")) }.getOrDefault(JSONArray())
        log.put(JSONObject().put("at", System.currentTimeMillis()).put("line", line))
        val kept = JSONArray()
        for (i in maxOf(0, log.length() - LOG_KEEP) until log.length()) kept.put(log.get(i))
        p.edit().putString("log", kept.toString()).apply()
        changed()
    }

    /** Today's log lines, oldest first, as (time, line). */
    fun todaysLog(context: Context): List<Pair<String, String>> {
        val log = runCatching { JSONArray(prefs(context).getString("log", "[]")) }.getOrDefault(JSONArray())
        val from = startOfDay(0)
        val hhmm = SimpleDateFormat("HH:mm:ss", Locale.UK)
        return (0 until log.length()).map { log.getJSONObject(it) }.filter { it.getLong("at") >= from }
            .map { hhmm.format(it.getLong("at")) to it.getString("line") }
    }

    /** What Àṣàrò last sent today, or null. */
    fun todaysEntry(context: Context): JSONObject? {
        val p = prefs(context)
        if (p.getLong("last_sent_at", 0L) < startOfDay(0)) return null
        return p.getString("last_sent", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    /** How the day opened: "passed", "unchecked", or null. For the Happened summary and the weekly report. */
    fun dayResult(context: Context, dayStart: Long): String? = prefs(context).getString("day_$dayStart", null)?.lowercase()

    // ── For the screens ───────────────────────────────────────────────────────

    /** Where today's check stands. A verdict on an earlier day's entry says nothing about today. */
    fun status(context: Context): Status {
        val p = prefs(context)
        val s = runCatching { Status.valueOf(p.getString("status", null) ?: "NONE") }.getOrDefault(Status.NONE)
        val today = ScheduleReceiver.entryCountsFrom()
        // The newest thing Àṣàrò said wins: a short entry after the last full one means it's short now.
        val shortAt = p.getLong("short_at", 0L)
        if (shortAt >= today && shortAt > p.getLong("pending_at", 0L) && shortAt > p.getLong("judged_at", 0L)) return Status.SHORT
        return when (s) {
            Status.SHORT -> Status.NONE
            Status.PASSED, Status.REJECTED, Status.UNCHECKED -> if (p.getLong("judged_at", 0L) >= today) s else Status.NONE
            Status.CHECKING, Status.OFFLINE -> if (p.getLong("pending_at", 0L) >= today) s else Status.NONE
            Status.NONE -> s
        }
    }

    /** One line for the entry screen. Always says something, so the lock is never a mystery. */
    fun line(context: Context): String {
        val p = prefs(context)
        return when (status(context)) {
            Status.NONE -> "Waiting for today's entry from Àṣàrò."
            Status.SHORT -> "Àṣàrò has today's entries, but only ${p.getString("short", "?")} answers needed. Notes don't count; answers across entries do. Add an answer in Àṣàrò."
            Status.CHECKING -> "Gemini is checking your entry…"
            Status.OFFLINE -> {
                val opensAt = SimpleDateFormat("HH:mm", Locale.UK).format(p.getLong("pending_at", 0L) + UNCHECKED_AFTER_MS)
                "Couldn't check: ${p.getString("problem", null) ?: "unknown"}. Trying every minute; opens unchecked at $opensAt."
            }
            Status.REJECTED -> "Doesn't match: " + (p.getString("reason", null)?.takeIf { it.isNotBlank() } ?: "no reason given") +
                " Edit the entry in Àṣàrò to send it again."
            Status.PASSED -> "Matched. " + (p.getString("reason", null) ?: "")
            Status.UNCHECKED -> p.getString("reason", null) ?: "Opened unchecked."
        }
    }

    fun listen(listener: () -> Unit) = synchronized(listeners) { listeners += listener }
    fun unlisten(listener: () -> Unit) = synchronized(listeners) { listeners -= listener }
    private fun changed() = main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    /** adb: where it stands and today's log. */
    fun describe(context: Context): String =
        "reading: ${status(context)} — ${line(context)}\n" + todaysLog(context).joinToString("\n") { "  ${it.first} ${it.second}" }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
