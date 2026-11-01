package com.example.gpstracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
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

    // ---- Воспроизведение ----
    /** Точка шкалы воспроизведения: t — время в движении от старта (паузы вырезаны). */
    private class PlayPt(val p: GeoPoint, val t: Long)
    private var playSegs: List<List<PlayPt>> = emptyList()
    private var total = 0L
    private var playTime = 0L
    private var playing = false
    private var lastFrame = 0L
    private val speeds = intArrayOf(1, 5, 10, 30, 60, 120, 300)
    private var speedIdx = 3
    private val playedLines = mutableListOf<Polyline>()
    private lateinit var playBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var seek: SeekBar
    private lateinit var timeLabel: TextView
    private val cursor by lazy {
        Marker(map).apply {
            icon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(229, 57, 53))
                setStroke(6, Color.WHITE)
                setSize(48, 48)
            }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ -> true }
        }
    }
    private val player = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            playTime += (now - lastFrame) * speeds[speedIdx]
            lastFrame = now
            if (playTime >= total) {
                playTime = total
                render(true)
                setPlaying(false)
                return
            }
            render(true)
            handler.postDelayed(this, 33)
        }
    }

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

        // Панель воспроизведения
        playBtn = Button(this).apply {
            text = "▶"
            textSize = 20f
            setOnClickListener { setPlaying(!playing) }
        }
        speedBtn = Button(this).apply {
            text = "x${speeds[speedIdx]}"
            setOnClickListener {
                speedIdx = (speedIdx + 1) % speeds.size
                text = "x${speeds[speedIdx]}"
            }
        }
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && total > 0) {
                        playTime = progress * total / 1000
                        render(true)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        timeLabel = TextView(this).apply {
            setTextColor(Color.BLACK)
            setPadding(8, 0, 8, 0)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(220, 255, 255, 255))
            setPadding(12, 8, 12, 8)
            addView(playBtn)
            addView(speedBtn)
            addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(timeLabel)
        }

        val root = FrameLayout(this)
        root.addView(map)
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply { setMargins(16, 16, 16, 72) })
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

        // Шкала воспроизведения: время в движении, паузы вырезаны
        var offset = 0L
        playSegs = segs.map { seg ->
            val t0 = seg.first().time
            val list = seg.map { PlayPt(GeoPoint(it.lat, it.lon), offset + (it.time - t0)) }
            offset = list.last().t
            list
        }
        total = offset
        map.overlays.removeAll(playedLines)
        playedLines.clear()
        repeat(playSegs.size) {
            playedLines += Polyline(map).apply {
                outlinePaint.color = Color.rgb(255, 111, 0)
                outlinePaint.strokeWidth = 12f
            }
        }
        map.overlays.addAll(lines.size, playedLines)
        val canPlay = total > 0
        playBtn.isEnabled = canPlay
        seek.isEnabled = canPlay
        if (playTime > 0) render(false) else timeLabel.text = "0:00:00 / ${TrackStore.formatDuration(total)}"

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

    private fun setPlaying(p: Boolean) {
        if (p && total <= 0) return
        playing = p
        playBtn.text = if (p) "❚❚" else "▶"
        handler.removeCallbacks(player)
        if (p) {
            if (playTime >= total) playTime = 0
            if (map.zoomLevelDouble < 16) map.controller.setZoom(17.0)
            lastFrame = SystemClock.uptimeMillis()
            handler.post(player)
        }
    }

    /** Рисует положение на момент playTime: пройденный путь и маркер. */
    private fun render(follow: Boolean) {
        if (playSegs.isEmpty()) return
        var cur: GeoPoint? = null
        for ((i, seg) in playSegs.withIndex()) {
            val pts = mutableListOf<GeoPoint>()
            for (j in seg.indices) {
                val pp = seg[j]
                if (pp.t <= playTime) {
                    pts += pp.p
                    cur = pp.p
                } else {
                    if (j > 0) {
                        val a = seg[j - 1]
                        val f = (playTime - a.t).toDouble() / (pp.t - a.t).coerceAtLeast(1)
                        val g = GeoPoint(
                            a.p.latitude + (pp.p.latitude - a.p.latitude) * f,
                            a.p.longitude + (pp.p.longitude - a.p.longitude) * f
                        )
                        pts += g
                        cur = g
                    }
                    break
                }
            }
            playedLines.getOrNull(i)?.setPoints(pts)
        }
        cur?.let {
            cursor.position = it
            if (!map.overlays.contains(cursor)) map.overlays.add(cursor)
            if (follow) map.controller.setCenter(it)
        }
        seek.progress = if (total > 0) (playTime * 1000 / total).toInt() else 0
        timeLabel.text = "${TrackStore.formatDuration(playTime)} / ${TrackStore.formatDuration(total)}"
        map.invalidate()
    }

    override fun onResume() {
        super.onResume()
        if (!::map.isInitialized) return
        map.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        if (::playBtn.isInitialized) setPlaying(false)
        handler.removeCallbacks(refresher)
        if (::map.isInitialized) map.onPause()
        super.onPause()
    }
}
