package com.tolu.dpc

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Android shows its own "Blocked by work policy" dialog for a locked app, and a device owner can't change its words.
 * This closes that dialog as soon as it appears and opens BlockedActivity, which says why and until when.
 * It also tells ColourKeeper which app is in front (the package name only; no window content is read).
 */
class BlockScreenService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // The always-open tools (Recorder, Notepad, Calculator) get 4 minutes at a time, then rest for 5.
    private var toolFront: String? = null
    private var toolSince = 0L

    private fun toolPrefs() = getSharedPreferences("focus", MODE_PRIVATE)
    private fun toolResting(pkg: String) = System.currentTimeMillis() < toolPrefs().getLong("tool_until_$pkg", 0L)

    private fun toolName(pkg: String) = when (pkg) {
        "com.transsion.soundrecorder" -> "Recorder"
        "com.transsion.notebook" -> "Notepad"
        else -> "Calculator"
    }

    /** 4 minutes straight in one tool: it closes and rests for 5. A recording keeps going; only the screen closes. */
    private fun checkTool() {
        val pkg = toolFront ?: return
        val interactive = getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true
        if (!interactive || android.os.SystemClock.elapsedRealtime() - toolSince < TOOL_LIMIT_MS) return
        val until = System.currentTimeMillis() + TOOL_REST_MS
        toolPrefs().edit().putLong("tool_until_$pkg", until).apply()
        toolFront = null
        performGlobalAction(GLOBAL_ACTION_HOME)
        android.widget.Toast.makeText(this, "Four minutes. ${toolName(pkg)} rests until ${clock(until)}.", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun clock(ms: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

    private val colourTick = object : Runnable {
        override fun run() {
            checkTool()
            ColourKeeper.tick(this@BlockScreenService)
            handler.postDelayed(this, 30_000)
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            ColourKeeper.onScreen(context, on)
            handler.removeCallbacks(colourTick)
            if (on) handler.postDelayed(colourTick, 30_000)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // HiOS's Hiber freezes the DPC while the screen is off, drops its alarms and holds its broadcasts. Accessibility
        // events aren't held, so the first one after a freeze puts the phone right at once (phase, alarms, tides).
        if (android.os.SystemClock.elapsedRealtime() - ScheduleReceiver.lastEnforceAt > 60_000) {
            runCatching { ScheduleReceiver.enforce(this) }
        }
        if (event.className?.toString() != ADMIN_DIALOG) {
            val pkg = event.packageName?.toString()
            // The tools: turned away while resting; otherwise their 4 minutes start when they come to the front.
            if (pkg != null && pkg in TOOLS) {
                if (toolResting(pkg)) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    val until = toolPrefs().getLong("tool_until_$pkg", 0L)
                    android.widget.Toast.makeText(this, "${toolName(pkg)} is resting until ${clock(until)}.", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                if (toolFront != pkg) { toolFront = pkg; toolSince = android.os.SystemClock.elapsedRealtime() }
            } else if (pkg != null && pkg !in PASSING && !pkg.contains("inputmethod") && !pkg.contains("keyboard")) {
                toolFront = null   // left the tool (the keyboard and notifications don't count as leaving)
            }
            // An app that should be locked but that Android wouldn't suspend (Play Store, Settings at night): turned away.
            if (pkg != null && pkg in ScheduleReceiver.softLocked && !isCall(event) && !ColourKeeper.inCall(this)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed({
                    startActivity(Intent(this, BlockedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }, 150)
                return
            }
            // WhatsApp statuses are off: the status player is closed the moment it opens. Chats and calls are untouched.
            if (pkg != null && pkg in WHATSAPP && isStatusPlayer(event)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                android.widget.Toast.makeText(this, "Statuses are off o. Your chats are open.", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            // A soft tide (WhatsApp): turn it away at the door, unless it's a call, which always comes through.
            if (pkg != null && pkg in ColourKeeper.SOFT && pkg in ColourKeeper.tidedApps(this) && !isCall(event) && !ColourKeeper.inCall(this)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed({
                    startActivity(Intent(this, BlockedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }, 150)
                return
            }
            ColourKeeper.onForeground(this, pkg)
            return
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        // After the dialog has gone, or the back press would close ours instead.
        handler.postDelayed({
            startActivity(Intent(this, BlockedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }, 150)
    }

    /**
     * WhatsApp's player for other people's statuses (com.whatsapp.status.playback.StatusPlaybackActivity, checked in the
     * installed WhatsApp Business 2.26.37.73). Only the player: posting your own status and replying stay open.
     */
    private fun isStatusPlayer(event: AccessibilityEvent): Boolean =
        event.className?.toString()?.endsWith(".StatusPlaybackActivity") == true

    /** WhatsApp's call screens (ringing, in a call) are named for VoIP; a call is never turned away. */
    private fun isCall(event: AccessibilityEvent) = event.className?.toString()?.contains("voip", ignoreCase = true) == true

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        handler.postDelayed(colourTick, 30_000)
    }

    override fun onDestroy() {
        running = null
        handler.removeCallbacks(colourTick)
        runCatching { unregisterReceiver(screen) }
        super.onDestroy()
    }

    companion object {
        private const val ADMIN_DIALOG = "com.android.settings.enterprise.ActionDisabledByAdminDialog"
        private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val TOOLS = setOf("com.transsion.soundrecorder", "com.transsion.notebook", "com.transsion.calculator")
        /** Windows that pass over whatever is open without meaning you left it. */
        private val PASSING = setOf("com.android.systemui", "com.tolu.dpc", "android")
        private const val TOOL_LIMIT_MS = 4 * 60_000L
        private const val TOOL_REST_MS = 5 * 60_000L

        @Volatile private var running: BlockScreenService? = null

        /** Sends the phone home and shows the tide screen: for a soft tide that starts while its app is open. */
        fun turnAway(): Boolean {
            val service = running ?: return false
            service.performGlobalAction(GLOBAL_ACTION_HOME)
            service.handler.postDelayed({
                service.startActivity(Intent(service, BlockedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }, 150)
            return true
        }

        /** Locks the screen the way the power button does, for "Goodnight". False if the service isn't running. */
        fun lockScreen(): Boolean = running?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
    }
}
