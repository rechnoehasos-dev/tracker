package com.example.gpstracker

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Хранилище треков. Каждый трек — отдельный CSV в filesDir/tracks/<id>.csv.
 * Строка точки: time;lat;lon;alt;acc. Строка "SEG" — разрыв (пауза).
 */
object TrackStore {

    data class Point(val time: Long, val lat: Double, val lon: Double, val alt: Double)
    data class Info(val id: String, val points: Int, val meters: Float, val durationMs: Long)

    private const val SEG = "SEG"
    private val idFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private fun dir(ctx: Context) = File(ctx.filesDir, "tracks").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.csv")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tracks", Context.MODE_PRIVATE)

    /** Id трека, в который сейчас идёт (или последней шла) запись. */
    fun currentId(ctx: Context): String? =
        prefs(ctx).getString("current", null)?.takeIf { file(ctx, it).exists() }

    /** Переносит трек из старых версий (один файл track.csv) в список треков. */
    fun migrate(ctx: Context) {
        val old = File(ctx.filesDir, "track.csv")
        if (!old.exists()) return
        val first = old.useLines { s -> s.firstOrNull()?.substringBefore(';')?.toLongOrNull() }
        val id = idFormat.format(Date(first ?: System.currentTimeMillis()))
        old.renameTo(file(ctx, id))
    }

    fun newTrack(ctx: Context): String {
        val id = idFormat.format(Date())
        file(ctx, id).writeText("")
        prefs(ctx).edit().putString("current", id).apply()
        return id
    }

    fun add(ctx: Context, id: String, time: Long, lat: Double, lon: Double, alt: Double, acc: Float) {
        file(ctx, id).appendText("$time;$lat;$lon;$alt;$acc\n")
    }

    /** Отмечает паузу: следующие точки начнут новый отрезок. */
    fun breakSegment(ctx: Context, id: String) {
        val f = file(ctx, id)
        if (f.exists() && f.length() > 0) f.appendText("$SEG\n")
    }

    /** Отрезки трека (между паузами), пустые отброшены. */
    fun segments(ctx: Context, id: String): List<List<Point>> {
        val f = file(ctx, id)
        if (!f.exists()) return emptyList()
        val result = mutableListOf<MutableList<Point>>(mutableListOf())
        f.forEachLine { line ->
            if (line == SEG) {
                result.add(mutableListOf())
            } else {
                val p = line.split(';')
                if (p.size >= 4) {
                    val t = p[0].toLongOrNull()
                    val lat = p[1].toDoubleOrNull()
                    val lon = p[2].toDoubleOrNull()
                    if (t != null && lat != null && lon != null) {
                        result.last().add(Point(t, lat, lon, p[3].toDoubleOrNull() ?: 0.0))
                    }
                }
            }
        }
        return result.filter { it.isNotEmpty() }
    }

    fun distance(segments: List<List<Point>>): Float {
        var meters = 0f
        val tmp = FloatArray(1)
        for (seg in segments) for (i in 1 until seg.size) {
            Location.distanceBetween(seg[i - 1].lat, seg[i - 1].lon, seg[i].lat, seg[i].lon, tmp)
            meters += tmp[0]
        }
        return meters
    }

    /** Время в движении: сумма длительностей отрезков (без пауз). */
    fun duration(segments: List<List<Point>>): Long =
        segments.sumOf { it.last().time - it.first().time }

    fun info(ctx: Context, id: String): Info {
        val s = segments(ctx, id)
        return Info(id, s.sumOf { it.size }, distance(s), duration(s))
    }

    /** Все треки, новые сверху. */
    fun list(ctx: Context): List<Info> =
        dir(ctx).listFiles { f -> f.extension == "csv" }.orEmpty()
            .map { it.nameWithoutExtension }
            .sortedDescending()
            .map { info(ctx, it) }

    fun delete(ctx: Context, id: String) {
        file(ctx, id).delete()
    }

    /** Название трека для показа: дата и время старта. */
    fun title(id: String): String = try {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(idFormat.parse(id)!!)
    } catch (e: Exception) {
        id
    }

    fun formatDistance(m: Float): String =
        if (m >= 1000) "%.2f км".format(m / 1000) else "%.0f м".format(m)

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    /** Сохраняет трек в «Загрузки» как GPX. Возвращает имя файла или null. */
    fun exportGpx(ctx: Context, id: String): String? {
        val segs = segments(ctx, id)
        if (segs.isEmpty()) return null
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="GpsTracker" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("<trk><name>${title(id)}</name>\n")
        for (seg in segs) {
            sb.append("<trkseg>\n")
            for (p in seg) {
                sb.append("""<trkpt lat="${p.lat}" lon="${p.lon}"><ele>${p.alt}</ele><time>${iso.format(Date(p.time))}</time></trkpt>""")
                    .append('\n')
            }
            sb.append("</trkseg>\n")
        }
        sb.append("</trk>\n</gpx>\n")

        val name = "track_$id.gpx"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
        return name
    }
}
