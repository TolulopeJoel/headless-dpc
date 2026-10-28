package com.tolu.dpc

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import java.util.Calendar

/**
 * When to ask, and only when it needs to. Coming home after 30 minutes or more: if the plan had a field, bank or work
 * session then, that's the answer; stops at known places label themselves; anything left is asked once ("Where were
 * you?"), right away before 21:00, or the next morning. From 21:00 the day's review waits for him: it opens on the
 * 21:00 alarm and on unlocking, until he says it looks right. 21:00 since 28 Oct 2026, so the review, planning tomorrow,
 * the card and prayer run as one wind-down instead of interrupting dinner at 20:01. Skipping is fine: unconfirmed rows stay unconfirmed.
 */
object Ask {
    private const val TAG = "Ask"
    private const val PREFS = "ask"
    const val REVIEW_AT = 21 * 60
    private const val QUIET_FROM = 21 * 60
    private const val QUIET_TO = 7 * 60

    // ── Coming home ───────────────────────────────────────────────────────────

    fun cameHome(context: Context, id: Long) {
        var o = Trail.outing(context, id) ?: return
        runCatching { FieldBackup.cameHome(context) }
        planFor(context, o)?.let { (plan, windows) ->
            // Field answers for the whole trip (a territory is many short stops). Work and Kowane only answer for the
            // stops inside the session: 16 Oct, out 13:57–20:16, the 18:13 and 19:10 stops came after work and were
            // filed as work without a word.
            fun inside(s: Trail.Stop) = windows.sumOf { w -> maxOf(0L, minOf(s.to, w.end) - maxOf(s.from, w.start)) } * 2 >= s.to - s.from
            val outside = if (plan == Kind.FIELD) emptyList() else o.stops.filterNot { inside(it) }
            if (outside.isEmpty()) {
                Trail.answer(context, id, "all", plan)
                Log.d(TAG, "outing labelled by the plan: ${plan.label}")
                Happened.write(context, startOfDay(0))
                return
            }
            o.stops.filter { inside(it) }.forEach { Trail.answer(context, id, it.key, plan) }
            Log.d(TAG, "stops in the plan labelled ${plan.label}; ${outside.size} outside it to ask about")
            o = Trail.outing(context, id) ?: return
        }
        if (o.minutes < Trail.ASK_AFTER_MIN || Day.toCheck(Day.outingBlocks(context, o)).isEmpty()) {
            Happened.write(context, startOfDay(0))
            return
        }
        val pending = pending(context) + id
        prefs(context).edit().putString("where", JSONArray(pending.distinct()).toString()).apply()
        if (!quiet() && !FieldBackup.holdsAsking(context)) showWhere(context, id)
    }

    /** The planned session most of this trip fell in (field from half an hour before it, banks or work), with its windows. */
    private fun planFor(context: Context, o: Trail.Outing): Pair<Kind, List<SessionWindow>>? = runCatching {
        // No stops at all (a walk, a drive): the plan can't vouch for it, so it's asked about (14 Oct).
        if (o.stops.isEmpty() && o.minutes >= Trail.ASK_AFTER_MIN) return@runCatching null
        // A stop at the Kingdom Hall (a meeting before field, say): the stops speak for themselves, not the plan.
        if (o.stops.any { s -> Places.at(context, s.lat, s.lon)?.let { it.kind == Kind.HALL && it.sure } == true }) return@runCatching null
        val kinds = listOf(
            Kind.FIELD to Field.today(context).map { SessionWindow(it.start - 30 * 60_000L, it.end + 30 * 60_000L) },
            Kind.BANK to BankTime.today(context).map { SessionWindow(it.start - 30 * 60_000L, it.end + 30 * 60_000L) },
            Kind.WORK to WorkTime.today(context).map { SessionWindow(it.start - 45 * 60_000L, it.end + 30 * 60_000L) },
        )
        kinds.map { (kind, ws) -> Triple(kind, ws, ws.sumOf { w -> maxOf(0L, minOf(o.to, w.end) - maxOf(o.from, w.start)) }) }
            .filter { it.third * 2 >= o.to - o.from }.maxByOrNull { it.third }?.let { it.first to it.second }
    }.getOrNull()

