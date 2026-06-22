package com.tolu.dpc

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.util.Calendar

class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESTRICT -> {
                applyRestriction(context)
                scheduleRestrict(context)       // reschedule for tomorrow
            }
            ACTION_UNRESTRICT -> {
                liftRestriction(context)
                scheduleUnrestrict(context)     // reschedule for tomorrow
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                // Alarms don't survive reboots — reschedule both
                scheduleRestrict(context)
                scheduleUnrestrict(context)
                // If we're currently in the window, apply immediately
                if (isInRestrictionWindow()) applyRestriction(context)
            }
        }
    }

    companion object {

        const val ACTION_RESTRICT   = "com.tolu.dpc.ACTION_RESTRICT"
        const val ACTION_UNRESTRICT = "com.tolu.dpc.ACTION_UNRESTRICT"

        private val ALLOWED_PACKAGES = setOf(
            "org.jw.jwlibrary",
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
        }

        fun liftRestriction(context: Context) {
            val (dpm, admin) = dpmAndAdmin(context)
            val toUnsuspend = userPackages(context).toTypedArray()
            if (toUnsuspend.isNotEmpty()) dpm.setPackagesSuspended(admin, toUnsuspend, false)
        }

        fun isInRestrictionWindow(): Boolean {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return hour < 7     // 12AM–7AM
        }

        // ── Internal helpers ──────────────────────────────────────────────────────

        private fun scheduleAlarm(context: Context, action: String, hour: Int, minute: Int) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val trigger = nextOccurrence(hour, minute)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && !am.canScheduleExactAlarms()
            ) {
                // Fallback: near-exact, good enough for a 12AM / 7AM boundary
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntentFor(context, action))
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntentFor(context, action))
            }
        }

        private fun nextOccurrence(hour: Int, minute: Int): Long =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis

        private fun userPackages(context: Context): List<String> =
            context.packageManager.getInstalledPackages(0)
                .filter { pkg ->
                    pkg.applicationInfo?.let {
                        (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    } ?: false
                }
                .map { it.packageName }

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