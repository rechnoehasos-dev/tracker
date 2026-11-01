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
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.gpstracker.TrackingService.State

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var interval: EditText
    private lateinit var toggle: Button
    private lateinit var pause: Button
    private lateinit var showCurrent: Button
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrackStore.migrate(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }
        root.addView(TextView(this).apply { text = "Интервал записи, сек:" })
        interval = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(getSharedPreferences("service", MODE_PRIVATE).getInt("interval", 10).toString())
        }
        root.addView(interval)
        status = TextView(this).apply { textSize = 18f; setPadding(0, 32, 0, 32) }
        root.addView(status)

        fun bigButton(onClick: () -> Unit) = Button(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 40, 0, 40)
            setOnClickListener { onClick() }
        }
        val fullWidth = {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }

        toggle = bigButton { if (TrackingService.state == State.IDLE) start() else send(TrackingService.ACTION_STOP) }
        root.addView(toggle, fullWidth())
        pause = bigButton {
            send(if (TrackingService.state == State.PAUSED) TrackingService.ACTION_RESUME else TrackingService.ACTION_PAUSE)
        }
        root.addView(pause, fullWidth())

        showCurrent = Button(this).apply {
            text = "Текущий трек на карте"
            setOnClickListener {
                TrackStore.currentId(this@MainActivity)?.let { MapActivity.open(this@MainActivity, it) }
            }
        }
        root.addView(showCurrent, fullWidth())
        root.addView(Button(this).apply {
            text = "Мои треки"
            setOnClickListener { startActivity(Intent(this@MainActivity, TracksActivity::class.java)) }
        }, fullWidth())

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
            Intent(this, TrackingService::class.java)
                .setAction(TrackingService.ACTION_START)
                .putExtra(TrackingService.EXTRA_INTERVAL_SEC, sec)
        )
        status.postDelayed({ update() }, 300)
    }

    private fun send(action: String) {
        startService(Intent(this, TrackingService::class.java).setAction(action))
        status.postDelayed({ update() }, 300)
    }

    private fun update() {
        val state = TrackingService.state
        val id = TrackStore.currentId(this)
        val head = when (state) {
            State.RECORDING -> "● Запись идёт"
            State.PAUSED -> "❚❚ Пауза"
            State.IDLE -> "Остановлено"
        }
        val details = if (id != null) {
            val i = TrackStore.info(this, id)
            val label = if (state == State.IDLE) "Последний трек" else "Трек"
            "\n$label: ${TrackStore.title(id)}\nТочек: ${i.points} · ${TrackStore.formatDistance(i.meters)} · ${TrackStore.formatDuration(i.durationMs)}"
        } else ""
        status.text = head + details
        status.setTextColor(
            when (state) {
                State.RECORDING -> Color.rgb(198, 40, 40)
                State.PAUSED -> Color.rgb(239, 108, 0)
                State.IDLE -> Color.DKGRAY
            }
        )

        val idle = state == State.IDLE
        toggle.text = if (idle) "▶  СТАРТ" else "■  СТОП"
        toggle.backgroundTintList = ColorStateList.valueOf(
            if (idle) Color.rgb(46, 125, 50) else Color.rgb(211, 47, 47)
        )
        pause.visibility = if (idle) View.GONE else View.VISIBLE
        pause.text = if (state == State.PAUSED) "▶  ПРОДОЛЖИТЬ" else "❚❚  ПАУЗА"
        pause.backgroundTintList = ColorStateList.valueOf(
            if (state == State.PAUSED) Color.rgb(25, 118, 210) else Color.rgb(239, 108, 0)
        )
        interval.isEnabled = idle
        showCurrent.isEnabled = id != null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