    /** adb (ACTION_REOPEN_OUTING): ask about this trip again ("Where were you?" now, or on the next unlock). */
    fun askAgain(context: Context, id: Long) {
        prefs(context).edit().putString("where", JSONArray((pending(context) + id).distinct()).toString()).putLong("shown_at", 0L).apply()
        if (!quiet()) showWhere(context, id)
    }

    fun pending(context: Context): List<Long> {
        val a = JSONArray(prefs(context).getString("where", "[]"))
        return (0 until a.length()).map { a.getLong(it) }
    }

    /** An outing is done being asked about once nothing in it needs him. */
    fun settle(context: Context) {
        val left = pending(context).filter { id -> Trail.outing(context, id)?.let { Day.toCheck(Day.outingBlocks(context, it)).isNotEmpty() } == true }
        prefs(context).edit().putString("where", JSONArray(left).toString()).apply()
    }

    private fun showWhere(context: Context, id: Long) {
        prefs(context).edit().putLong("shown_at", System.currentTimeMillis()).apply()
        runCatching {
            context.startActivity(Intent(context, WhereActivity::class.java).putExtra(WhereActivity.EXTRA_OUTING, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }.onFailure { Log.e(TAG, "couldn't show Where", it) }
    }

    // ── The review ────────────────────────────────────────────────────────────

    fun reviewDue(context: Context) = minuteNow() >= REVIEW_AT && !Day.reviewed(context)

    /** The 21:00 alarm. */
    fun reviewTime(context: Context) {
        if (reviewDue(context)) showReview(context)
        FocusWidget.refresh(context)
        NowBar.refresh(context)
    }

    private fun showReview(context: Context) {
        prefs(context).edit().putLong("shown_at", System.currentTimeMillis()).apply()
        runCatching {
            context.startActivity(Intent(context, ReviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }.onFailure { Log.e(TAG, "couldn't show the review", it) }
    }

    /** Unlocking: the review first (from 21:00), else a waiting "Where were you?" (07:00–21:00). At most every 15 minutes. */
    fun onScreenOn(context: Context) {
        if (System.currentTimeMillis() - prefs(context).getLong("shown_at", 0L) < 15 * 60_000L) return
        if (ScheduleReceiver.currentState(context).gated) return            // the entry comes first
        if (FieldBackup.holdsAsking(context)) return                         // Field Service's backup first (on unlock)
        when {
            reviewDue(context) -> showReview(context)
            !quiet() -> pending(context).lastOrNull()?.let { showWhere(context, it) }
        }
    }

    /** Field Service's backup is done: whatever was waiting for him now (the review, or "Where were you?"). */
    fun whereIfWaiting(context: Context) {
        when {
            reviewDue(context) -> showReview(context)
            !quiet() -> pending(context).lastOrNull()?.let { showWhere(context, it) }
        }
    }

    /** Once a day: yesterday goes to the Happened calendar as it stands, reviewed or not. */
    fun daily(context: Context) {
        val yesterday = startOfDay(-1)
        val p = prefs(context)
        if (p.getLong("written_day", 0L) >= yesterday) return
        if (Happened.write(context, yesterday)) p.edit().putLong("written_day", yesterday).apply()
    }

    // ── The weekly report ─────────────────────────────────────────────────────

    const val REPORT_AT = 16 * 60

    /**
     * The week's report (Sunday to Saturday) is made on the laptop from Sunday 15:00, the first time it's online. A
     * reminder to look at it: Sunday from 16:00, or Monday morning (07:00–12:00) if Sunday's never showed. Once a week.
     */
    fun weeklyReminder(context: Context) {
        val c = Calendar.getInstance()
        val minute = minuteNow()
        val sunday = when {
            c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && minute >= REPORT_AT -> startOfDay(0)
            c.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY && minute in QUIET_TO until 12 * 60 -> startOfDay(-1)
            else -> return
        }
        val p = prefs(context)
        if (p.getLong("reminded_week", 0L) == sunday) return
        p.edit().putLong("reminded_week", sunday).apply()
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("report", "Weekly report", android.app.NotificationManager.IMPORTANCE_HIGH))
        val line = "Your week's report is on your laptop, open in Brave (justme › weekly-report)."
        val n = android.app.Notification.Builder(context, "report")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Your week")
            .setContentText(line)
            .setStyle(android.app.Notification.BigTextStyle().bigText(line))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(3001, n) }
    }

    private fun quiet() = minuteNow().let { it >= QUIET_FROM || it < QUIET_TO }

    private fun minuteNow() = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
