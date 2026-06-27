package com.kidmode

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile.
 * - Tap when OFF  → activates Kid Mode
 * - Tap when ON   → deactivates Kid Mode immediately (no PIN)
 */
class KidModeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()

        if (PrefsManager.isActive(this)) {
            // Kid Mode is on — turn it off directly
            startService(Intent(this, KidModeService::class.java).apply {
                action = KidModeService.ACTION_STOP
            })
            BoringActivity.dismiss()
            refreshTile()
            return
        }

        // Start foreground service then launch BoringActivity
        startForegroundService(Intent(this, KidModeService::class.java).apply {
            action = KidModeService.ACTION_START
        })

        val boringIntent = Intent(this, BoringActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        launchActivity(boringIntent)
        refreshTile()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun refreshTile() {
        val tile = qsTile ?: return
        val active = PrefsManager.isActive(this)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "Kid Mode ON" else "Kid Mode"
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun launchActivity(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
