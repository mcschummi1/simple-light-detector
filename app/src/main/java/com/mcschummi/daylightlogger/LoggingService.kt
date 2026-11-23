package com.mcschummi.daylightlogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Foreground service so logging survives 24+ hours with the screen off.
 * Holds a partial wake lock (CPU keeps running) and shows a persistent
 * "Logging daylight" notification.
 */
class LoggingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Notification channels exist only on Android 8 (API 26)+
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val chan = NotificationChannel(
                CHANNEL_ID, "Daylight logging", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogState.logging = true
        startForeground(DEMO_NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        // Open the camera (or reuse it if the preview already opened it).
        CameraEngine.start(this)
        return START_STICKY   // relaunch if the system kills us overnight
    }

    override fun onDestroy() {
        LogState.logging = false
        releaseWakeLock()
        // Only close the camera if it wasn't needed for the visible preview.
        CameraEngine.stop()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(Context.POWER_SERVICE) as PowerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DaylightLogger::wakelock")
        wakeLock?.acquire(26 * 60 * 60 * 1000L)   // just over 24h as a safety net
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("Daylight Logger")
            .setContentText("Logging daylight samples...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "logging"
        const val DEMO_NOTIFICATION_ID = 1
    }
}
