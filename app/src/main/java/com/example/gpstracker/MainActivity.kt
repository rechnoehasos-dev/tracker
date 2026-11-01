package com.example.gpstracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.gpstracker.TrackingService.State
import com.example.gpstracker.Ui.MATCH
import com.example.gpstracker.Ui.WRAP
import com.example.gpstracker.Ui.lp
import java.text.SimpleDateFormat
import java.util.Locale

/** Главный экран, вариант A «Кольцо». */
class MainActivity : Activity() {

    private val intervals = intArrayOf(1, 5, 10, 30, 60)

    private lateinit var pill: TextView
    private lateinit var startCircle: View
    private lateinit var ring: LinearLayout
    private lateinit var ringLabel: TextView
    private lateinit var ringTime: TextView
    private lateinit var ringSub: TextView
    private lateinit var stats: LinearLayout
    private lateinit var statKm: TextView
    private lateinit var statKmUnit: TextView
    private lateinit var statPts: TextView
    private lateinit var statInt: TextView
    private lateinit var idleBlock: LinearLayout
    private val chips = mutableListOf<TextView>()
    private lateinit var lastCard: LinearLayout
    private lateinit var lastTitle: TextView
    private lateinit var lastValue: TextView
    private lateinit var controls: LinearLayout
    private lateinit var pauseBtn: FrameLayout
    private lateinit var pauseIcon: ImageView
    private lateinit var pauseLabel: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0
    private val ticker = object : Runnable {
        override fun run() {
            update(full = tick++ % 3 == 0)
            handler.postDelayed(this, 1000)
        }
    }

    private fun d(v: Number) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrackStore.migrate(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
        }

