package com.tolu.dpc

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Coming home from field service: have Field Service save a fresh backup, so the people screen and tomorrow's
 * reminders know today's visits. Field Service's own folder can't be read by any app, and the debugging port the DPC
 * used to reach it closes on every restart and every night (28 Oct 2026: it had been failing for weeks). So now the
 * accessibility service does what he would: open Field Service → menu → Backup → "E-mail etc." → CREATE BACKUP → "Read my visits",
 * and ReceiveBackupActivity reads the file it's handed. About 5 seconds; gives up after 40.
 *
 * At most once a day, only on a day with field service, only if he opened Field Service since its last backup, and
 * only with the phone unlocked in his hand (else on the next unlock). "Where were you?" waits until it's done.
 */
object FieldBackup {
    private const val TAG = "FieldBackup"
    private const val PREFS = "fieldbackup"
    private const val WAIT_MS = 40_000L
    private const val STEP_MS = 700L
    private const val SHARE_LABEL = "Read my visits"

    @Volatile var running = false
        private set
    @Volatile private var startedAt = 0L
    private val tapped = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val main = Handler(Looper.getMainLooper())

    /** Came home. Wanted if today had field service (planned or tapped) and Field Service was used since its last backup. */
    fun cameHome(context: Context) {
        val today = startOfDay(0)
        val p = prefs(context)
        if (p.getLong("done_day", 0L) == today) return
        val fieldDay = Field.today(context).isNotEmpty() || Field.marks(context, today).first != null
        if (!fieldDay || !usedSinceBackup(context)) return
        p.edit().putLong("wanted_day", today).apply()
        Log.d(TAG, "wanted")
        if (inHand(context)) run(context)
    }

    /** Unlocked: run if one is waiting from today. */
    fun onUnlock(context: Context) {
        if (waiting(context)) run(context)
    }

    /** Waiting or running: "Where were you?" holds off until it's done. */
    fun holdsAsking(context: Context) = running || waiting(context)

    private fun waiting(context: Context): Boolean {
        val p = prefs(context); val today = startOfDay(0)
        return p.getLong("wanted_day", 0L) == today && p.getLong("done_day", 0L) != today
    }

    /** Field Service opened since the newest backup the DPC has read (or nothing read yet). */
    private fun usedSinceBackup(context: Context): Boolean {
        val fs = context.getSharedPreferences("fieldservice", Context.MODE_PRIVATE)
        val left = fs.getLong("left_at", 0L)
        val backupAt = fs.getString("stamp", null)?.substringBefore(' ')?.toLongOrNull()?.times(1000) ?: return true
        return left > backupAt
    }

    private fun inHand(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun run(context: Context) {
        if (running) return
        if (!BlockScreenService.isRunning()) { Log.w(TAG, "the accessibility service isn't running; next unlock"); return }
        val app = context.applicationContext
        val launch = app.packageManager.getLaunchIntentForPackage(FieldService.PKG) ?: return
        running = true
        startedAt = System.currentTimeMillis()
        tapped.clear()
        main.post { Toast.makeText(app, "Saving your Field Service visits…", Toast.LENGTH_SHORT).show() }
        app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        main.postDelayed({ tick(app) }, STEP_MS)
    }

    /**
     * One look at the screen in front and the next tap toward the share: Field Service's menu ("Open"), its "Backup"
     * item, the "E-mail etc." destination and "CREATE BACKUP" on the Backup screen, then "Read my visits" in the share sheet (scrolled to if it's further
     * down). The same thing isn't tapped twice within 3 seconds. Gives up after 40 seconds.
     */
    private fun tick(app: Context) {
        if (!running) return
        if (System.currentTimeMillis() - startedAt > WAIT_MS) { done(app, false, "timed out"); return }
        runCatching {
            val root = BlockScreenService.window()
            if (root != null) {
                val inFs = root.packageName?.toString() == FieldService.PKG
                when {
                    tap(root, SHARE_LABEL) -> {}
                    inFs && "E-mail etc." in tapped.keys && tap(root, "CREATE BACKUP") -> {}
                    inFs && tap(root, "E-mail etc.") -> {}
                    inFs && tap(root, "Backup") -> {}
                    inFs && tap(root, "Open") -> {}
                    !inFs && "share" in tapped.keys -> {}           // the file is on its way to ReceiveBackupActivity
                    !inFs && "CREATE BACKUP" in tapped.keys -> scroll(root)   // the share sheet, our row further down
                }
            }
        }.onFailure { Log.e(TAG, "step failed", it) }
        main.postDelayed({ tick(app) }, STEP_MS)
    }

    /** Tap the node whose text or description is exactly this (or its nearest clickable parent). */
    private fun tap(root: AccessibilityNodeInfo, label: String): Boolean {
        val key = if (label == SHARE_LABEL) "share" else label
        if (System.currentTimeMillis() - (tapped[key] ?: 0L) < 3_000L) return false
        val node = root.findAccessibilityNodeInfosByText(label).firstOrNull {
            it.text?.toString()?.trim().equals(label, true) || it.contentDescription?.toString()?.trim().equals(label, true)
        } ?: return false
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) return false
        tapped[key] = System.currentTimeMillis()
        Log.d(TAG, "tapped $label")
        return true
    }

    private fun scroll(root: AccessibilityNodeInfo) {
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isScrollable) { n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); return }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
    }

    /** ReceiveBackupActivity read the shared file (or couldn't). */
    fun received(context: Context, ok: Boolean) = main.post { done(context.applicationContext, ok, if (ok) "read" else "unreadable") }

    private fun done(app: Context, ok: Boolean, why: String) {
        if (!running) return
        running = false
        Log.d(TAG, "done: $why")
        // Done for today either way: Field Service's own daily backup is the fallback, and he isn't nagged twice.
        prefs(app).edit().putLong("done_day", startOfDay(0)).apply()
        BlockScreenService.goHome()
        Toast.makeText(app, if (ok) "Field Service visits saved." else "Couldn't save the Field Service visits.", Toast.LENGTH_SHORT).show()
        Ask.whereIfWaiting(app)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** For adb (ACTION_TEST_CLEAR): as if today's backup hadn't happened. */
    fun forgetToday(context: Context) = prefs(context).edit().remove("done_day").remove("wanted_day").apply()

    /** For adb (ACTION_TEST_BACKUP): run now, as if he'd just come home from field service. */
    fun test(context: Context) {
        prefs(context).edit().putLong("wanted_day", startOfDay(0)).remove("done_day").apply()
        run(context)
    }
}
