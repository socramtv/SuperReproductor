package com.example.superplayer.data

import android.content.Context
import com.example.superplayer.model.Stream
import com.example.superplayer.model.streamFromJson
import com.example.superplayer.model.toJson
import java.io.File

/**
 * Favoritos guardados localmente en SharedPreferences, identificados por
 * URL del stream (ver Stream.id). Además del conjunto de ids de siempre
 * (para isFavorite/getAll, usados para la categoría "⭐ Favoritos" de la
 * lista de canales, que ya filtra la lista ACTUALMENTE cargada por id),
 * guarda el Stream COMPLETO de cada uno (ver Stream.toJson): lo necesitan
 * los accesos directos del icono de la app (ver player/ShortcutsHelper.kt
 * y MainActivity.refreshShortcuts), que tienen que poder abrir un canal
 * favorito aunque el proceso acabe de arrancar y todavía no haya ninguna
 * lista cargada de la que sacarlo por id.
 */
class FavoritesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun isFavorite(streamId: String): Boolean =
        prefs.getStringSet(KEY_IDS, emptySet())?.contains(streamId) == true

    /** Cambia el estado de favorito y devuelve el nuevo valor (true = ahora es favorito). */
    fun toggle(stream: Stream): Boolean {
        val current = HashSet(prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet())
        val editor = prefs.edit()
        val nowFavorite = if (current.contains(stream.id)) {
            current.remove(stream.id)
            editor.remove(jsonKey(stream.id))
            false
        } else {
            current.add(stream.id)
            editor.putString(jsonKey(stream.id), stream.toJson())
            true
        }
        editor.putStringSet(KEY_IDS, current).apply()
        return nowFavorite
    }

    fun getAll(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()

    /** Los Stream completos de los favoritos que sí tienen datos guardados (ver refreshStoredStreams), para los accesos directos. */
    fun getAllStreams(): List<Stream> {
        val ids = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        return ids.mapNotNull { id -> prefs.getString(jsonKey(id), null)?.let { streamFromJson(it) } }
    }

    /**
     * Actualiza el Stream guardado de cada favorito que también esté en
     * [streams] (la lista recién cargada): por si cambió algo del canal
     * (logo, url, cabeceras...) desde la última vez, o por si se marcó
     * como favorito antes de que existiera esto y todavía no tenía nada
     * guardado. Los favoritos que no estén en ESTA lista en concreto
     * (están en otra de tus 5 listas) se dejan tal cual: solo toggle()
     * los añade o quita de verdad.
     */
    fun refreshStoredStreams(streams: List<Stream>) {
        val ids = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        if (ids.isEmpty()) return
        val byId = streams.associateBy { it.id }
        val editor = prefs.edit()
        var changed = false
        for (id in ids) {
            val stream = byId[id] ?: continue
            editor.putString(jsonKey(id), stream.toJson())
            changed = true
        }
        if (changed) editor.apply()
    }

    /**
     * Añade estos Stream a los favoritos (copia de seguridad, ver
     * BackupManager): une con los que ya hay, no borra ninguno. Devuelve
     * cuántos eran nuevos.
     */
    fun addAll(streams: List<Stream>): Int {
        val current = HashSet(prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet())
        val editor = prefs.edit()
        var added = 0
        for (stream in streams) {
            if (current.add(stream.id)) added++
            editor.putString(jsonKey(stream.id), stream.toJson())
        }
        editor.putStringSet(KEY_IDS, current).apply()
        return added
    }

    private fun jsonKey(streamId: String) = "fav_json_$streamId"

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
     * Filtro de categorías por lista (ver MainActivity.showCategoryFilterDialog):
     * guarda las categorías que el usuario ha OCULTADO de esa lista (no las
     * visibles), para que una categoría nueva que aparezca más tarde en la
     * lista remota se vea por defecto. `listKey` identifica la lista:
     * "slot_1".."slot_5" para los huecos remotos, "file" para el archivo
     * local, "sample" para la lista de ejemplo.
     */
    fun getHiddenCategories(context: Context, listKey: String): Set<String> =
        HashSet(prefs(context).getStringSet("hidden_cats_$listKey", emptySet()) ?: emptySet())

    fun setHiddenCategories(context: Context, listKey: String, hidden: Set<String>) {
        prefs(context).edit().putStringSet("hidden_cats_$listKey", HashSet(hidden)).apply()
    }

    /** Todos los filtros de categorías guardados, por clave de lista (para la copia de seguridad, ver BackupManager). */
    fun getAllHiddenCategories(context: Context): Map<String, Set<String>> {
        val result = HashMap<String, Set<String>>()
        for ((key, value) in prefs(context).all) {
            if (key.startsWith("hidden_cats_") && value is Set<*>) {
                result[key.removePrefix("hidden_cats_")] = value.filterIsInstance<String>().toSet()
            }
        }
        return result
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
