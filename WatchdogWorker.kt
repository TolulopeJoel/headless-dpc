package com.tolu.dpc

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Belt-and-suspenders watchdog for ScheduleReceiver.
 *
 * ScheduleReceiver's exact alarms (AlarmManager.setAlarmClock) are the primary
 * mechanism and should be reliable on their own — but they depend on one broadcast
 * successfully reaching one receiver at exactly the right moment. If HiOS's
 * proprietary background-freezer (which sits ABOVE stock Android Doze and doesn't
 * necessarily respect Device Owner exemptions) kills the process before that
 * broadcast is handled, the whole chain can go silent with nothing to signal it.
 *
 * WorkManager is backed by JobScheduler, a genuinely different OS subsystem from
 * AlarmManager broadcasts. It won't survive every possible OEM restriction either,
 * but it fails independently — so the two mechanisms are unlikely to be killed by
 * the exact same failure at the exact same time. Every 15 minutes (WorkManager's
 * minimum periodic interval) this:
 *   1. re-evaluates the current time against the restriction window and corrects
 *      suspension state if it's wrong (self-healing, not just a re-check)
 *   2. re-arms tomorrow's AlarmManager alarms unconditionally (idempotent —
 *      setAlarmClock with the same request code just overwrites)
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            ScheduleReceiver.applyCurrentState(applicationContext)
            ScheduleReceiver.scheduleAlarms(applicationContext)
            Log.d(TAG, "Watchdog run OK at ${System.currentTimeMillis()}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog run failed", e)
            // retry with WorkManager's default backoff rather than silently giving up
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "dpc.WatchdogWorker"
        private const val WORK_NAME = "dpc_watchdog"

        /**
         * Idempotent — safe to call from setup, boot, package-replaced, or anywhere
         * else you want to guarantee the watchdog is armed. KEEP means if it's
         * already scheduled, this is a no-op rather than resetting the cadence.
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Watchdog enqueued")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Watchdog cancelled")
        }
    }
}
