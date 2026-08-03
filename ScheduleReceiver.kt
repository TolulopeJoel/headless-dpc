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
            ACTION_RESTRICT -> {
                // try/finally: the reschedule MUST happen even if applyRestriction throws
                // (e.g. transient DPM/binder error, device-owner state hiccup). Previously
                // an exception here silently killed the entire alarm chain for good —
                // nothing would fire again until the next reboot.
                try {
                    applyRestriction(context)
                } catch (e: Exception) {
                    Log.e(TAG, "applyRestriction failed", e)
                } finally {
                    scheduleRestrict(context)       // reschedule for tomorrow, no matter what
                }
            }
            ACTION_UNRESTRICT -> {
                try {
                    liftRestriction(context)
                } catch (e: Exception) {
                    Log.e(TAG, "liftRestriction failed", e)
                } finally {
                    scheduleUnrestrict(context)     // reschedule for tomorrow, no matter what
                }
            }
            // Group boot, time-change, AND app-update events together.
            // MY_PACKAGE_REPLACED matters because an app update (e.g. you push a new
            // build via adb install -r) can silently drop previously-set exact alarms
            // on some OEM builds — same failure mode as a reboot, easy to miss.
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                try {
                    scheduleRestrict(context)
                    scheduleUnrestrict(context)

                    // USE applyCurrentState HERE!
                    // This ensures if it is 3:00 PM, it will force the apps to un-suspend.
                    applyCurrentState(context)

                    // Re-arm the WorkManager watchdog too — if its own persisted
                    // schedule got wiped for any reason, this recreates it.
                    WatchdogWorker.enqueue(context)

                    Log.d(TAG, "Full re-arm complete on ${intent.action}")
                } catch (e: Exception) {
                    Log.e(TAG, "Re-arm failed on ${intent.action}", e)
                }
            }
        }
    }

    companion object {

        private const val TAG = "dpc.ScheduleReceiver"

        const val ACTION_RESTRICT   = "com.tolu.dpc.ACTION_RESTRICT"
        const val ACTION_UNRESTRICT = "com.tolu.dpc.ACTION_UNRESTRICT"

        private val ALLOWED_PACKAGES = setOf(
            "org.jw.jwlibrary.mobile",
            "com.asaro.meditation",
            "com.transsnet.palmpay",
            "com.tolu.dpc"          // never suspend the DPC itself
        )

        // ── Public entry points ───────────────────────────────────────────────────

        fun scheduleRestrict(context: Context)   = scheduleAlarm(context, ACTION_RESTRICT,   0, 0)
        fun scheduleUnrestrict(context: Context) = scheduleAlarm(context, ACTION_UNRESTRICT, 7, 0)

        fun cancelAlarms(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntentFor(context, ACTION_RESTRICT))
            am.cancel(pendingIntentFor(context, ACTION_UNRESTRICT))
        }

        fun unrestrict(context: Context) = liftRestriction(context)

        fun applyCurrentState(context: Context) {
            if (isInRestrictionWindow()) {
                applyRestriction(context)
            } else {
                liftRestriction(context)
            }
        }

        fun scheduleAlarms(context: Context) {
            scheduleRestrict(context)
            scheduleUnrestrict(context)
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
            return hour < 7     // 12AM–7AM
        }

        // ── Internal helpers ──────────────────────────────────────────────────────

        private fun scheduleAlarm(context: Context, action: String, hour: Int, minute: Int) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val trigger = nextOccurrence(hour, minute)
            val pendingIntent = pendingIntentFor(context, action)

            // Using AlarmClockInfo guarantees exact execution to the millisecond,
            // bypassing Doze mode and manufacturer battery constraints.
            val alarmClockInfo = AlarmManager.AlarmClockInfo(trigger, pendingIntent)
            am.setAlarmClock(alarmClockInfo, pendingIntent)
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

            // 1. Find your default home screen launcher so we don't accidentally suspend it
            val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName

            // 2. Get every app that has a clickable icon in the app drawer
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }

            return pm.queryIntentActivities(launcherIntent, 0)
                .map { it.activityInfo.packageName }
                .filter { it != defaultLauncher } // Keep the home screen alive!
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
