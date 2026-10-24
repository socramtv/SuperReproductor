package com.example.superplayer.telegram

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * «Visto» de los vídeos de Telegram. La clave es del capítulo o la película (no del mensaje): así, si el mismo
 * capítulo está repetido en varios canales o en varias calidades, todas las copias cuentan como vistas.
 * El reproductor avisa por URL (ver [register] y [onProgress]); la pantalla de Telegram pone y quita marcas a mano.
 */
object TgWatched {
    private const val PREFS = "tg_watched"
    private const val KEY = "seen"
    /** Parte de la duración a partir de la cual se da por visto. */
    private const val SEEN_FRACTION = 0.9

    private val urlToKey = ConcurrentHashMap<String, String>()

    /** Sube cada vez que cambia algo: la pantalla de Telegram lo compara al volver del reproductor para repintar las marcas. */
    @Volatile
    var version = 0
        private set

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun all(c: Context): Set<String> = prefs(c).getStringSet(KEY, emptySet()) ?: emptySet()

    /** Dice qué capítulo/película es el stream de esta URL (la URL local con la que se reproduce el archivo de Telegram). */
    fun register(url: String, watchKey: String) {
        if (urlToKey.size > 5000) urlToKey.clear()
        urlToKey[url] = watchKey
    }

    fun isWatched(c: Context, watchKey: String): Boolean = watchKey in all(c)

    fun set(c: Context, watchKey: String, on: Boolean) = setAll(c, listOf(watchKey), on)

    fun setAll(c: Context, keys: Collection<String>, on: Boolean) {
        val cur = HashSet(all(c))
        val changed = if (on) cur.addAll(keys) else cur.removeAll(keys.toSet())
        if (changed) {
            prefs(c).edit().putStringSet(KEY, cur).apply()
            version++
        }
    }

    /** Desde el reproductor: al salir o cambiar de capítulo, y al acabar. */
    fun onProgress(c: Context, url: String, positionMs: Long, durationMs: Long) {
        val key = urlToKey[url] ?: return
        if (durationMs > 0 && positionMs >= durationMs * SEEN_FRACTION) set(c, key, true)
    }

    fun onEnded(c: Context, url: String) {
        val key = urlToKey[url] ?: return
        set(c, key, true)
    }
}
