package com.example.gpstracker

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.example.gpstracker.TrackingService.State

/** Список сохранённых треков: нажатие — карта, долгое нажатие — действия. */
class TracksActivity : Activity() {

    private lateinit var list: ListView
    private var tracks: List<TrackStore.Info> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Мои треки"
        list = ListView(this)
        val empty = TextView(this).apply {
            text = "Треков пока нет"
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val root = android.widget.FrameLayout(this)
        root.addView(list)
        root.addView(empty)
        list.emptyView = empty
        setContentView(root)

        list.setOnItemClickListener { _, _, pos, _ -> MapActivity.open(this, tracks[pos].id) }
        list.setOnItemLongClickListener { _, _, pos, _ -> showActions(tracks[pos]); true }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        tracks = TrackStore.list(this)
        val activeId = if (TrackingService.state != State.IDLE) TrackStore.currentId(this) else null
        val rows = tracks.map {
            val mark = if (it.id == activeId) "  ● запись" else ""
            "${TrackStore.title(it.id)}$mark\n" +
                "${TrackStore.formatDistance(it.meters)} · ${TrackStore.formatDuration(it.durationMs)} · точек: ${it.points}"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
