package com.phonecam

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var address: TextView
    private lateinit var status: TextView
    private lateinit var pairing: TextView
    private lateinit var toggle: Button
    private lateinit var lens: Spinner
    private lateinit var preset: Spinner
    private var currentUrl = ""
    private var pending = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastFrames = 0L
    private var cachedIp: String? = null
    private var ticks = 0
    private val poll = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(Color.rgb(16, 24, 32))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        // Android 15+ enforces edge-to-edge for targetSdk 35+. Keep all controls out of system bars.
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(dp(24), dp(20) + insets.systemWindowInsetTop, dp(24), dp(20) + insets.systemWindowInsetBottom)
            insets
        }
        fun text(value: String, size: Float, color: Int = Color.WHITE): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }
        text("PhoneCam", 34f)
        text("Your phone as a PC webcam", 16f, Color.rgb(160, 186, 200))
        text("1  Connect the phone and PC to the same Wi-Fi network.\n2  Pick a camera and tap “Start camera”.\n3  Open PhoneCam.exe and enter the code.", 15f)
        text("Camera", 14f, Color.LTGRAY)
        fun spinner(items: Array<String>): Spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            root.addView(this, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) })
        }
        lens = spinner(arrayOf("Front", "Back"))
        text("Quality and load", 14f, Color.LTGRAY)
        preset = spinner(arrayOf("Balanced · 720p / up to 30 fps", "Saver · 480p / up to 15 fps", "Detail · 720p / up to 30 fps, JPEG 90"))
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        lens.setSelection(prefs.getInt("lens", 0).coerceIn(0, 1))
        preset.setSelection(prefs.getInt("preset", 0).coerceIn(0, 2))
        toggle = Button(this).apply { text = "Start camera"; root.addView(this, LinearLayout.LayoutParams(-1, dp(56))) }
        status = text("Camera is off", 16f, Color.rgb(91, 220, 180))
        pairing = text("The code appears after you start", 24f)
        address = text("", 14f, Color.LTGRAY).apply { setTextIsSelectable(true) }
        Button(this).apply {
            text = "Copy browser link"
            root.addView(this, LinearLayout.LayoutParams(-1, dp(52)))
            setOnClickListener {
                if (currentUrl.isNotEmpty()) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PhoneCam", currentUrl))
                    Toast.makeText(this@MainActivity, "Link copied", Toast.LENGTH_SHORT).show()
                }
            }
        }
        text("Turn the phone sideways for a wide frame. You can turn the screen off: streaming continues. To stop, use the button here or in the notification.\n\nVideo is sent over the local network without encryption. Use a trusted Wi-Fi network. The microphone is not streamed.", 13f, Color.rgb(160, 186, 200))
        toggle.setOnClickListener {
            if (StreamService.running || StreamService.starting) {
                stopService(Intent(this, StreamService::class.java)); pending = false
            } else if (!pending) begin()
            refresh()
        }
    }
    override fun onResume() { super.onResume(); handler.removeCallbacks(poll); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }
    private fun begin() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1); return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2); return
        }
        launch()
    }
    private fun launch() {
        pending = true
        val selected = preset.selectedItemPosition
        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("lens", lens.selectedItemPosition).putInt("preset", selected).apply()
        val intent = Intent(this, StreamService::class.java)
            .putExtra("lens", lens.selectedItemPosition)
            .putExtra("width", if (selected == 1) 640 else 1280)
            .putExtra("fps", if (selected == 1) 15 else 30)
            .putExtra("quality", if (selected == 2) 90 else if (selected == 1) 65 else 75)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            handler.postDelayed({ pending = false; refresh() }, 2000)
        } catch (e: Exception) { pending = false; Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 1) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin()
            else Toast.makeText(this, "Allow camera access to stream video", Toast.LENGTH_LONG).show()
        } else if (requestCode == 2) launch()
    }
    private fun refresh() {
        if (ticks++ % 5 == 0) cachedIp = localIpv4()
        val active = StreamService.running || StreamService.starting || pending
        lens.isEnabled = !active; preset.isEnabled = !active
        toggle.text = if (active) "Stop camera" else "Start camera"
        toggle.isEnabled = !pending
        pairing.text = if (active && StreamService.code.isNotEmpty()) "Code  ${StreamService.code}" else "The code appears after you start"
        currentUrl = if (active && cachedIp != null) "http://$cachedIp:${StreamService.PORT}/?code=${StreamService.code}" else ""
        address.text = if (cachedIp == null) "No Wi-Fi address. Connect to a network." else "Phone address: $cachedIp:${StreamService.PORT}\n$currentUrl"
        val frames = StreamService.frames
        status.text = when {
            StreamService.lastError != null -> "Error: ${StreamService.lastError}"
            StreamService.starting || pending -> "Starting camera…"
            StreamService.running -> "Streaming · ${(frames - lastFrames).coerceAtLeast(0)} fps · viewers: ${StreamService.viewers}"
            else -> "Camera is off"
        }
        lastFrames = frames
    }
}
