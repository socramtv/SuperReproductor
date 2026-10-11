package com.example.superplayer.telegram

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Botón de un bot: "cb" (de la botonera bajo el mensaje), "url" (enlace) o "txt" (teclado de respuesta). */
data class TgButton(val text: String, val kind: String, val data: String)

/** Un mensaje guardado de un canal o grupo donde está el bot. */
data class TgMessage(
    val chatId: Long,
    val chatTitle: String,
    val chatType: String,
    val messageId: Long,
    val date: Long,
    val text: String,
    /** "video", "audio", "voice", "animation", "document" o "none". */
    val kind: String,
    val fileId: String?,
    val fileName: String?,
    val mime: String?,
    val size: Long,
    val links: List<String>,
    val buttons: List<TgButton> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("c", chatId).put("ct", chatTitle).put("cy", chatType)
        .put("m", messageId).put("d", date).put("t", text).put("k", kind)
        .put("f", fileId ?: "").put("n", fileName ?: "").put("mi", mime ?: "")
        .put("s", size).put("l", JSONArray(links))

    companion object {
        fun fromJson(o: JSONObject): TgMessage {
            val arr = o.optJSONArray("l")
            val links = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
            return TgMessage(
                o.optLong("c"), o.optString("ct"), o.optString("cy"), o.optLong("m"), o.optLong("d"),
                o.optString("t"), o.optString("k", "none"),
                o.optString("f").ifEmpty { null }, o.optString("n").ifEmpty { null }, o.optString("mi").ifEmpty { null },
                o.optLong("s"), links
            )
        }
    }
}

/**
 * Soporte de Telegram mediante un BOT (Bot API oficial): el bot se añade al
 * canal o grupo (en un canal, como administrador) y la app va recogiendo sus
 * mensajes con getUpdates. Se guardan en el móvil para poder verlos después.
 * Límite de Telegram para bots: solo se pueden descargar/reproducir archivos
 * de hasta 20 MB; los enlaces dentro de los mensajes no tienen límite.
 */
object TelegramBot {
    private const val API = "https://api.telegram.org"
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    private const val MAX_STORED = 1500
    private val LINK = Regex("https?://[^\\s<>\"']+")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("telegram_bot", Context.MODE_PRIVATE)

    fun getToken(context: Context): String = prefs(context).getString("token", "").orEmpty().trim()

    fun setToken(context: Context, token: String) {
        val clean = token.trim()
        if (clean != getToken(context)) {
            // Otro bot: se empieza de cero.
            prefs(context).edit().putString("token", clean).putLong("offset", 0L).remove("msgs").apply()
        }
    }

    private fun call(token: String, method: String, params: Map<String, String>, timeoutSec: Int): JSONObject? {
        val body = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = URL("$API/bot$token/$method").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = (timeoutSec + 10) * 1000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: return null
            JSONObject(text)
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Comprueba el token: devuelve "@usuario" del bot, o null si no vale. */
    fun checkToken(token: String): String? {
        val r = call(token, "getMe", emptyMap(), 10) ?: return null
        if (!r.optBoolean("ok")) return null
        return "@" + (r.optJSONObject("result")?.optString("username") ?: "bot")
    }

    /** Resultado de una consulta: nuevos mensajes guardados, o -1 si falló. */
    fun poll(context: Context, timeoutSec: Int): Int {
        val token = getToken(context)
        if (token.isEmpty()) return -1
        val p = prefs(context)
        if (!p.getBoolean("webhook_cleared", false)) {
            // getUpdates no funciona si el bot tuviera un webhook puesto.
            val r = call(token, "deleteWebhook", emptyMap(), 10)
            if (r?.optBoolean("ok") == true) p.edit().putBoolean("webhook_cleared", true).apply()
        }
        val offset = p.getLong("offset", 0L)
        val params = HashMap<String, String>()
        params["timeout"] = timeoutSec.toString()
        if (offset > 0) params["offset"] = offset.toString()
        params["allowed_updates"] = "[\"message\",\"channel_post\",\"edited_message\",\"edited_channel_post\"]"
        val r = call(token, "getUpdates", params, timeoutSec) ?: return -1
        if (!r.optBoolean("ok")) return -1
        val updates = r.optJSONArray("result") ?: return 0
        if (updates.length() == 0) return 0
        var maxId = offset
        val found = ArrayList<TgMessage>()
        for (i in 0 until updates.length()) {
            val u = updates.optJSONObject(i) ?: continue
            maxId = maxOf(maxId, u.optLong("update_id") + 1)
            val m = u.optJSONObject("message") ?: u.optJSONObject("channel_post")
                ?: u.optJSONObject("edited_message") ?: u.optJSONObject("edited_channel_post") ?: continue
            parseMessage(m)?.let { found.add(it) }
        }
        merge(context, found)
        p.edit().putLong("offset", maxId).apply()
        return found.size
    }

    private fun parseMessage(m: JSONObject): TgMessage? {
        val chat = m.optJSONObject("chat") ?: return null
        val chatId = chat.optLong("id")
        val type = chat.optString("type")
        val title = chat.optString("title").ifEmpty {
            listOf(chat.optString("first_name"), chat.optString("last_name")).filter { it.isNotEmpty() }
                .joinToString(" ").ifEmpty { chat.optString("username", "Chat") }
        }
        val text = m.optString("text").ifEmpty { m.optString("caption") }
        var kind = "none"
        var media: JSONObject? = null
        for (k in listOf("video", "animation", "audio", "voice", "document")) {
            val o = m.optJSONObject(k)
            if (o != null) { kind = k; media = o; break }
        }
        val links = LINK.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', ';', '!') }.distinct().toList()
        if (kind == "none" && links.isEmpty() && text.isEmpty()) return null
        return TgMessage(
            chatId, title, type, m.optLong("message_id"), m.optLong("date"), text, kind,
            media?.optString("file_id")?.ifEmpty { null },
            media?.optString("file_name")?.ifEmpty { null },
            media?.optString("mime_type")?.ifEmpty { null },
            media?.optLong("file_size") ?: 0L,
            links
        )
    }

    private fun merge(context: Context, fresh: List<TgMessage>) {
        if (fresh.isEmpty()) return
        synchronized(this) {
            val all = ArrayList(loadAll(context))
            for (m in fresh) {
                all.removeAll { it.chatId == m.chatId && it.messageId == m.messageId }
                all.add(m)
            }
            all.sortBy { it.date }
            val trimmed = if (all.size > MAX_STORED) all.subList(all.size - MAX_STORED, all.size) else all
            val arr = JSONArray()
            trimmed.forEach { arr.put(it.toJson()) }
            prefs(context).edit().putString("msgs", arr.toString()).apply()
        }
    }

    fun loadAll(context: Context): List<TgMessage> {
        val raw = prefs(context).getString("msgs", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> TgMessage.fromJson(o) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove("msgs").apply()
    }

    /** URL de descarga/streaming de un archivo, o un texto de error en [error]. */
    class FileResult(val url: String?, val error: String?)

    fun fileUrl(context: Context, fileId: String): FileResult {
        val token = getToken(context)
        val r = call(token, "getFile", mapOf("file_id" to fileId), 15) ?: return FileResult(null, "sin conexión")
        if (!r.optBoolean("ok")) return FileResult(null, r.optString("description", "error"))
        val path = r.optJSONObject("result")?.optString("file_path").orEmpty()
        if (path.isEmpty()) return FileResult(null, "sin ruta de archivo")
        return FileResult("$API/file/bot$token/$path", null)
    }
}
