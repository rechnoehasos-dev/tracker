package com.example.gpstracker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.gpstracker.TrackingService.State
import com.example.gpstracker.Ui.MATCH
import com.example.gpstracker.Ui.WRAP
import com.example.gpstracker.Ui.lp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Список треков по макету: сводка, группы по дням, карточки с превью. */
class TracksActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var summary: LinearLayout
    private lateinit var newBtn: TextView
    private val idFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private fun d(v: Number) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(8), d(24), d(16), d(8))
        }
        header.addView(FrameLayout(this).apply {
            background = Ui.ripple(Ui.oval(Ui.SURFACE), Ui.oval(Ui.GRAPHITE))
            contentDescription = "Назад"
            isClickable = true
            setOnClickListener { finish() }
            addView(ImageView(this@TracksActivity).apply { setImageDrawable(Icon(Icon.Kind.BACK, Ui.GRAPHITE)) },
                FrameLayout.LayoutParams(d(22), d(22), Gravity.CENTER))
        }, lp(d(44), d(44)))
        header.addView(Ui.text(this, 24f, 700, value = "Мои треки"), lp(WRAP, WRAP).apply { marginStart = d(8) })
        root.addView(header, lp(MATCH, WRAP))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(8), d(16), d(16))
        }
        summary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rect(Ui.SURFACE_ALT, d(8).toFloat())
            setPadding(d(16), d(16), d(16), d(16))
        }
        content.addView(summary, lp(MATCH, WRAP))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, lp(MATCH, WRAP))

        val hint = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rect(Ui.SURFACE_ALT, d(8).toFloat())
            setPadding(d(12), d(12), d(12), d(12))
        }
        hint.addView(ImageView(this).apply { setImageDrawable(Icon(Icon.Kind.INFO, Ui.GRAPHITE)) }, lp(d(20), d(20)))
        hint.addView(Ui.text(this, 14f, 400, value = "Долгое нажатие — экспорт GPX или удаление"),
            lp(0, WRAP, 1f).apply { marginStart = d(8) })
        content.addView(hint, lp(MATCH, WRAP).apply { topMargin = d(16) })
        scroll.addView(content)
        root.addView(scroll, lp(MATCH, 0, 1f))

        newBtn = Ui.text(this, 14f, 700, value = "▶  НОВЫЙ ТРЕК").apply {
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            background = Ui.ripple(Ui.rect(Ui.YELLOW, d(999).toFloat()))
            isClickable = true
            setOnClickListener { startNew() }
        }
        root.addView(newBtn, lp(MATCH, d(56)).apply {
            marginStart = d(16); marginEnd = d(16); bottomMargin = d(24); topMargin = d(8)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val tracks = TrackStore.list(this)
        val active = TrackingService.state != State.IDLE
        val activeId = if (active) TrackStore.currentId(this) else null
        newBtn.visibility = if (active) View.GONE else View.VISIBLE

        // Сводка
        summary.removeAllViews()
        fun cell(title: String, value: String) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(Ui.label(this, title))
            col.addView(Ui.text(this, 18f, 700, value = value), lp(WRAP, WRAP).apply { topMargin = d(4) })
            summary.addView(col, lp(0, WRAP, 1f))
        }
        cell("Треков", tracks.size.toString())
        cell("Всего", TrackStore.formatDistance(tracks.sumOf { it.meters.toDouble() }.toFloat()))
        cell("Время", TrackStore.formatDuration(tracks.sumOf { it.durationMs }))

        // Карточки, сгруппированные по дням
        list.removeAllViews()
        if (tracks.isEmpty()) {
            list.addView(Ui.text(this, 16f, 400, Ui.MUTED, "Треков пока нет").apply { gravity = Gravity.CENTER },
                lp(MATCH, WRAP).apply { topMargin = d(48) })
            return
        }
        var lastDay = ""
        for (t in tracks) {
            val day = t.id.take(8)
            if (day != lastDay) {
                lastDay = day
                list.addView(Ui.label(this, dayTitle(t.id)), lp(MATCH, WRAP).apply { topMargin = d(24); bottomMargin = d(8) })
            }
            list.addView(card(t, t.id == activeId), lp(MATCH, WRAP).apply { bottomMargin = d(8) })
        }
    }

    private fun card(t: TrackStore.Info, active: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(12), d(12), d(12), d(12))
            background = Ui.ripple(
                Ui.rect(Ui.SURFACE, d(8).toFloat(), if (active) Ui.YELLOW else Ui.BORDER, d(if (active) 2 else 1))
            )
            isClickable = true
            setOnClickListener { MapActivity.open(this@TracksActivity, t.id) }
            setOnLongClickListener { showActions(t); true }
        }
        val thumb = TrackThumb(this, TrackStore.segments(this, t.id)).apply {
            background = Ui.rect(Ui.SURFACE_ALT, d(8).toFloat())
        }
        row.addView(thumb, lp(d(56), d(56)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(this, 16f, 600, value = time(t.id)))
        col.addView(Ui.text(this, 14f, 400, Ui.MUTED, "${TrackStore.formatDuration(t.durationMs)} · ${t.points} точек"),
            lp(WRAP, WRAP).apply { topMargin = d(4) })
        row.addView(col, lp(0, WRAP, 1f).apply { marginStart = d(12) })

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(Ui.text(this, 18f, 700, value = TrackStore.formatDistance(t.meters)))
        if (active) {
            right.addView(Ui.label(this, "Запись", Ui.GRAPHITE).apply {
                background = Ui.rect(Ui.YELLOW, d(999).toFloat())
                setPadding(d(8), d(2), d(8), d(2))
            }, lp(WRAP, WRAP).apply { topMargin = d(4) })
        }
        row.addView(right)
        return row
    }

    private fun time(id: String) = try {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(idFormat.parse(id)!!)
    } catch (e: Exception) { id }

    private fun dayTitle(id: String): String = try {
        val date = idFormat.parse(id)!!
        val cal = Calendar.getInstance().apply { time = date }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun same(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val dm = SimpleDateFormat("d MMMM", Locale("ru")).format(date)
        when {
            same(cal, today) -> "Сегодня · $dm"
            same(cal, yesterday) -> "Вчера · $dm"
            else -> SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(date)
        }
    } catch (e: Exception) { id }

    private fun showActions(t: TrackStore.Info) {
        AlertDialog.Builder(this)
            .setTitle(TrackStore.title(t.id))
            .setItems(arrayOf("Показать на карте", "Экспорт GPX в «Загрузки»", "Удалить")) { _, which ->
                when (which) {
                    0 -> MapActivity.open(this, t.id)
                    1 -> {
                        val name = TrackStore.exportGpx(this, t.id)
                        toast(if (name != null) "Сохранено: Загрузки/$name" else "Трек пуст")
                    }
                    2 -> confirmDelete(t)
                }
            }
            .show()
    }

    private fun confirmDelete(t: TrackStore.Info) {
        if (TrackingService.state != State.IDLE && t.id == TrackStore.currentId(this)) {
            toast("Сначала остановите запись этого трека")
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Удалить трек ${TrackStore.title(t.id)}?")
            .setPositiveButton("Удалить") { _, _ -> TrackStore.delete(this, t.id); reload() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startNew() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            finish(); return
        }
        val sec = getSharedPreferences("service", MODE_PRIVATE).getInt("interval", 10)
        startForegroundService(
            Intent(this, TrackingService::class.java)
                .setAction(TrackingService.ACTION_START)
                .putExtra(TrackingService.EXTRA_INTERVAL_SEC, sec)
        )
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/** Мини-превью трека: линия по точкам, вписанная в квадрат, конец отмечен жёлтым. */
class TrackThumb(ctx: Context, segs: List<List<TrackStore.Point>>) : View(ctx) {
    private val pts = segs.map { seg ->
        val step = maxOf(1, seg.size / 100)
        seg.filterIndexed { i, _ -> i % step == 0 || i == seg.lastIndex }
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(ctx, 2.5f).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Ui.GRAPHITE
    }
    private val endFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.YELLOW }
    private val endStroke = Paint(line).apply { strokeWidth = Ui.dp(ctx, 1.5f).toFloat() }

    override fun onDraw(canvas: Canvas) {
        val all = pts.flatten()
        if (all.isEmpty()) return
        val pad = Ui.dp(context, 10).toFloat()
        val minLat = all.minOf { it.lat }; val maxLat = all.maxOf { it.lat }
        val minLon = all.minOf { it.lon }; val maxLon = all.maxOf { it.lon }
        val span = maxOf(maxLat - minLat, maxLon - minLon, 1e-6)
        val size = minOf(width, height) - 2 * pad
        val ox = (width - size) / 2f + ((span - (maxLon - minLon)) / span * size / 2).toFloat()
        val oy = (height - size) / 2f + ((span - (maxLat - minLat)) / span * size / 2).toFloat()
        fun x(p: TrackStore.Point) = ox + ((p.lon - minLon) / span * size).toFloat()
        fun y(p: TrackStore.Point) = oy + ((maxLat - p.lat) / span * size).toFloat()
        for (seg in pts) {
            if (seg.size < 2) continue
            val path = Path().apply {
                moveTo(x(seg[0]), y(seg[0]))
                for (i in 1 until seg.size) lineTo(x(seg[i]), y(seg[i]))
            }
            canvas.drawPath(path, line)
        }
        val last = all.last()
        val r = Ui.dp(context, 3.5f).toFloat()
        canvas.drawCircle(x(last), y(last), r, endFill)
        canvas.drawCircle(x(last), y(last), r, endStroke)
    }
}
