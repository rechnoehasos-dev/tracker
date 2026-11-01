package com.example.gpstracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Polyline

/** Показывает записанный трек на карте OpenStreetMap и обновляет его во время записи. */
class MapActivity : Activity() {

    companion object {
        private const val EXTRA_TRACK = "track_id"
        fun open(ctx: Context, trackId: String) =
            ctx.startActivity(Intent(ctx, MapActivity::class.java).putExtra(EXTRA_TRACK, trackId))
    }

    private lateinit var trackId: String
    private val lines = mutableListOf<Polyline>()

    private lateinit var map: MapView
    private lateinit var info: TextView
    private val startMarker by lazy { Marker(map).apply { title = "Старт" } }
    private val endMarker by lazy { Marker(map).apply { title = "Финиш" } }
    private val handler = Handler(Looper.getMainLooper())
    private var lastCount = -1
    private var firstFit = true
    private var sourceIdx = 0

    /** Источники карт без ключа API: название для кнопки и сам источник. */
    private val sources: List<Pair<String, ITileSource>> = listOf(
        "OSM" to XYTileSource(
            "OSM-Std", 0, 19, 256, ".png",
            arrayOf("https://tile.openstreetmap.org/"),
            "© OpenStreetMap contributors"
        ),
        "Esri" to object : OnlineTileSourceBase(
            "EsriStreet", 0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"),
            "© Esri, HERE, Garmin, © OpenStreetMap contributors"
        ) {
            override fun getTileURLString(index: Long): String =
                baseUrl + MapTileIndex.getZoom(index) + "/" + MapTileIndex.getY(index) + "/" + MapTileIndex.getX(index)
        },
        "Топо" to XYTileSource(
            "OpenTopo", 0, 17, 256, ".png",
            arrayOf("https://a.tile.opentopomap.org/", "https://b.tile.opentopomap.org/", "https://c.tile.opentopomap.org/"),
            "© OpenStreetMap contributors, SRTM | © OpenTopoMap"
        )
    )

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trackId = intent.getStringExtra(EXTRA_TRACK) ?: TrackStore.currentId(this) ?: run { finish(); return }
        title = TrackStore.title(trackId)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "GpsTracker/1.5 (github.com/rechnoehasos-dev/tracker)"
        }
        val prefs = getSharedPreferences("map", MODE_PRIVATE)
        sourceIdx = prefs.getInt("source", 0).coerceIn(0, sources.lastIndex)

        map = MapView(this).apply {
            setMultiTouchControls(true)
            controller.setZoom(15.0)
        }
        map.overlays.add(CopyrightOverlay(this))
        map.setTileSource(sources[sourceIdx].second)

        val switcher = Button(this).apply {
            text = "Карта: ${sources[sourceIdx].first}"
            setOnClickListener {
                sourceIdx = (sourceIdx + 1) % sources.size
                prefs.edit().putInt("source", sourceIdx).apply()
                map.setTileSource(sources[sourceIdx].second)
                text = "Карта: ${sources[sourceIdx].first}"
            }
        }

        info = TextView(this).apply {
            setBackgroundColor(Color.argb(200, 255, 255, 255))
            setTextColor(Color.BLACK)
            textSize = 16f
            setPadding(24, 16, 24, 16)
        }

        val root = FrameLayout(this)
        root.addView(map)
        root.addView(switcher, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { setMargins(24, 24, 24, 24) })
        root.addView(info, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply { setMargins(24, 24, 24, 24) })
        setContentView(root)
    }

    private fun refresh() {
        val segs = TrackStore.segments(this, trackId)
        val count = segs.sumOf { it.size }
        if (count == lastCount) return
        lastCount = count

        if (count == 0) {
            info.text = "Трек пуст"
            return
        }

        // Каждый отрезок (между паузами) — отдельная линия, разрывы не соединяются
        map.overlays.removeAll(lines)
        lines.clear()
        for (seg in segs) {
            lines += Polyline(map).apply {
                setPoints(seg.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = Color.rgb(33, 150, 243)
                outlinePaint.strokeWidth = 10f
            }
        }
        map.overlays.addAll(0, lines)

        val pts = segs.flatten().map { GeoPoint(it.lat, it.lon) }
        startMarker.position = pts.first()
        endMarker.position = pts.last()
        if (!map.overlays.contains(startMarker)) map.overlays.add(startMarker)
        if (pts.size > 1 && !map.overlays.contains(endMarker)) map.overlays.add(endMarker)

        val pauses = segs.size - 1
        info.text = "Точек: $count\n" +
            "Дистанция: ${TrackStore.formatDistance(TrackStore.distance(segs))}\n" +
            "В движении: ${TrackStore.formatDuration(TrackStore.duration(segs))}" +
            if (pauses > 0) "\nПауз: $pauses" else ""

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
        if (!::map.isInitialized) return
        map.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        if (::map.isInitialized) map.onPause()
        super.onPause()
    }
}
