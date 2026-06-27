package com.kidmode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * Foreground service that:
 *  1. Enables system-wide grayscale (if WRITE_SECURE_SETTINGS was granted via ADB).
 *  2. Launches / re-launches BoringActivity when Kid Mode is active.
 *  3. Cleans up when the parent unlocks with their PIN.
 */
class KidModeService : Service() {

    companion object {
        const val ACTION_START = "com.kidmode.START"
        const val ACTION_STOP  = "com.kidmode.STOP"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "kidmode_channel"

        /** Polled by the watchdog coroutine to know if BoringActivity is on-screen. */
        @Volatile var boringActivityVisible = false
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> activateKidMode()
            ACTION_STOP  -> deactivateKidMode()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Kid Mode activation ───────────────────────────────────────────────────

    private fun activateKidMode() {
        startForeground(NOTIFICATION_ID, buildNotification())
        PrefsManager.setActive(this, true)
        enableGrayscale()
        launchBoringActivity()
        startWatchdog()
    }

    private fun deactivateKidMode() {
        PrefsManager.setActive(this, false)
        disableGrayscale()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Grayscale (requires WRITE_SECURE_SETTINGS granted via ADB) ────────────

    private fun enableGrayscale() {
        try {
            Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer_enabled", 1)
            Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer", 0) // 0 = monochrome
        } catch (_: SecurityException) {
            // Permission not granted — BoringActivity uses its own window-layer grayscale
        }
    }

    private fun disableGrayscale() {
        try {
            Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer_enabled", 0)
        } catch (_: SecurityException) { /* ignore */ }
    }

    // ── Watchdog: re-launch BoringActivity if it was minimized ───────────────

    private fun startWatchdog() {
        scope.launch {
            delay(3_000) // give the activity time to appear first
            while (isActive && PrefsManager.isActive(this@KidModeService)) {
                if (!boringActivityVisible) {
                    launchBoringActivity()
                }
                delay(2_000)
            }
        }
    }

    private fun launchBoringActivity() {
        val intent = Intent(this, BoringActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kid Mode is active")
            .setContentText("Enter parent PIN to disable")
            .setSmallIcon(R.drawable.ic_tile)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Kid Mode",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while Kid Mode is restricting the screen"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
