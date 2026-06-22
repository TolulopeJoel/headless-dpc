package com.tolu.dpc

import android.app.Activity
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.widget.Toast

class SetupActivity : Activity() {

    private val NEXTDNS_PROFILE_ID = "4e8a9f"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)

        if (!dpm.isDeviceOwnerApp(packageName)) {
            toast("Not device owner. Run: adb shell dpm set-device-owner com.tolu.dpc/.AdminReceiver")
            finish()
            return
        }

        // ── Private DNS ─────────────────────────────────────────────────────────
        val dotHost = "$NEXTDNS_PROFILE_ID.dns.nextdns.io"

        // Set Private DNS to your specific NextDNS profile via DNS-over-TLS.
        // Applies to ALL connections — WiFi, mobile data, everything.
        dpm.setGlobalSetting(admin, "private_dns_mode", "hostname")
        dpm.setGlobalSetting(admin, "private_dns_specifier", dotHost)

        // Lock Private DNS so the user can't change it in Settings.
        // Available since API 29 (Android 10). Uses the official constant — no raw strings.
        dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)

        // ── App focus window (12AM–7AM: only allowed apps) ───────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                // DNS is already locked above. User must grant this permission
                // then re-run SetupActivity to activate scheduling.
                toast("DNS locked. To activate focus window: Settings → Apps → Special app access → Alarms & Reminders → enable for this app. Then re-run SetupActivity.")
                finish()
                return
            }
        }

        ScheduleReceiver.applyCurrentState(this)
        ScheduleReceiver.scheduleAlarms(this)

        // ── Go headless ─────────────────────────────────────────────────────────
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