package com.phonenap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = PrefsManager(context)
        if (prefs.isSetupComplete()) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneNapService::class.java)
            )
        }
    }
}
