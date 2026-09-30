package com.tolu.dpc

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Android shows its own "Blocked by work policy" dialog for a locked app, and a device owner can't change its words.
 * This closes that dialog as soon as it appears and opens BlockedActivity, which says why and until when.
 */
class BlockScreenService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.className?.toString() != ADMIN_DIALOG) return
        performGlobalAction(GLOBAL_ACTION_BACK)
        // After the dialog has gone, or the back press would close ours instead.
        handler.postDelayed({
            startActivity(Intent(this, BlockedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }, 150)
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
    }

    override fun onDestroy() {
        running = null
        super.onDestroy()
    }

    companion object {
        private const val ADMIN_DIALOG = "com.android.settings.enterprise.ActionDisabledByAdminDialog"

        @Volatile private var running: BlockScreenService? = null

        /** Locks the screen the way the power button does, for "Goodnight". False if the service isn't running. */
        fun lockScreen(): Boolean = running?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
    }
}
