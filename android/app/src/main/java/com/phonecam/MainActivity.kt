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
        text("Твой телефон — камера для ПК", 16f, Color.rgb(160, 186, 200))
        text("1  Подключи телефон и ПК к одной Wi-Fi сети.\n2  Выбери камеру и нажми «Включить».\n3  Открой PhoneCam.exe и введи код.", 15f)
        text("Камера", 14f, Color.LTGRAY)
        fun spinner(items: Array<String>): Spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            root.addView(this, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) })
        }
        lens = spinner(arrayOf("Фронтальная", "Основная"))
        text("Качество и нагрузка", 14f, Color.LTGRAY)
        preset = spinner(arrayOf("Баланс · 720p / до 30 fps", "Экономия · 480p / до 15 fps", "Детали · 720p / до 30 fps, JPEG 90"))
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        lens.setSelection(prefs.getInt("lens", 0).coerceIn(0, 1))
        preset.setSelection(prefs.getInt("preset", 0).coerceIn(0, 2))
        toggle = Button(this).apply { text = "Включить камеру"; root.addView(this, LinearLayout.LayoutParams(-1, dp(56))) }
        status = text("Камера выключена", 16f, Color.rgb(91, 220, 180))
        pairing = text("Код появится после включения", 24f)
        address = text("", 14f, Color.LTGRAY).apply { setTextIsSelectable(true) }
        Button(this).apply {
            text = "Скопировать адрес для браузера"
            root.addView(this, LinearLayout.LayoutParams(-1, dp(52)))
            setOnClickListener {
                if (currentUrl.isNotEmpty()) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PhoneCam", currentUrl))
                    Toast.makeText(this@MainActivity, "Адрес скопирован", Toast.LENGTH_SHORT).show()
                }
            }
        }
        text("Поверни телефон горизонтально для широкого кадра. Можно погасить экран: передача продолжается. Для остановки используй кнопку здесь или в уведомлении.\n\nВидео передаётся по локальной сети без шифрования. Используй доверенный Wi-Fi. Микрофон не передаётся.", 13f, Color.rgb(160, 186, 200))
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
            else Toast.makeText(this, "Разреши доступ к камере для передачи видео", Toast.LENGTH_LONG).show()
        } else if (requestCode == 2) launch()
    }
    private fun refresh() {
        if (ticks++ % 5 == 0) cachedIp = localIpv4()
        val active = StreamService.running || StreamService.starting || pending
        lens.isEnabled = !active; preset.isEnabled = !active
        toggle.text = if (active) "Выключить камеру" else "Включить камеру"
        toggle.isEnabled = !pending
        pairing.text = if (active && StreamService.code.isNotEmpty()) "Код  ${StreamService.code}" else "Код появится после включения"
        currentUrl = if (active && cachedIp != null) "http://$cachedIp:${StreamService.PORT}/?code=${StreamService.code}" else ""
        address.text = if (cachedIp == null) "Нет адреса Wi-Fi. Подключись к сети." else "Адрес телефона: $cachedIp:${StreamService.PORT}\n$currentUrl"
        val frames = StreamService.frames
        status.text = when {
            StreamService.lastError != null -> "Ошибка: ${StreamService.lastError}"
            StreamService.starting || pending -> "Запускаю камеру…"
            StreamService.running -> "Передача · ${(frames - lastFrames).coerceAtLeast(0)} fps · зрителей: ${StreamService.viewers}"
            else -> "Камера выключена"
        }
        lastFrames = frames
    }
}
