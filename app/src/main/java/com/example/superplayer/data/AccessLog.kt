package com.example.superplayer.data

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Historial de accesos (intentos de PIN). Se guarda siempre en este dispositivo (como mucho [MAX_LOCAL] entradas) y,
 * si el archivo de acceso trae `log_url`, cada entrada se envía también ahí (POST con JSON) para que el administrador
 * vea los de todos los dispositivos (con GET a la misma URL se devuelve la lista; ver README, «Historial de accesos»).
 * NUNCA se guarda ni se envía el PIN escrito: solo el resultado.
 */
object AccessLog {
    private const val MAX_LOCAL = 500
    private const val FILE = "access_log.jsonl"
    private const val PREFS = "access_device"
    private const val K_ID = "id"

    class Event(val ts: Long, val device: String, val model: String, val code: String, val section: String, val result: String)

    /** Identificador corto de esta instalación (se genera una vez). El administrador lo usa para autorizar dispositivos. */
    fun deviceId(c: Context): String {
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = p.getString(K_ID, null)
        if (id.isNullOrEmpty()) {
            id = java.lang.Long.toHexString(java.security.SecureRandom().nextLong()).padStart(16, '0').take(8).uppercase()
            p.edit().putString(K_ID, id).apply()
        }
        return id
    }

    fun model(): String = (Build.MANUFACTURER.orEmpty() + " " + Build.MODEL.orEmpty()).trim()

    fun deviceLabel(c: Context) = "${deviceId(c)} (${model()})"

    @Synchronized
    fun record(c: Context, logUrl: String?, code: String, section: String, result: String) {
        val app = c.applicationContext
        val e = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("device", deviceId(app))
            .put("model", model())
            .put("code", code)
            .put("section", section)
            .put("result", result)
        try {
            val f = File(app.filesDir, FILE)
            f.appendText(e.toString() + "\n")
            val lines = f.readLines()
            if (lines.size > MAX_LOCAL) f.writeText(lines.takeLast(MAX_LOCAL).joinToString("\n") + "\n")
        } catch (ex: Exception) { }
        if (!logUrl.isNullOrBlank()) {
            val body = e.toString()
            Thread({
                try {
                    val conn = URL(logUrl).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 6_000
                    conn.readTimeout = 6_000
                    conn.instanceFollowRedirects = false
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                    conn.disconnect()
                } catch (ex: Exception) { }
            }, "access-report").start()
        }
    }

    private fun parse(o: JSONObject) = Event(
        o.optLong("ts"), o.optString("device"), o.optString("model"), o.optString("code"), o.optString("section"), o.optString("result")
    )

    /** Las últimas entradas de este dispositivo, la más reciente primero. */
    fun local(c: Context): List<Event> {
        return try {
            val f = File(c.applicationContext.filesDir, FILE)
            if (!f.exists()) emptyList()
            else f.readLines().mapNotNull { try { parse(JSONObject(it)) } catch (e: Exception) { null } }.asReversed()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearLocal(c: Context) {
        try { File(c.applicationContext.filesDir, FILE).delete() } catch (e: Exception) { }
    }

    /** Historial de todos los dispositivos desde `log_url` (GET). Bloquea: hilo de fondo. */
    fun fetchRemote(url: String): List<Event> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> parse(o) } }.sortedByDescending { it.ts }
        } finally {
            conn.disconnect()
        }
    }
}
