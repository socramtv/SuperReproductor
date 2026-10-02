package com.example.superplayer.data

import android.content.Context
import java.io.File

/** Favoritos guardados localmente en SharedPreferences (identificados por URL del stream). */
class FavoritesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun isFavorite(streamId: String): Boolean =
        prefs.getStringSet(KEY_IDS, emptySet())?.contains(streamId) == true

    /** Cambia el estado de favorito y devuelve el nuevo valor (true = ahora es favorito). */
    fun toggle(streamId: String): Boolean {
        val current = HashSet(prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet())
        val nowFavorite = if (current.contains(streamId)) {
            current.remove(streamId)
            false
        } else {
            current.add(streamId)
            true
        }
        prefs.edit().putStringSet(KEY_IDS, current).apply()
        return nowFavorite
    }

    fun getAll(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()

    companion object {
        private const val KEY_IDS = "favorite_ids"
    }
}

/** Recuerda cuál fue el último JSON de playlist cargado, para reabrirlo al iniciar la app. */
object AppPrefs {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_LAST_URI = "last_playlist_uri"
    private const val KEY_DARK_MODE = "dark_mode"

    fun saveLastPlaylistUri(context: Context, uriString: String) {
        prefs(context).edit().putString(KEY_LAST_URI, uriString).apply()
    }

    fun getLastPlaylistUri(context: Context): String? =
        prefs(context).getString(KEY_LAST_URI, null)

    fun getListUrl(context: Context, slot: Int): String? =
        prefs(context).getString("list_url_$slot", null)

    fun saveListUrl(context: Context, slot: Int, url: String) {
        prefs(context).edit().putString("list_url_$slot", url).apply()
    }

    /**
     * Modo claro/oscuro elegido a mano (ver MainActivity.toggleTheme): por
     * defecto oscuro, que es como ha sido siempre la app hasta ahora, para
     * que a quien ya la tenga instalada no le cambie el aspecto solo al
     * actualizar.
     */
    fun isDarkMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DARK_MODE, true)

    fun setDarkMode(context: Context, dark: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK_MODE, dark).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Guarda en disco el contenido (JSON o M3U, tal cual se descargó, sin volver
 * a analizarlo) de la última descarga que tuvo éxito de cada uno de los 5
 * "huecos" de lista remota (ver MainActivity.loadFromRemoteUrl). Así, si en
 * algún momento no hay conexión, esa lista puede seguir abriéndose con la
 * última copia que sí se descargó bien, en vez de no cargar nada. Solo se
 * guarda una copia cuando la descarga Y el análisis posterior salen bien
 * (nunca una respuesta a medias o un error disfrazado de servidor), así que
 * la copia guardada siempre es válida.
 */
object PlaylistCache {
    fun save(context: Context, slot: Int, rawText: String) {
        try {
            cacheFile(context, slot).writeText(rawText)
        } catch (e: Exception) {
            // Si no se puede escribir (poco espacio, etc.) no pasa nada grave:
            // simplemente no habrá copia de respaldo la próxima vez sin red.
        }
    }

    /** El contenido guardado para ese hueco, o null si todavía no se ha descargado nunca con éxito. */
    fun load(context: Context, slot: Int): String? {
        val file = cacheFile(context, slot)
        if (!file.exists()) return null
        return try {
            file.readText().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** Fecha/hora (epoch ms) de la copia guardada para ese hueco, o null si no hay ninguna. */
    fun lastSavedAt(context: Context, slot: Int): Long? {
        val file = cacheFile(context, slot)
        return if (file.exists()) file.lastModified() else null
    }

    private fun cacheFile(context: Context, slot: Int): File =
        File(context.applicationContext.filesDir, "remote_list_cache_$slot.txt")
}
