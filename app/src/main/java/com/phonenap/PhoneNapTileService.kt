package com.phonenap

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class PhoneNapTileService : TileService() {

    companion object {
        const val ACTION_REFRESH_TILE = "com.phonenap.REFRESH_TILE"
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) { refreshTile() }
    }

    override fun onStartListening() {
        super.onStartListening()
        val filter = IntentFilter(ACTION_REFRESH_TILE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
        }
        refreshTile()
    }

    override fun onStopListening() {
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()

        if (!isAccessibilityEnabled()) {
            // Guide parent to enable the accessibility service first
            launchActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        if (PrefsManager.isActive(this)) {
            // Already ON — parent must use biometric to turn it off
            launchActivity(
                Intent(this, BiometricUnlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            // Activate PhoneNap
            sendBroadcast(
                Intent(PhoneNapAccessibilityService.ACTION_ACTIVATE).setPackage(packageName)
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun refreshTile() {
        val tile   = qsTile ?: return
        val active = PrefsManager.isActive(this)
        val ready  = isAccessibilityEnabled()
        tile.state = when {
            !ready -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else   -> Tile.STATE_INACTIVE
        }
        tile.label = if (active) "PhoneNap ON" else "PhoneNap"
        tile.updateTile()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = "$packageName/${PhoneNapAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(target, ignoreCase = true) }
    }

    @Suppress("DEPRECATION")
    private fun launchActivity(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
