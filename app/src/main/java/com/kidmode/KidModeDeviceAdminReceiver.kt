package com.kidmode

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Required to call DevicePolicyManager.lockNow() after the dimming period.
 * Enable via: Settings → Security → Device admin apps → Kid Mode
 * (or MainActivity will prompt the user automatically).
 */
class KidModeDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Kid Mode: lock permission granted", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Kid Mode: lock permission revoked", Toast.LENGTH_SHORT).show()
    }
}
