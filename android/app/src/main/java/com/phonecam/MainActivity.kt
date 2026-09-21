package com.phonecam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * One screen: the address to open, whether anyone is watching, and a button.
 *
 * The state lives in [StreamService] rather than here, because the service
 * outlives this activity — the app is expected to be closed while the stream
 * keeps running. So the UI polls the service's companion fields once a second
 * instead of trying to mirror them.
 */
class MainActivity : Activity() {

    private lateinit var urlView: TextView
    private lateinit var statusView: TextView
    private lateinit var toggle: Button

    private var cachedIp: String? = null
    private var ipAge = 0

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlView = findViewById(R.id.url)
        statusView = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)

        toggle.setOnClickListener {
            if (StreamService.running) {
                stopService(Intent(this, StreamService::class.java))
            } else {
                beginStreaming()
            }
        }

        // The address is only useful while the screen is on, and a phone acting
        // as a webcam is normally left alone on a desk.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun beginStreaming() {
        if (!has(Manifest.permission.CAMERA)) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        // Not fatal if refused: the service still runs, just without a visible
        // notification.
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        launchService()
    }

    private fun launchService() {
        val intent = Intent(this, StreamService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            launchService()
        } else {
            Toast.makeText(this, R.string.camera_needed, Toast.LENGTH_LONG).show()
        }
    }

    private fun has(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Enumerating network interfaces is not free, and the address almost never
     * changes, so it is re-read every few seconds rather than on every tick.
     */
    private fun currentIp(): String? {
        if (ipAge <= 0) {
            cachedIp = localIpv4()
            ipAge = IP_REFRESH_TICKS
        }
        ipAge--
        return cachedIp
    }

    private fun refresh() {
        val ip = currentIp()
        urlView.text = if (ip == null) {
            getString(R.string.no_network)
        } else {
            getString(R.string.url_fmt, ip, StreamService.PORT)
        }

        val error = StreamService.lastError
        statusView.text = when {
            error != null -> getString(R.string.status_error, error)
            StreamService.running -> getString(R.string.status_live, StreamService.viewers)
            else -> getString(R.string.status_idle)
        }

        toggle.setText(if (StreamService.running) R.string.stop else R.string.start)
    }

    private companion object {
        const val REQUEST_CAMERA = 1
        const val REQUEST_NOTIFICATIONS = 2
        const val IP_REFRESH_TICKS = 5
    }
}
