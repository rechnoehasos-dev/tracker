package com.example.gpstracker

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Хранит точки трека в CSV (time;lat;lon;alt;acc) и экспортирует в GPX. */
object TrackStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "track.csv")

    fun add(ctx: Context, time: Long, lat: Double, lon: Double, alt: Double, acc: Float) {
        file(ctx).appendText("$time;$lat;$lon;$alt;$acc\n")
    }

    fun count(ctx: Context): Int =
        file(ctx).takeIf { it.exists() }?.readLines()?.count { it.isNotBlank() } ?: 0

    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    /** Сохраняет трек в «Загрузки» как GPX. Возвращает имя файла или null. */
    fun exportGpx(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="GpsTracker" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("<trk><name>Track</name><trkseg>\n")
        f.forEachLine { line ->
            val p = line.split(';')
            if (p.size >= 4) {
                sb.append("""<trkpt lat="${p[1]}" lon="${p[2]}"><ele>${p[3]}</ele><time>${iso.format(Date(p[0].toLong()))}</time></trkpt>""")
                    .append('\n')
            }
        }
        sb.append("</trkseg></trk>\n</gpx>\n")

        val name = "track_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".gpx"
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
