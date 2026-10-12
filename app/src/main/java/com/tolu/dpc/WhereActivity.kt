package com.tolu.dpc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout

/**
 * "Where were you?": asked once about a trip the plan and the known places couldn't label. One stop at a time, the
 * longest first, on the stored map with his trail. "Field" answers for the whole trip (a territory isn't a place).
 * "See in Google Maps" opens the spot in Google Maps, which has proper street names and works offline with his
 * downloaded maps. "Later" leaves it for the 20:01 review.
 */
class WhereActivity : Activity() {

    private var chosen: Kind? = null
    private var forKey: String? = null

    override fun onStop() {
        super.onStop()
        NowBar.refresh(this)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val id = intent.getLongExtra(EXTRA_OUTING, 0L)
        val o = Trail.outing(this, id) ?: return finish()
        val open = Day.toCheck(Day.outingBlocks(this, o)).maxByOrNull { it.minutes } ?: run { Ask.settle(this); finish(); return }
        if (forKey != open.key) { forKey = open.key; chosen = open.guess }
        val stop = o.stops.firstOrNull { it.key == open.key }
        val ui = Ui(this)

        val content = ui.column()
        val justNow = System.currentTimeMillis() - o.to < 20 * 60_000L
        content.addView(ui.kick(if (justNow) "You're back" else "Earlier today"))
        content.addView(ui.big("Where were you?"))
        content.addView(ui.sub("${NowActivity.hhmm(open.from)} – ${NowActivity.hhmm(open.to)} · ${duration(open.minutes)}"))
        val map = MapView(this, ui).apply {
            show(Away.home(this@WhereActivity), o.points, o.stops, stop)
            background = ui.rounded(0, 16); clipToOutline = true
        }
        content.addView(map, LinearLayout.LayoutParams(-1, ui.dp(190)).apply { topMargin = ui.dp(14) })
        if (stop != null) content.addView(ui.link("See in Google Maps", 8) {
            val uri = Uri.parse("geo:${stop.lat},${stop.lon}?q=${stop.lat},${stop.lon}(Here)")
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")) }
        })
        content.addView(ui.chips(*Kind.PLACES.map { k ->
            ui.chip(k.label, if (k.colour == Kind.OTHER.colour) null else k.colour, chosen == k) { chosen = k; build() }
        }.toTypedArray()))
        if (o.stops.size > 1) content.addView(ui.sub("Field counts for the whole trip.", 10))

        val footer = ui.footer()
        footer.addView(ui.button("Save", if (chosen == null) ui.line else ui.ink, if (chosen == null) ui.muted else ui.page) {
            val k = chosen ?: return@button
            Trail.answer(this, o.id, if (k == Kind.FIELD || stop == null) "all" else open.key!!, k)
            Ask.settle(this)
            Happened.write(this, startOfDay(0))
            build()
        })
        footer.addView(ui.link("Later, at 20:01") { finish() })
        setContentView(ui.page(content, footer))
    }

    private fun duration(m: Int) = if (m >= 60) "${m / 60} h ${m % 60} m" else "$m m"

    companion object { const val EXTRA_OUTING = "outing" }
}
