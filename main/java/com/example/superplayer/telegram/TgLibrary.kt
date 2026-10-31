package com.example.superplayer.telegram

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Biblioteca de Telegram: los vídeos de los canales/grupos que el usuario elige como «fuentes»,
 * guardados en un índice local (un archivo JSON) para abrir la pestaña al instante.
 * Cada entrada es el mensaje en JSON (TgMessage.toJson) + «th» (miniatura del chat).
 */
object TgLibrary {
    private const val PREFS = "tg_library"
    private const val FILE = "tg_library.json"
    /** Páginas de 50 mensajes que se leen de cada fuente al actualizar (las más recientes). */
    const val PAGES = 8
    /** Pasadas estas horas desde la última lectura, al abrir la pestaña se actualiza sola. */
    const val AUTO_HOURS = 6
    const val MAX_ENTRIES = 8000

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun sources(c: Context): Set<Long> =
        (prefs(c).getStringSet("sources", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()

    fun setSources(c: Context, ids: Set<Long>) {
        prefs(c).edit().putStringSet("sources", ids.map { it.toString() }.toSet()).apply()
    }

    /** Copias repetidas que el usuario ha ocultado de la biblioteca («chatId:mensaje»); no se borra nada de Telegram. */
    fun hidden(c: Context): Set<String> = prefs(c).getStringSet("hidden", emptySet()) ?: emptySet()

    fun setHidden(c: Context, keys: Set<String>) {
        prefs(c).edit().putStringSet("hidden", keys).apply()
    }

    fun keyOf(m: TgMessage) = "${m.chatId}:${m.messageId}"

    /** Lista de deseos: títulos de películas o series que aún no se tienen. */
    fun wishlist(c: Context): List<String> = try {
        val arr = JSONArray(prefs(c).getString("wishlist", "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun setWishlist(c: Context, list: List<String>) {
        prefs(c).edit().putString("wishlist", JSONArray(list).toString()).apply()
    }

    fun lastScan(c: Context): Long = prefs(c).getLong("last_scan", 0L)

    fun isStale(c: Context): Boolean = System.currentTimeMillis() - lastScan(c) > AUTO_HOURS * 3600_000L

    fun load(c: Context): List<Pair<TgMessage, Int>> = try {
        val f = File(c.applicationContext.filesDir, FILE)
        if (!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                TgMessage.fromJson(o) to o.optInt("th", 0)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(c: Context, entries: List<Pair<TgMessage, Int>>) {
        try {
            val arr = JSONArray()
            // Solo lo necesario: el texto se recorta (el título está en su primera línea) y se guardan como máximo los 8000 más recientes.
            for ((m, th) in entries.sortedByDescending { it.first.date }.take(MAX_ENTRIES)) {
                val slim = m.copy(text = m.text.lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n").take(300))
                arr.put(slim.toJson().put("th", th))
            }
            File(c.applicationContext.filesDir, FILE).writeText(arr.toString())
            prefs(c).edit().putLong("last_scan", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
        }
    }
}
