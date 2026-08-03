package com.tolu.dpc

import android.app.Activity
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.widget.Toast

class SetupActivity : Activity() {

    private val NEXTDNS_PROFILE_ID = "4e8a9f"
    private var waitingForAlarmPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager

        // ── Guard: must be device owner ──────────────────────────────────────────
        if (!dpm.isDeviceOwnerApp(packageName)) {
            toast("Not device owner. Run: adb shell dpm set-device-owner com.tolu.dpc/.AdminReceiver")
            finish()
            return
        }

        // ── Guard: must have exact alarm permission ───────────────────────────────
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!am.canScheduleExactAlarms()) {
            waitingForAlarmPermission = true
            toast("Grant 'Alarms & Reminders' permission, then come back.")
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            // Don't finish — stay alive so onResume can pick up after the user grants
            return
        }

        runSetup()
    }

    override fun onResume() {
        super.onResume()
        if (!waitingForAlarmPermission) return

        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (am.canScheduleExactAlarms()) {
            waitingForAlarmPermission = false
            runSetup()
        } else {
            toast("Permission not granted yet — please allow 'Alarms & Reminders'.")
        }
    }

    private fun runSetup() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)

        // ── Private DNS ──────────────────────────────────────────────────────────
        val dotHost = "$NEXTDNS_PROFILE_ID.dns.nextdns.io"
        dpm.setGlobalSetting(admin, "private_dns_mode", "hostname")
        dpm.setGlobalSetting(admin, "private_dns_specifier", dotHost)
        dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)

        // ── Scheduling ───────────────────────────────────────────────────────────
        ScheduleReceiver.applyCurrentState(this)
        ScheduleReceiver.scheduleAlarms(this)

        // ── Go headless ──────────────────────────────────────────────────────────
        packageManager.setComponentEnabledSetting(
            ComponentName(this, SetupActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )

        toast("Done. DNS locked to $dotHost. Focus window active: 12AM–7AM.")
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}