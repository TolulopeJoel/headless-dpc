package com.tolu.dpc

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Bundle
import android.os.UserManager
import android.widget.Toast

class RemoveActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            // Cancel scheduled alarms before anything else
            ScheduleReceiver.cancelAlarms(this)

            // Unsuspend all apps
            ScheduleReceiver.unrestrict(this)

            // Remove user restrictions
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)

            // Clear device owner
            dpm.clearDeviceOwnerApp(packageName)
            toast("Device owner cleared.")
        } else {
            toast("Not device owner.")
        }
        finish()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}