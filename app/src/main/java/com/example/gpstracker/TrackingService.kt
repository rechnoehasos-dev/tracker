package com.example.gpstracker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Фоновый сервис записи: старт (новый трек), пауза, продолжение, стоп. */
class TrackingService : Service() {

    enum class State { IDLE, RECORDING, PAUSED }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        const val EXTRA_INTERVAL_SEC = "interval_sec"
        private const val CHANNEL = "tracking"

        @Volatile
        var state = State.IDLE
            private set
    }

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val prefs by lazy { getSharedPreferences("service", MODE_PRIVATE) }
    private var trackId: String? = null
    private var intervalSec = 10

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val id = trackId ?: return
            if (state != State.RECORDING) return
            for (l in result.locations) {
                TrackStore.add(this@TrackingService, id, l.time, l.latitude, l.longitude, l.altitude, l.accuracy)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                intervalSec = intent.getIntExtra(EXTRA_INTERVAL_SEC, 10).coerceAtLeast(1)
                trackId = TrackStore.newTrack(this)
                setState(State.RECORDING)
                startUpdates()
            }
            ACTION_PAUSE -> {
                restoreIfNeeded()
                stopUpdates()
                trackId?.let { TrackStore.breakSegment(this, it) }
                setState(State.PAUSED)
            }
            ACTION_RESUME -> {
                restoreIfNeeded()
                setState(State.RECORDING)
                startUpdates()
            }
            ACTION_STOP -> {
                stopUpdates()
                setState(State.IDLE)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Перезапуск системой после выгрузки: продолжаем, как было
                restoreIfNeeded()
                val saved = prefs.getString("state", State.IDLE.name)
                if (trackId == null || saved == State.IDLE.name) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                setState(State.valueOf(saved!!))
                if (state == State.RECORDING) startUpdates()
            }
        }
        return START_STICKY
    }

    private fun restoreIfNeeded() {
        if (trackId == null) trackId = TrackStore.currentId(this)
        intervalSec = prefs.getInt("interval", intervalSec)
    }

    private fun setState(s: State) {
        state = s
        prefs.edit().putString("state", s.name).putInt("interval", intervalSec).apply()
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Запись трека", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(if (state == State.PAUSED) "Запись на паузе" else "Идёт запись трека")
            .setContentText("Интервал: $intervalSec с")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalSec * 1000L)
            .setMinUpdateIntervalMillis(intervalSec * 1000L)
            .build()
        client.removeLocationUpdates(callback)
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun stopUpdates() {
        client.removeLocationUpdates(callback)
    }

    override fun onDestroy() {
        stopUpdates()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
