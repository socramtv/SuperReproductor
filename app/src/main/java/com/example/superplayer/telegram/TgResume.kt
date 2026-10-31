package com.example.superplayer.telegram

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * «Seguir viendo» de Telegram: por cada capítulo o película que se dejó a medias guarda la posición y el mensaje
 * (para volver a reproducirlo directo). También recuerda dónde acaba la intro de cada serie («Saltar intro»).
 * La clave es la misma que usa [TgWatched] (capítulo o película, no el mensaje).
 */
object TgResume {
    private const val PREFS = "tg_resume"
    private const val MAX = 30
    private const val MIN_WATCHED_MS = 30_000L
    private const val FINISHED_MARGIN_MS = 90_000L

    class Meta(val key: String, val message: TgMessage, val thumb: Int)
    class Entry(val key: String, val message: TgMessage, val thumb: Int, val posMs: Long, val durMs: Long, val at: Long)

    private val byUrl = ConcurrentHashMap<String, Meta>()

    @Volatile
    var version = 0
        private set

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Al preparar un stream: qué mensaje es (para poder volver a abrirlo desde «Seguir viendo»). */
    fun register(url: String, key: String, m: TgMessage, thumb: Int) {
        if (byUrl.size > 5000) byUrl.clear()
        byUrl[url] = Meta(key, m, thumb)
    }

    fun isTelegramUrl(url: String) = byUrl.containsKey(url)

    fun keyFor(url: String): String? = byUrl[url]?.key

    /** Serie a la que pertenece una clave de capítulo («s:nombre:1x02» -> «s:nombre»); null si es una película. */
    fun seriesOf(key: String): String? = if (key.startsWith("s:")) key.substringBeforeLast(':') else null

    fun getAll(c: Context): List<Entry> = try {
        val arr = JSONArray(prefs(c).getString("entries", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(o.getString("key"), TgMessage.fromJson(o.getJSONObject("m")), o.optInt("th", 0), o.getLong("pos"), o.getLong("dur"), o.optLong("at"))
        }.sortedByDescending { it.at }
    } catch (e: Exception) {
        emptyList()
    }

    fun positionFor(c: Context, url: String): Long? {
        val key = byUrl[url]?.key ?: return null
        return getAll(c).firstOrNull { it.key == key }?.posMs
    }

    private fun write(c: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list.take(MAX)) {
            arr.put(JSONObject().put("key", e.key).put("m", e.message.toJson()).put("th", e.thumb)
                .put("pos", e.posMs).put("dur", e.durMs).put("at", e.at))
        }
        prefs(c).edit().putString("entries", arr.toString()).apply()
        version++
    }

    /** Desde el reproductor, al salir o cambiar de capítulo: guarda dónde se queda, o lo quita si apenas se empezó o ya se vio. */
    fun onProgress(c: Context, url: String, posMs: Long, durMs: Long) {
        val meta = byUrl[url] ?: return
        if (durMs <= 0) return
        val cur = getAll(c).filter { it.key != meta.key }
        if (posMs < MIN_WATCHED_MS || durMs - posMs < FINISHED_MARGIN_MS) {
            if (cur.size != getAll(c).size) write(c, cur)
            return
        }
        write(c, listOf(Entry(meta.key, meta.message, meta.thumb, posMs, durMs, System.currentTimeMillis())) + cur)
    }

    fun remove(c: Context, key: String) {
        val all = getAll(c)
        val cur = all.filter { it.key != key }
        if (cur.size != all.size) write(c, cur)
    }

    // ---- Saltar intro ----

    /** Dónde acaba la intro de esta serie (ms), si el usuario ya la saltó antes. */
    fun introEnd(c: Context, series: String): Long = prefs(c).getLong("intro:$series", 0L)

    fun setIntroEnd(c: Context, series: String, ms: Long) {
        prefs(c).edit().putLong("intro:$series", ms).apply()
    }
}
