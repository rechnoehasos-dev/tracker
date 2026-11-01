package com.example.gpstracker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

/** Фоновый сервис: получает координаты с заданным интервалом и пишет их в трек. */
class TrackingService : Service() {

    companion object {
        const val EXTRA_INTERVAL_SEC = "interval_sec"
        private const val CHANNEL = "tracking"
        var isRunning = false
            private set
    }

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (l in result.locations) {
                TrackStore.add(this@TrackingService, l.time, l.latitude, l.longitude, l.altitude, l.accuracy)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sec = (intent?.getIntExtra(EXTRA_INTERVAL_SEC, 10) ?: 10).coerceAtLeast(1)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Запись трека", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Идёт запись трека")
            .setContentText("Интервал: $sec с")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, sec * 1000L)
            .setMinUpdateIntervalMillis(sec * 1000L)
            .build()
        client.removeLocationUpdates(callback)
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
