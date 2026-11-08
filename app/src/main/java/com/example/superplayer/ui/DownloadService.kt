package com.example.superplayer.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.superplayer.R
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Descarga un vídeo detectado en la web a Descargas/SocramTV. Vale para archivos directos (mp4, mkv, webm…)
 * y para HLS (.m3u8) sin cifrar, que se junta en un único archivo .ts. Envía el mismo Referer y User-Agent
 * que usa el reproductor. Servicio en primer plano con progreso y botón para cancelar.
 */
class DownloadService : Service() {
    private val cancel = AtomicBoolean(false)
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancel.set(true)
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY
        val url = intent?.getStringExtra(EXTRA_URL) ?: run { stopSelf(); return START_NOT_STICKY }
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "video" }
        val headers = HashMap<String, String>()
        try {
            val o = JSONObject(intent.getStringExtra(EXTRA_HEADERS) ?: "{}")
            for (k in o.keys()) headers[k] = o.getString(k)
        } catch (e: Exception) { }
        cancel.set(false)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIF_ID, notif(name, getString(R.string.dl_starting), -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        try {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "socramtv:download").apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) }
        } catch (e: Exception) { }
        worker = Thread {
            var result = ""
            var ok = false
            try {
                val where = download(url, name, headers) { text, pct -> nm.notify(NOTIF_ID, notif(name, text, pct)) }
                result = getString(R.string.dl_done, where)
                ok = true
            } catch (e: CancelledException) {
                result = getString(R.string.dl_cancelled)
            } catch (e: Exception) {
                result = getString(R.string.dl_failed, e.message ?: e.javaClass.simpleName)
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            nm.notify(
                NOTIF_ID + 1,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(name).setContentText(result).setAutoCancel(true).build()
            )
            try { wake?.release() } catch (e: Exception) { }
            stopSelf()
        }.also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancel.set(true)
        try { wake?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun notif(name: String, text: String, pct: Int): android.app.Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .addAction(0, getString(R.string.dl_cancel), stop)
            .build()
    }

    private class CancelledException : Exception()

    private fun open(u: String, headers: Map<String, String>): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        for ((k, v) in headers) c.setRequestProperty(k, v)
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return c
    }

    private fun readText(u: String, headers: Map<String, String>): String =
        open(u, headers).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun download(url: String, name: String, headers: Map<String, String>, progress: (String, Int) -> Unit): String {
        val lower = url.substringBefore('?').lowercase()
        if (lower.endsWith(".mpd")) throw IllegalStateException(getString(R.string.dl_mpd))
        val hls = lower.endsWith(".m3u8") || lower.contains(".m3u8")
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(60).ifBlank { "video" }
        val ext = if (hls) "ts" else lower.substringAfterLast('.', "mp4").takeIf { it.length in 2..4 && it.all { ch -> ch.isLetterOrDigit() } } ?: "mp4"
        val mime = when (ext) { "ts" -> "video/mp2t"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; else -> "video/mp4" }
        val fileName = "$clean.$ext"
        val sink = openSink(fileName, mime)
        var okDone = false
        try {
            sink.stream.use { out ->
                if (hls) downloadHls(url, headers, out, progress) else downloadFile(url, headers, out, progress)
            }
            okDone = true
        } finally {
            if (okDone) sink.finish() else sink.discard()
        }
        return sink.where
    }

    private fun downloadFile(url: String, headers: Map<String, String>, out: OutputStream, progress: (String, Int) -> Unit) {
        val c = open(url, headers)
        val total = c.contentLengthLong
        var done = 0L
        var last = 0L
        c.inputStream.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (cancel.get()) throw CancelledException()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                val now = System.currentTimeMillis()
                if (now - last > 700) {
                    last = now
                    val mb = done / 1_048_576
                    progress(if (total > 0) "$mb / ${total / 1_048_576} MB" else "$mb MB", if (total > 0) (done * 100 / total).toInt() else -1)
                }
            }
        }
    }

    private fun resolve(base: String, ref: String): String = try { URL(URL(base), ref).toString() } catch (e: Exception) { ref }

    private fun downloadHls(url: String, headers: Map<String, String>, out: OutputStream, progress: (String, Int) -> Unit) {
        var playlistUrl = url
        var text = readText(playlistUrl, headers)
        // Lista maestra: se elige la variante de más calidad.
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            var best = -1L
            var bestUrl: String? = null
            for (i in lines.indices) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                if (bw > best) { best = bw; bestUrl = next.trim() }
            }
            if (bestUrl == null) throw IllegalStateException(getString(R.string.dl_hls_bad))
            playlistUrl = resolve(playlistUrl, bestUrl)
            text = readText(playlistUrl, headers)
        }
        if (Regex("#EXT-X-KEY:.*METHOD=(?!NONE)").containsMatchIn(text)) throw IllegalStateException(getString(R.string.dl_encrypted))
        val segs = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { resolve(playlistUrl, it) }
        if (segs.isEmpty()) throw IllegalStateException(getString(R.string.dl_hls_bad))
        if (!text.contains("#EXT-X-ENDLIST")) throw IllegalStateException(getString(R.string.dl_live))
        val buf = ByteArray(64 * 1024)
        for ((i, seg) in segs.withIndex()) {
            var attempt = 0
            while (true) {
                if (cancel.get()) throw CancelledException()
                try {
                    open(seg, headers).inputStream.use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    break
                } catch (e: CancelledException) {
                    throw e
                } catch (e: Exception) {
                    if (++attempt >= 3) throw e
                    Thread.sleep(800)
                }
            }
            progress("${i + 1} / ${segs.size}", (i + 1) * 100 / segs.size)
        }
    }

    private class Sink(val stream: OutputStream, val where: String, val finish: () -> Unit, val discard: () -> Unit)

    private fun openSink(fileName: String, mime: String): Sink {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SocramTV")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri: Uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("no se pudo crear el archivo")
            val os = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("no se pudo abrir el archivo")
            return Sink(os, "Descargas/SocramTV/$fileName", {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }, { try { contentResolver.delete(uri, null, null) } catch (e: Exception) { } })
        }
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SocramTV").apply { mkdirs() }
        val f = File(dir, fileName)
        return Sink(FileOutputStream(f), f.absolutePath, { }, { f.delete() })
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 7301
        const val ACTION_CANCEL = "cancel"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_HEADERS = "headers"

        fun start(context: Context, url: String, name: String, headers: Map<String, String>) {
            val i = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_HEADERS, JSONObject(headers as Map<*, *>).toString())
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
    }
}
