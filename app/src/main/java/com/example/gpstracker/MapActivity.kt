package com.example.gpstracker

import android.app.Activity
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Polyline

/** Показывает записанный трек на карте OpenStreetMap и обновляет его во время записи. */
class MapActivity : Activity() {

    private lateinit var map: MapView
    private lateinit var info: TextView
    private val line by lazy { Polyline(map) }
    private val startMarker by lazy { Marker(map).apply { title = "Старт" } }
    private val endMarker by lazy { Marker(map).apply { title = "Финиш" } }
    private val handler = Handler(Looper.getMainLooper())
    private var lastCount = -1
    private var firstFit = true

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "GpsTracker/1.2 (github.com/rechnoehasos-dev/tracker)"
        }

        map = MapView(this).apply {
            setTileSource(
                XYTileSource(
                    "CartoVoyager", 0, 20, 256, ".png",
                    arrayOf(
                        "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
                        "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
                        "https://c.basemaps.cartocdn.com/rastertiles/voyager/"
                    ),
                    "© OpenStreetMap contributors © CARTO"
                )
            )
            setMultiTouchControls(true)
            controller.setZoom(15.0)
        }
        line.outlinePaint.apply {
            color = Color.rgb(33, 150, 243)
            strokeWidth = 10f
        }
        map.overlays.add(line)
        map.overlays.add(CopyrightOverlay(this))

        info = TextView(this).apply {
            setBackgroundColor(Color.argb(200, 255, 255, 255))
            setTextColor(Color.BLACK)
            textSize = 16f
            setPadding(24, 16, 24, 16)
        }

        val root = FrameLayout(this)
        root.addView(map)
        root.addView(info, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply { setMargins(24, 24, 24, 24) })
        setContentView(root)
    }

    private fun refresh() {
        val raw = TrackStore.points(this)
        if (raw.size == lastCount) return
        lastCount = raw.size

        if (raw.isEmpty()) {
            info.text = "Трек пуст"
            return
        }
        val pts = raw.map { GeoPoint(it.first, it.second) }
        line.setPoints(pts)

        startMarker.position = pts.first()
        endMarker.position = pts.last()
        if (!map.overlays.contains(startMarker)) map.overlays.add(startMarker)
        if (pts.size > 1 && !map.overlays.contains(endMarker)) map.overlays.add(endMarker)

        var meters = 0f
        val tmp = FloatArray(1)
        for (i in 1 until raw.size) {
            Location.distanceBetween(raw[i - 1].first, raw[i - 1].second, raw[i].first, raw[i].second, tmp)
            meters += tmp[0]
        }
        val dist = if (meters >= 1000) "%.2f км".format(meters / 1000) else "%.0f м".format(meters)
        info.text = "Точек: ${pts.size}\nДистанция: $dist"

        if (firstFit) {
            firstFit = false
            map.post {
                if (pts.size == 1) {
                    map.controller.setZoom(17.0)
                    map.controller.setCenter(pts.first())
                } else {
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), false, 100)
                }
            }
        }
        map.invalidate()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        map.onPause()
        super.onPause()
    }
}
