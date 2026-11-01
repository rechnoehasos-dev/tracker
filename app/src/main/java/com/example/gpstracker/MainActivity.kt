package com.example.gpstracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var interval: EditText
    private lateinit var toggle: Button
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }
        root.addView(TextView(this).apply { text = "Интервал записи, сек:" })
        interval = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("10")
        }
        root.addView(interval)
        status = TextView(this).apply { textSize = 18f; setPadding(0, 32, 0, 32) }
        root.addView(status)

        fun btn(title: String, action: () -> Unit) =
            root.addView(Button(this).apply { text = title; setOnClickListener { action() } })

        toggle = Button(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 40, 0, 40)
            setOnClickListener {
                if (TrackingService.isRunning) stop() else start()
            }
        }
        root.addView(toggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 32 })
        btn("Показать на карте") { startActivity(Intent(this, MapActivity::class.java)) }
        btn("Экспорт GPX") {
            val name = TrackStore.exportGpx(this)
            toast(if (name != null) "Сохранено: Загрузки/$name" else "Трек пуст")
        }
        btn("Очистить трек") { TrackStore.clear(this); update() }

        setContentView(root)
        requestPerms()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun stop() {
        stopService(Intent(this, TrackingService::class.java))
        status.postDelayed({ update() }, 300)
    }

    private fun requestPerms() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPerms(); toast("Нужно разрешение на геолокацию"); return
        }
        val sec = interval.text.toString().toIntOrNull() ?: 10
        startForegroundService(
            Intent(this, TrackingService::class.java).putExtra(TrackingService.EXTRA_INTERVAL_SEC, sec)
        )
        status.postDelayed({ update() }, 300)
    }

    private fun update() {
        val running = TrackingService.isRunning
        status.text = (if (running) "● Запись идёт" else "Остановлено") +
            "\nТочек в треке: ${TrackStore.count(this)}"
        status.setTextColor(if (running) Color.rgb(198, 40, 40) else Color.DKGRAY)
        toggle.text = if (running) "■  СТОП" else "▶  СТАРТ"
        toggle.backgroundTintList = ColorStateList.valueOf(
            if (running) Color.rgb(211, 47, 47) else Color.rgb(46, 125, 50)
        )
        interval.isEnabled = !running
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
