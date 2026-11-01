package com.example.gpstracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.example.gpstracker.Ui.MATCH
import com.example.gpstracker.Ui.WRAP
import com.example.gpstracker.Ui.lp
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Трек на карте с плеером, вариант A «Кольцо». */
class MapActivity : Activity() {

    companion object {
        private const val EXTRA_TRACK = "track_id"
        fun open(ctx: Context, trackId: String) =
            ctx.startActivity(Intent(ctx, MapActivity::class.java).putExtra(EXTRA_TRACK, trackId))
    }

    private lateinit var trackId: String
    private lateinit var map: MapView
    private lateinit var subtitle: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var lastCount = -1
    private var firstFit = true
    private var sourceIdx = 0

    // Линии трека: серая основа и пройденная часть (графитовая обводка + жёлтая середина)
    private val baseLines = mutableListOf<Polyline>()
    private val playedOuter = mutableListOf<Polyline>()
    private val playedInner = mutableListOf<Polyline>()
    private val startMarker by lazy { dot(14, Ui.SURFACE) }
    private val endMarker by lazy { dot(14, Ui.SURFACE) }
    private val cursor by lazy { dot(26, Ui.YELLOW) }

    // Плеер
    private class PlayPt(val p: GeoPoint, val t: Long)
    private var playSegs: List<List<PlayPt>> = emptyList()
    private var total = 0L
    private var playTime = 0L
    private var playing = false
    private var lastFrame = 0L
    private val speeds = intArrayOf(1, 5, 10, 30, 60, 120, 300)
    private var speedIdx = 3
    private lateinit var playBtn: FrameLayout
    private lateinit var speedBtn: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeTotal: TextView

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

