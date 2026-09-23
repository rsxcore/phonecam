package com.phonecam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.lifecycle.LifecycleService

/**
 * Foreground service that keeps [Streamer] alive while the phone screen is off
 * or another app is on top. The `camera` foreground type keeps the camera
 * grant; the Wi-Fi lock stops power saving from adding latency spikes, which
 * is a common cause of stutter on otherwise good networks.
 */
class StreamService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneCam:stream")
                .apply { setReferenceCounted(false); acquire() }
            @Suppress("DEPRECATION")
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PhoneCam:stream")
                .apply { setReferenceCounted(false); acquire() }
        }
        Streamer.start(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Streamer.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_ID = "phonecam.stream"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.phonecam.STOP"

        fun start(context: Context) = context.startForegroundService(Intent(context, StreamService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, StreamService::class.java))
    }
}
