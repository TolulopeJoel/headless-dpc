package com.tolu.dpc

import android.app.Activity
import android.content.Intent
import android.net.Uri

/**
 * Today's reading check, in the open (22 Oct: "how do I know if Gemini has failed?"). Where it stands, what Àṣàrò
 * sent, and every attempt with its time and what happened, in plain words. Opened by tapping the line on the entry
 * screen, or from the Now screen.
 */
class ReadingActivity : Activity() {

    private val onChange: () -> Unit = { build() }
    private var closing = false

    override fun onResume() {
        super.onResume()
        Reading.listen(onChange)
        Reading.retryNow(this)   // in front, so Android lets it reach Gemini
        build()
    }

    override fun onPause() {
        super.onPause()
        Reading.unlisten(onChange)
    }

    private fun build() {
        val ui = Ui(this)
        val content = ui.column()
        val status = Reading.status(this)
        content.addView(ui.kick("Today's reading check"))
        content.addView(ui.big(when (status) {
            Reading.Status.NONE -> "Waiting for Àṣàrò"
            Reading.Status.SHORT -> "Not enough answers"
            Reading.Status.CHECKING -> "Checking…"
            Reading.Status.OFFLINE -> "Couldn't check yet"
            Reading.Status.REJECTED -> "Doesn't match"
            Reading.Status.PASSED -> "Matched"
            Reading.Status.UNCHECKED -> "Opened unchecked"
        }))
        val tone = when (status) {
            Reading.Status.PASSED -> ui.good
            Reading.Status.REJECTED -> ui.bad
            else -> ui.ink2
        }
        content.addView(ui.text(Reading.line(this), 15f, ui.body, tone, 8))
        // A match (or the plumbing giving way) opens the phone: say so for a moment, then step aside.
        if ((status == Reading.Status.PASSED || status == Reading.Status.UNCHECKED) && !closing && Reading.justSettled(this)) {
            closing = true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 4_000)
        }

        content.addView(ui.kick("What Àṣàrò sent", top = 26))
        val entry = Reading.todaysEntry(this)
        if (entry == null) {
            content.addView(ui.sub("Nothing yet today. Saving an entry with two answers in Àṣàrò sends it; so does opening Àṣàrò.", 6))
        } else {
            content.addView(ui.text(entry.optString("reading"), 17f, ui.strong, ui.ink, 6))
            val answers = entry.optJSONArray("answers")
            for (i in 0 until (answers?.length() ?: 0)) {
                val a = answers!!.getJSONObject(i)
                content.addView(ui.text(a.optString("question"), 12.5f, ui.strong, ui.muted, 10))
                content.addView(ui.text(a.optString("answer"), 14f, ui.body, ui.ink2, 2))
            }
        }

        content.addView(ui.kick("What happened", top = 26))
        val log = Reading.todaysLog(this)
        if (log.isEmpty()) content.addView(ui.sub("No attempts today.", 6))
        log.forEach { (time, line) -> content.addView(ui.text("$time   $line", 13f, ui.body, ui.ink2, 6)) }

        val footer = ui.footer()
        // Try again, always: with an entry waiting it asks Gemini now; without one it opens Àṣàrò, which sends today's.
        if (status != Reading.Status.PASSED && status != Reading.Status.UNCHECKED) {
            footer.addView(ui.button("Try again") {
                if (Reading.pending(this)) Reading.retryNow(this) else openAsaro(null)
            })
            footer.addView(ui.link(if (status == Reading.Status.NONE) "Write today's entry" else "Edit today's entry in Àṣàrò") {
                openAsaro(if (status == Reading.Status.NONE) "asaro://addEntry" else null)
            })
        }
        footer.addView(ui.link("Close") { finish() })
        setContentView(ui.page(content, footer))
    }

    /** Àṣàrò, at `link` or its home. Opening it sends today's entries. */
    private fun openAsaro(link: String?) {
        val asaro = "com.asaro.meditation"
        runCatching { startActivity(if (link != null) Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(asaro) else packageManager.getLaunchIntentForPackage(asaro)!!) }
    }
}
