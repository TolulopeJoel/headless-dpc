package com.tolu.dpc

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserManager
import android.widget.Toast

class SetupActivity : Activity() {

    // ── Config ──────────────────────────────────────────────────────────────────
    // Your NextDNS profile ID. Find it at: nextdns.io → your profile → Setup tab
    // It's the short string in the DoT hostname, e.g. "abc123" from "abc123.dns.nextdns.io"
    private val NEXTDNS_PROFILE_ID = "4e8a9f"
    // ─────────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)

        if (!dpm.isDeviceOwnerApp(packageName)) {
            toast("Not device owner yet. Run the ADB dpm command first.")
            finish()
            return
        }

        val dotHost = "$NEXTDNS_PROFILE_ID.dns.nextdns.io"

        // Set Private DNS to your specific NextDNS profile via DNS-over-TLS.
        // Applies to ALL connections — WiFi, mobile data, everything.
        dpm.setGlobalSetting(admin, "private_dns_mode", "hostname")
        dpm.setGlobalSetting(admin, "private_dns_specifier", dotHost)

        // Lock Private DNS so the user can't change it in Settings.
        // Available since API 29 (Android 10). Uses the official constant — no raw strings.
        dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)

        // Disable this activity permanently — app goes fully headless.
        packageManager.setComponentEnabledSetting(
            ComponentName(this, SetupActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )

        toast("Done. Private DNS locked to $dotHost")
        finish()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}