    private fun d(v: Number) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trackId = intent.getStringExtra(EXTRA_TRACK) ?: TrackStore.currentId(this) ?: run { finish(); return }
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "GpsTracker/2.0 (github.com/rechnoehasos-dev/tracker)"
        }
        val prefs = getSharedPreferences("map", MODE_PRIVATE)
        sourceIdx = prefs.getInt("source", 0).coerceIn(0, sources.lastIndex)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
        }

        // Шапка: назад, дата трека, слои карты
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(8), d(16), d(16), d(8))
        }
        header.addView(iconButton(Icon.Kind.BACK, "Назад") { finish() }, lp(d(44), d(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.text(this, 18f, 600, value = TrackStore.title(trackId)))
        subtitle = Ui.text(this, 14f, 400, Ui.MUTED)
        titles.addView(subtitle, lp(WRAP, WRAP).apply { topMargin = d(4) })
        header.addView(titles, lp(0, WRAP, 1f).apply { marginStart = d(8) })
        header.addView(Ui.circleButton(this, 44, Ui.SURFACE, Icon(Icon.Kind.LAYERS, Ui.GRAPHITE), 22, "Слои карты",
            Ui.BORDER, 1) {
            sourceIdx = (sourceIdx + 1) % sources.size
            prefs.edit().putInt("source", sourceIdx).apply()
            map.setTileSource(sources[sourceIdx].second)
            Toast.makeText(this, "Карта: ${sources[sourceIdx].first}", Toast.LENGTH_SHORT).show()
        })
        root.addView(header, lp(MATCH, WRAP))

        // Карта в скруглённой рамке
        map = MapView(this).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.0)
            setTileSource(sources[sourceIdx].second)
        }
        map.overlays.add(CopyrightOverlay(this))
        val frame = FrameLayout(this).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, d(16).toFloat())
            }
            clipToOutline = true
            addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        root.addView(frame, lp(MATCH, 0, 1f).apply { marginStart = d(16); marginEnd = d(16) })

        // Плеер
        val playerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(24), d(16), d(24), d(24))
        }
        seek = SeekBar(this).apply {
            max = 1000
            splitTrack = false
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.GRAPHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.BORDER)
            thumb = Ui.oval(Ui.YELLOW, Ui.GRAPHITE, d(3)).apply { setSize(d(24), d(24)) }
            setPadding(d(12), 0, d(12), 0)
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
        playerBox.addView(seek, lp(MATCH, d(32)))
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        timeNow = Ui.text(this, 14f, 400, Ui.MUTED, "0:00:00")
        timeTotal = Ui.text(this, 14f, 400, Ui.MUTED, "0:00:00")
        times.addView(timeNow)
        times.addView(Ui.spacer(this))
        times.addView(timeTotal)
        playerBox.addView(times, lp(MATCH, WRAP).apply { topMargin = d(4) })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(Ui.circleButton(this, 48, Ui.SURFACE, Icon(Icon.Kind.REWIND, Ui.GRAPHITE), 22,
            "Назад на 1 минуту", Ui.BORDER_STRONG, 1) {
            playTime = (playTime - 60_000).coerceAtLeast(0)
            render(true)
        })
        playBtn = Ui.circleButton(this, 80, Ui.YELLOW, Icon(Icon.Kind.PLAY, Ui.GRAPHITE), 32, "Воспроизвести") {
            setPlaying(!playing)
        }
        buttons.addView(playBtn, lp(d(80), d(80)).apply { marginStart = d(24); marginEnd = d(24) })
        speedBtn = Ui.text(this, 16f, 700, value = "x${speeds[speedIdx]}").apply {
            gravity = Gravity.CENTER
            background = Ui.ripple(Ui.rect(Ui.SURFACE, d(999).toFloat(), Ui.BORDER_STRONG, d(1)))
            isClickable = true
            contentDescription = "Скорость воспроизведения"
            setOnClickListener {
                speedIdx = (speedIdx + 1) % speeds.size
                text = "x${speeds[speedIdx]}"
            }
        }
        buttons.addView(speedBtn, lp(d(64), d(48)))
        playerBox.addView(buttons, lp(MATCH, WRAP).apply { topMargin = d(16) })
        root.addView(playerBox, lp(MATCH, WRAP))

        setContentView(root)
    }

    private fun iconButton(kind: Icon.Kind, desc: String, onClick: () -> Unit) = FrameLayout(this).apply {
        background = Ui.ripple(Ui.oval(Ui.SURFACE), Ui.oval(Ui.GRAPHITE))
        contentDescription = desc
        isClickable = true
        setOnClickListener { onClick() }
        addView(ImageView(this@MapActivity).apply { setImageDrawable(Icon(kind, Ui.GRAPHITE)) },
            FrameLayout.LayoutParams(d(22), d(22), Gravity.CENTER))
    }

    private fun dot(sizeDp: Int, fill: Int) = Marker(map).apply {
        icon = Ui.oval(fill, Ui.GRAPHITE, d(3)).apply { setSize(d(sizeDp), d(sizeDp)) }
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setOnMarkerClickListener { _, _ -> true }
    }

    private fun line(color: Int, widthDp: Float) = Polyline(map).apply {
        outlinePaint.color = color
        outlinePaint.strokeWidth = d(widthDp).toFloat()
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
    }

    private fun refresh() {
        val segs = TrackStore.segments(this, trackId)
        val count = segs.sumOf { it.size }
        if (count == lastCount) return
        lastCount = count

        subtitle.text = if (count == 0) "Трек пуст" else
            "${TrackStore.formatDistance(TrackStore.distance(segs))} · $count точек"
        if (count == 0) return

        map.overlays.removeAll(baseLines + playedOuter + playedInner)
        baseLines.clear(); playedOuter.clear(); playedInner.clear()
        for (seg in segs) {
            baseLines += line(Ui.BORDER_STRONG, 4f).apply { setPoints(seg.map { GeoPoint(it.lat, it.lon) }) }
            playedOuter += line(Ui.GRAPHITE, 6f)
            playedInner += line(Ui.YELLOW, 3f)
        }
        map.overlays.addAll(0, baseLines + playedOuter + playedInner)

        val pts = segs.flatten().map { GeoPoint(it.lat, it.lon) }
        startMarker.position = pts.first()
        endMarker.position = pts.last()
        if (!map.overlays.contains(startMarker)) map.overlays.add(startMarker)
        if (pts.size > 1 && !map.overlays.contains(endMarker)) map.overlays.add(endMarker)

        var offset = 0L
        playSegs = segs.map { seg ->
            val t0 = seg.first().time
            val list = seg.map { PlayPt(GeoPoint(it.lat, it.lon), offset + (it.time - t0)) }
            offset = list.last().t
            list
        }
        total = offset
        timeTotal.text = TrackStore.formatDuration(total)
        playBtn.isEnabled = total > 0
        playBtn.alpha = if (total > 0) 1f else 0.4f
        seek.isEnabled = total > 0
        render(false)

        if (firstFit) {
            firstFit = false
            map.post {
                if (pts.size == 1) {
                    map.controller.setZoom(17.0)
                    map.controller.setCenter(pts.first())
                } else {
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), false, d(48))
                }
            }
        }
        map.invalidate()
    }

    private fun setPlaying(p: Boolean) {
        if (p && total <= 0) return
        playing = p
        (playBtn.getChildAt(0) as ImageView).setImageDrawable(
            Icon(if (p) Icon.Kind.PAUSE else Icon.Kind.PLAY, Ui.GRAPHITE)
        )
        playBtn.contentDescription = if (p) "Пауза" else "Воспроизвести"
        handler.removeCallbacks(player)
        if (p) {
            if (playTime >= total) playTime = 0
            if (map.zoomLevelDouble < 16) map.controller.setZoom(17.0)
            lastFrame = SystemClock.uptimeMillis()
            handler.post(player)
        }
    }

    /** Положение на момент playTime: пройденный путь и маркер. */
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
            playedOuter.getOrNull(i)?.setPoints(pts)
            playedInner.getOrNull(i)?.setPoints(pts)
        }
        val showCursor = playTime > 0 || playing
        if (showCursor) cur?.let {
            cursor.position = it
            if (!map.overlays.contains(cursor)) map.overlays.add(cursor)
            if (follow) map.controller.setCenter(it)
        }
        seek.progress = if (total > 0) (playTime * 1000 / total).toInt() else 0
        timeNow.text = TrackStore.formatDuration(playTime)
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