        // Шапка
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(24), d(24), d(24), d(8))
        }
        header.addView(View(this).apply { background = Ui.oval(Ui.YELLOW) }, lp(d(20), d(20)))
        header.addView(Ui.text(this, 14f, 700, value = "GPS ТРЕКЕР").apply { letterSpacing = 0.08f },
            lp(WRAP, WRAP).apply { marginStart = d(8) })
        header.addView(Ui.spacer(this))
        pill = Ui.label(this, "", Ui.GRAPHITE).apply { setPadding(d(12), d(6), d(12), d(6)) }
        header.addView(pill)
        root.addView(header, lp(MATCH, WRAP))

        // Центр
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            setPadding(d(24), 0, d(24), 0)
        }
        root.addView(center, lp(MATCH, 0, 1f))

        val dial = FrameLayout(this)
        center.addView(dial, lp(d(264), d(264)))

        // Кнопка СТАРТ (остановлено)
        startCircle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.ripple(Ui.oval(Ui.YELLOW), Ui.oval(Ui.GRAPHITE))
            isClickable = true
            contentDescription = "Старт"
            setOnClickListener { start() }
            addView(ImageView(this@MainActivity).apply { setImageDrawable(Icon(Icon.Kind.PLAY, Ui.GRAPHITE)) }, lp(d(64), d(64)))
            addView(Ui.text(this@MainActivity, 24f, 700, value = "СТАРТ").apply { letterSpacing = 0.06f },
                lp(WRAP, WRAP).apply { topMargin = d(8) })
        }
        dial.addView(startCircle, FrameLayout.LayoutParams(MATCH, MATCH))

        // Кольцо с таймером (запись/пауза)
        ring = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        ringLabel = Ui.label(this, "В движении")
        ringTime = Ui.text(this, 44f, 700)
        ringSub = Ui.text(this, 14f, 400, Ui.MUTED)
        ring.addView(ringLabel)
        ring.addView(ringTime, lp(WRAP, WRAP).apply { topMargin = d(6); bottomMargin = d(6) })
        ring.addView(ringSub)
        dial.addView(ring, FrameLayout.LayoutParams(MATCH, MATCH))

        // Показатели (запись/пауза)
        stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(divider: Boolean): Pair<TextView, TextView> {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                if (divider) background = Ui.rect(Ui.SURFACE, 0f).also { }
            }
            val v = Ui.text(this, 24f, 700)
            val l = Ui.label(this, "")
            col.addView(v)
            col.addView(l, lp(WRAP, WRAP).apply { topMargin = d(4) })
            stats.addView(col, lp(0, WRAP, 1f))
            return v to l
        }
        stat(false).let { statKm = it.first; statKmUnit = it.second }
        stats.addView(View(this).apply { setBackgroundColor(Ui.BORDER) }, lp(d(1), MATCH))
        stat(true).let { statPts = it.first; it.second.text = "ТОЧЕК" }
        stats.addView(View(this).apply { setBackgroundColor(Ui.BORDER) }, lp(d(1), MATCH))
        stat(false).let { statInt = it.first; it.second.text = "ИНТЕРВАЛ" }
        center.addView(stats, lp(MATCH, WRAP).apply { topMargin = d(32) })

        // Кнопки Пауза / Стоп
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        pauseIcon = ImageView(this)
        pauseBtn = Ui.circleButton(this, 72, Ui.SURFACE, Icon(Icon.Kind.PAUSE, Ui.GRAPHITE), 28, "Пауза",
            Ui.BORDER_STRONG, 2) { togglePause() }
        pauseLabel = Ui.label(this, "Пауза", Ui.GRAPHITE)
        controls.addView(column(pauseBtn, pauseLabel))
        val stopBtn = Ui.circleButton(this, 96, Ui.GRAPHITE, Icon(Icon.Kind.STOP, Ui.SURFACE), 32, "Стоп") {
            send(TrackingService.ACTION_STOP)
        }
        controls.addView(column(stopBtn, Ui.label(this, "Стоп", Ui.GRAPHITE)),
            lp(WRAP, WRAP).apply { marginStart = d(32); marginEnd = d(32) })
        controls.addView(View(this), lp(d(72), d(1)))
        center.addView(controls, lp(MATCH, WRAP).apply { topMargin = d(32) })

        // Блок «остановлено»: интервал и последний трек
        idleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        idleBlock.addView(Ui.label(this, "Интервал записи").apply { gravity = Gravity.CENTER },
            lp(MATCH, WRAP))
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        intervals.forEachIndexed { i, sec ->
            val chip = Ui.text(this, 14f, 600, value = if (sec >= 60) "${sec / 60} мин" else "$sec с").apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { setInterval(sec) }
            }
            chips += chip
            chipRow.addView(chip, lp(0, d(44), 1f).apply { if (i > 0) marginStart = d(8) })
        }
        idleBlock.addView(chipRow, lp(MATCH, WRAP).apply { topMargin = d(8) })

        lastCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16), d(12), d(16), d(12))
            background = Ui.ripple(Ui.rect(Ui.SURFACE_ALT, d(8).toFloat()))
            isClickable = true
            setOnClickListener { TrackStore.currentId(this@MainActivity)?.let { MapActivity.open(this@MainActivity, it) } }
        }
        val lastCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lastTitle = Ui.label(this, "")
        lastValue = Ui.text(this, 16f, 600)
        lastCol.addView(lastTitle)
        lastCol.addView(lastValue, lp(WRAP, WRAP).apply { topMargin = d(4) })
        lastCard.addView(lastCol, lp(0, WRAP, 1f))
        lastCard.addView(ImageView(this).apply { setImageDrawable(Icon(Icon.Kind.CHEVRON, Ui.GRAPHITE)) }, lp(d(18), d(18)))
        idleBlock.addView(lastCard, lp(MATCH, WRAP).apply { topMargin = d(32) })
        center.addView(idleBlock, lp(MATCH, WRAP).apply { topMargin = d(32) })

        // Нижняя навигация
        root.addView(View(this).apply { setBackgroundColor(Ui.BORDER) }, lp(MATCH, d(1)))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(d(8), d(8), d(8), d(16))
        }
        nav.addView(navItem(Icon.Kind.RECORD, "Запись", true) {}, lp(0, WRAP, 1f))
        nav.addView(navItem(Icon.Kind.MAP, "Карта", false) {
            val id = TrackStore.currentId(this)
            if (id != null) MapActivity.open(this, id) else toast("Треков пока нет")
        }, lp(0, WRAP, 1f))
        nav.addView(navItem(Icon.Kind.LIST, "Треки", false) {
            startActivity(Intent(this, TracksActivity::class.java))
        }, lp(0, WRAP, 1f))
        root.addView(nav, lp(MATCH, WRAP))

        setContentView(root)
        requestPerms()
    }

    private fun column(top: View, bottom: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(top)
        addView(bottom, lp(WRAP, WRAP).apply { topMargin = d(8) })
    }

    private fun navItem(kind: Icon.Kind, title: String, active: Boolean, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, d(8), 0, d(8))
            background = Ui.ripple(Ui.rect(Ui.SURFACE, d(8).toFloat()))
            isClickable = true
            setOnClickListener { onClick() }
            val c = if (active) Ui.GRAPHITE else Ui.MUTED
            addView(ImageView(this@MainActivity).apply { setImageDrawable(Icon(kind, c)) }, lp(d(24), d(24)))
            addView(Ui.label(this@MainActivity, title, c).apply {
                typeface = Ui.font(this@MainActivity, if (active) 700 else 600)
            }, lp(WRAP, WRAP).apply { topMargin = d(4) })
        }

    override fun onResume() {
        super.onResume()
        tick = 0
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun prefs() = getSharedPreferences("service", MODE_PRIVATE)
    private fun interval() = prefs().getInt("interval", 10)

    private fun setInterval(sec: Int) {
        prefs().edit().putInt("interval", sec).apply()
        update(full = false)
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
        startForegroundService(
            Intent(this, TrackingService::class.java)
                .setAction(TrackingService.ACTION_START)
                .putExtra(TrackingService.EXTRA_INTERVAL_SEC, interval())
        )
        handler.postDelayed({ update(full = true) }, 300)
    }

    private fun togglePause() {
        send(if (TrackingService.state == State.PAUSED) TrackingService.ACTION_RESUME else TrackingService.ACTION_PAUSE)
    }

    private fun send(action: String) {
        startService(Intent(this, TrackingService::class.java).setAction(action))
        handler.postDelayed({ update(full = true) }, 300)
    }

    private var lastShownState: State? = null

    private fun update(full: Boolean) {
        val state = TrackingService.state
        val idle = state == State.IDLE
        val paused = state == State.PAUSED
        val id = TrackStore.currentId(this)

        // Шапка
        pill.text = when (state) {
            State.RECORDING -> "● ЗАПИСЬ"
            State.PAUSED -> "❚❚ ПАУЗА"
            State.IDLE -> "ГОТОВ"
        }
        pill.background = Ui.rect(if (state == State.RECORDING) Ui.YELLOW else Ui.SURFACE_ALT, d(999).toFloat())

        startCircle.visibility = if (idle) View.VISIBLE else View.GONE
        ring.visibility = if (idle) View.GONE else View.VISIBLE
        stats.visibility = ring.visibility
        controls.visibility = ring.visibility
        idleBlock.visibility = if (idle) View.VISIBLE else View.GONE

        if (!idle) {
            if (lastShownState != state) {
                ring.background = Ui.oval(Ui.SURFACE, if (paused) Ui.BORDER else Ui.YELLOW, d(14))
                ringLabel.text = if (paused) "НА ПАУЗЕ" else "В ДВИЖЕНИИ"
                pauseBtn.contentDescription = if (paused) "Продолжить" else "Пауза"
                (pauseBtn.getChildAt(0) as ImageView).setImageDrawable(
                    Icon(if (paused) Icon.Kind.PLAY else Icon.Kind.PAUSE, Ui.GRAPHITE)
                )
                pauseLabel.text = if (paused) "ДАЛЕЕ" else "ПАУЗА"
            }
            ringTime.text = TrackStore.formatDuration(TrackingService.elapsed(this))
            if (id != null) ringSub.text = shortTitle(id)
            if (full || lastShownState != state) id?.let {
                val i = TrackStore.info(this, it)
                if (i.meters >= 1000) {
                    statKm.text = "%.2f".format(i.meters / 1000); statKmUnit.text = "КМ"
                } else {
                    statKm.text = "%.0f".format(i.meters); statKmUnit.text = "М"
                }
                statPts.text = i.points.toString()
            }
            statInt.text = formatInterval(interval())
        } else {
            val cur = interval()
            chips.forEachIndexed { i, chip ->
                val sel = intervals[i] == cur
                chip.background = Ui.ripple(
                    if (sel) Ui.rect(Ui.GRAPHITE, d(999).toFloat())
                    else Ui.rect(Ui.SURFACE, d(999).toFloat(), Ui.BORDER_STRONG, d(1))
                )
                chip.setTextColor(if (sel) Ui.SURFACE else Ui.GRAPHITE)
            }
            if (full || lastShownState != state) {
                if (id != null) {
                    val i = TrackStore.info(this, id)
                    lastCard.visibility = View.VISIBLE
                    lastTitle.text = "ПОСЛЕДНИЙ ТРЕК · ${shortTitle(id)}"
                    lastValue.text = "${TrackStore.formatDistance(i.meters)} · ${TrackStore.formatDuration(i.durationMs)}"
                } else {
                    lastCard.visibility = View.GONE
                }
            }
        }
        lastShownState = state
    }

    private fun shortTitle(id: String): String = try {
        val d = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(id)!!
        SimpleDateFormat("dd.MM · HH:mm", Locale.getDefault()).format(d)
    } catch (e: Exception) {
        id
    }

    private fun formatInterval(sec: Int) = if (sec >= 60 && sec % 60 == 0) "${sec / 60} мин" else "$sec с"

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
