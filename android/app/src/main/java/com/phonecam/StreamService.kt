package com.phonecam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.lifecycle.LifecycleService

/**
 * Keeps the camera and the MJPEG server alive while the app is in the
 * background.
 *
 * A foreground service rather than a plain one, because Android suspends
 * background work and a webcam that dies the moment you switch apps is useless.
 * The `camera` foreground type (see the manifest) is what keeps the camera
 * grant from being revoked when the app loses the foreground.
 *
 * Extends [LifecycleService] because CameraX binds to a LifecycleOwner and a
 * plain Service is not one.
 */
class StreamService : LifecycleService() {

    companion object {
        const val PORT = 8080
        @Volatile var starting = false; private set
        @Volatile var code = ""; private set
        @Volatile var frames = 0L; private set

        private const val CHANNEL_ID = "phonecam.stream"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "com.phonecam.STOP"

        /** Polled by MainActivity. Set when streaming stopped for a reason. */
        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var running: Boolean = false
            private set

        /** How many viewers are attached. The first thing to check when the
         *  picture has not appeared on the PC. */
        @Volatile
        var viewers: Int = 0
            private set
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var server: MjpegServer? = null
    private var camera: CameraSource? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            viewers = server?.clientCount ?: 0
            frames = server?.frameCount ?: 0
            if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
            handler.postDelayed(this, 1000)
        }
    }

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

        startForegroundCompat()

        if (server == null) startStreaming(intent)

        // Unconditional: the camera reports readiness asynchronously, so
        // `running` is still false here on a healthy start.
        handler.removeCallbacks(poll)
        handler.post(poll)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        // The notification is ongoing, so it does not clear itself with the
        // service the way a normal one would.
        stopForeground(STOP_FOREGROUND_REMOVE)
        running = false
        starting = false
        viewers = 0
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        camera?.stop()
        camera = null
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startStreaming(intent: Intent?) {
        lastError = null
        starting = true
        code = (100000 + java.security.SecureRandom().nextInt(900000)).toString()
        val s = MjpegServer(PORT, code)
        try {
            s.start()
        } catch (e: Exception) {
            // Almost always the port being taken by something else on the
            // phone, which is worth saying out loud rather than showing as a
            // camera that simply never works.
            lastError = "port $PORT: ${e.message}"
            stopSelf()
            return
        }
        server = s
        val power = getSystemService(android.os.PowerManager::class.java)
        wakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PhoneCam:stream").apply { setReferenceCounted(false); acquire(10 * 60 * 1000L) }

        val c = CameraSource(this, this,
            intent?.getIntExtra("lens", 0) ?: 0,
            intent?.getIntExtra("width", 1280) ?: 1280,
            intent?.getIntExtra("fps", 30) ?: 30,
            intent?.getIntExtra("quality", 75) ?: 75,
        ) { frame, rotation -> s.submit(frame, rotation) }
        camera = c
        c.start(
            onReady = { starting = false; running = true },
            onError = { message ->
                lastError = message
                stopSelf()
            },
        )
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("port $PORT")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }
}
