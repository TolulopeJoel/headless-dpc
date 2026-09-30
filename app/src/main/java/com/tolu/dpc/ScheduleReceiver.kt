package com.tolu.dpc

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import java.util.Calendar

class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: ${intent.action}")

        when (intent.action) {
            ACTION_RESTRICT -> runLogged("applyRestriction") { applyRestriction(context) }
            ACTION_UNRESTRICT -> runLogged("liftRestriction") { liftRestriction(context) }
            // Boot, time/zone change, app update: work out the correct state from the clock.
            else -> runLogged("applyCurrentState") { applyCurrentState(context) }
        }

        // Every path re-arms both alarms and restarts the keep-alive service.
        // HiOS's Hiber freezer drops the alarms of a frozen app, so nothing may rely on one alarm surviving.
        runLogged("scheduleAlarms") { scheduleAlarms(context) }
        KeepAliveService.start(context)
    }

    companion object {

        private const val TAG = "dpc.ScheduleReceiver"

        const val ACTION_RESTRICT   = "com.tolu.dpc.ACTION_RESTRICT"
        const val ACTION_UNRESTRICT = "com.tolu.dpc.ACTION_UNRESTRICT"

        private const val RESTRICT_HOUR = 0
        private const val UNRESTRICT_HOUR = 7

        private val ALLOWED_PACKAGES = setOf(
            "org.jw.jwlibrary.mobile",
            "com.asaro.meditation",
            "com.transsnet.palmpay",
            "com.tolu.dpc"          // never suspend the DPC itself
        )

        // ── Public entry points ───────────────────────────────────────────────────

        /** Idempotent: correct the suspension state and re-arm both alarms. Safe to call as often as you like. */
        fun enforce(context: Context) {
            runLogged("applyCurrentState") { applyCurrentState(context) }
            runLogged("scheduleAlarms") { scheduleAlarms(context) }
        }

        fun scheduleRestrict(context: Context)   = scheduleAlarm(context, ACTION_RESTRICT,   RESTRICT_HOUR, 0)
        fun scheduleUnrestrict(context: Context) = scheduleAlarm(context, ACTION_UNRESTRICT, UNRESTRICT_HOUR, 0)

        fun scheduleAlarms(context: Context) {
            scheduleRestrict(context)
            scheduleUnrestrict(context)
        }

        fun cancelAlarms(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntentFor(context, ACTION_RESTRICT))
            am.cancel(pendingIntentFor(context, ACTION_UNRESTRICT))
        }

        fun unrestrict(context: Context) = liftRestriction(context)

        fun applyCurrentState(context: Context) {
            if (isInRestrictionWindow()) applyRestriction(context) else liftRestriction(context)
        }

        fun applyRestriction(context: Context) {
            val (dpm, admin) = dpmAndAdmin(context)
            val toSuspend = userPackages(context)
                .filter { it !in ALLOWED_PACKAGES }
                .toTypedArray()
            if (toSuspend.isNotEmpty()) dpm.setPackagesSuspended(admin, toSuspend, true)
            Log.d(TAG, "applyRestriction: suspended ${toSuspend.size} packages")
        }

        fun liftRestriction(context: Context) {
            val (dpm, admin) = dpmAndAdmin(context)
            val toUnsuspend = userPackages(context).toTypedArray()
            if (toUnsuspend.isNotEmpty()) dpm.setPackagesSuspended(admin, toUnsuspend, false)
            Log.d(TAG, "liftRestriction: unsuspended ${toUnsuspend.size} packages")
        }

        fun isInRestrictionWindow(): Boolean {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return hour in RESTRICT_HOUR until UNRESTRICT_HOUR     // 12AM–7AM
        }

        // ── Internal helpers ──────────────────────────────────────────────────────

        private inline fun runLogged(what: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "$what failed", e)
            }
        }

        private fun scheduleAlarm(context: Context, action: String, hour: Int, minute: Int) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val trigger = nextOccurrence(hour, minute)
            val pendingIntent = pendingIntentFor(context, action)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, pendingIntent), pendingIntent)
            Log.d(TAG, "scheduleAlarm: $action -> $trigger")
        }

        private fun nextOccurrence(hour: Int, minute: Int): Long =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis

        private fun userPackages(context: Context): List<String> {
            val pm = context.packageManager

            // Never suspend the home screen launcher.
            val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName

            val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            return pm.queryIntentActivities(launcherIntent, 0)
                .map { it.activityInfo.packageName }
                .filter { it != defaultLauncher }
                .distinct()
        }

        private fun dpmAndAdmin(context: Context): Pair<DevicePolicyManager, ComponentName> {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, AdminReceiver::class.java)
            return dpm to admin
        }

        fun pendingIntentFor(context: Context, action: String): PendingIntent {
            val intent = Intent(context, ScheduleReceiver::class.java).apply { this.action = action }
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
