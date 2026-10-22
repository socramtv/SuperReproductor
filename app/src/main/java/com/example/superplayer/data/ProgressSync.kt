package com.example.superplayer.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.superplayer.model.Stream
import com.example.superplayer.model.streamFromJson
import com.example.superplayer.model.toJson
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * «Sigo viendo en otro dispositivo»: sincroniza «Continuar viendo» entre los dispositivos que se han desbloqueado con el
 * mismo código de acceso (los datos se guardan bajo un identificador derivado del código, no reversible). Usa el servicio
 * de `sync_url` (o `log_url`) del archivo de acceso: el mismo Google Apps Script del historial, en su versión con
 * «progreso» (ver README). Sin esa URL, o sin código desbloqueado, no se sincroniza nada. Lo de Telegram no entra.
 */
object ProgressSync {
    private const val MIN_PULL_MS = 45_000L
    @Volatile private var lastPull = 0L
    private val main = Handler(Looper.getMainLooper())

    private fun target(c: Context): Pair<String, String>? {
        val url = AccessControl.syncUrl(c) ?: return null
        val id = AccessControl.syncIdentity(c) ?: return null
        return url to id
    }

    /** Sube la posición de [stream] (o su borrado si [removed]). Fuera del hilo principal, sin bloquear. */
    fun push(c: Context, stream: Stream, positionMs: Long, durationMs: Long, at: Long, removed: Boolean) {
        val app = c.applicationContext
        val (url, key) = target(app) ?: return
        Thread({
            try {
                val body = JSONObject().put("type", "progress").put("key", key).put("id", stream.id).put("at", at).put("removed", removed)
                if (!removed) {
                    val entry = JSONObject()
                        .put("stream", JSONObject(stream.toJson()))
                        .put("pos", positionMs).put("dur", durationMs).put("at", at)
                    body.put("entry", entry.toString())
                }
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 6_000
                conn.readTimeout = 6_000
                conn.instanceFollowRedirects = false
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) { }
        }, "progress-push").start()
    }

    /** Baja lo que dejaron los otros dispositivos y lo mezcla con lo local (gana lo más reciente). [onChanged] en el hilo principal. */
    fun pull(c: Context, force: Boolean = false, onChanged: (() -> Unit)? = null) {
        val app = c.applicationContext
        val (url, key) = target(app) ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPull < MIN_PULL_MS) return
        lastPull = now
        Thread({
            try {
                val sep = if (url.contains('?')) "&" else "?"
                val conn = URL(url + sep + "type=progress&key=" + key).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 10_000
                val arr = try { JSONArray(conn.inputStream.bufferedReader().readText()) } finally { conn.disconnect() }
                val incoming = ArrayList<ContinueWatching.Entry>()
                var changed = false
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val at = o.optLong("at")
                    val removed = o.optInt("removed", 0) == 1 || o.optBoolean("removed", false)
                    if (removed) {
                        if (ContinueWatching.removeIfOlder(app, id, at)) changed = true
                        continue
                    }
                    val e = JSONObject(o.optString("entry", "{}"))
                    val stream = streamFromJson(e.getJSONObject("stream").toString()) ?: continue
                    incoming.add(ContinueWatching.Entry(stream, e.getLong("pos"), e.getLong("dur"), e.optLong("at", at)))
                }
                if (ContinueWatching.importNewer(app, incoming) > 0) changed = true
                if (changed && onChanged != null) main.post { onChanged() }
            } catch (e: Exception) { }
        }, "progress-pull").start()
    }
}
