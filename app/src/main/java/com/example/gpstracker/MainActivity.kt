package com.example.gpstracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

        btn("Старт") { start() }
        btn("Стоп") { stopService(Intent(this, TrackingService::class.java)); update() }
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
        update()
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
        val state = if (TrackingService.isRunning) "Запись идёт" else "Остановлено"
        status.text = "$state\nТочек в треке: ${TrackStore.count(this)}"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
