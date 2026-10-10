package com.example.superplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
        val order = ArrayList(getOrder())
        val nowFavorite = if (current.contains(stream.id)) {
            current.remove(stream.id)
            order.remove(stream.id)
            editor.remove(jsonKey(stream.id))
            false
        } else {
            current.add(stream.id)
            // Un favorito nuevo se pone al final del orden manual.
            order.remove(stream.id)
            order.add(stream.id)
            editor.putString(jsonKey(stream.id), stream.toJson())
            true
        }
        editor.putStringSet(KEY_IDS, current).putString(KEY_ORDER, JSONArray(order).toString()).apply()
        return nowFavorite
    }

    /** Orden manual de los favoritos (ids de canal), el que se elige en "Ordenar favoritos". Los favoritos que no salgan aquí van al final. */
    fun getOrder(): List<String> {
        val raw = prefs.getString(KEY_ORDER, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setOrder(ids: List<String>) {
        prefs.edit().putString(KEY_ORDER, JSONArray(ids).toString()).apply()
    }

    /** Ordena [streams] según el orden manual de favoritos (estable: los que no tengan posición guardada mantienen su orden y van al final). */
    fun sortByOrder(streams: List<Stream>): List<Stream> {
        val position = HashMap<String, Int>()
        getOrder().forEachIndexed { i, id -> position.putIfAbsent(id, i) }
        return streams.sortedBy { position[it.id] ?: Int.MAX_VALUE }
    }

    fun getAll(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()

    /** Los Stream completos de los favoritos que sí tienen datos guardados (ver refreshStoredStreams), para los accesos directos. */
    fun getAllStreams(): List<Stream> {
        val ids = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        return sortByOrder(ids.mapNotNull { id -> prefs.getString(jsonKey(id), null)?.let { streamFromJson(it) } })
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
        val order = ArrayList(getOrder())
        var added = 0
        for (stream in streams) {
            if (current.add(stream.id)) {
                added++
                if (stream.id !in order) order.add(stream.id)
            }
            editor.putString(jsonKey(stream.id), stream.toJson())
        }
        editor.putStringSet(KEY_IDS, current).putString(KEY_ORDER, JSONArray(order).toString()).apply()
        return added
    }

    private fun jsonKey(streamId: String) = "fav_json_$streamId"

    companion object {
        private const val KEY_IDS = "favorite_ids"
        private const val KEY_ORDER = "favorite_order"
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

    /**
     * Orden de categorías elegido por el usuario para cada lista (ver
     * MainActivity.showCategoryOrderDialog): los nombres, en orden. Vacío =
     * el orden original de la lista. Las categorías que no estén en este
     * orden (nuevas en la lista remota) salen al final.
     */
    fun getCategoryOrder(context: Context, listKey: String): List<String> {
        val raw = prefs(context).getString("cat_order_$listKey", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setCategoryOrder(context: Context, listKey: String, order: List<String>) {
        val editor = prefs(context).edit()
        if (order.isEmpty()) editor.remove("cat_order_$listKey")
        else editor.putString("cat_order_$listKey", org.json.JSONArray(order).toString())
        editor.apply()
    }

    /** Todos los órdenes guardados, por clave de lista (para la copia de seguridad). */
    fun getAllCategoryOrders(context: Context): Map<String, List<String>> {
        val result = HashMap<String, List<String>>()
        for (key in prefs(context).all.keys) {
            if (key.startsWith("cat_order_")) {
                val listKey = key.removePrefix("cat_order_")
                val order = getCategoryOrder(context, listKey)
                if (order.isNotEmpty()) result[listKey] = order
            }
        }
        return result
    }

    /**
     * Formato de pantalla del reproductor (ver PlayerActivity.cycleVideoFormat):
     * 0 = ajustar (con barras si hace falta, como siempre), 1 = estirar hasta
     * llenar la pantalla, 2 = zoom (llenar recortando los bordes). Un solo
     * valor para todos los canales, se recuerda al cerrar la app.
     */
    fun getVideoFormat(context: Context): Int = prefs(context).getInt("video_format", 0)

    fun setVideoFormat(context: Context, format: Int) {
        prefs(context).edit().putInt("video_format", format).apply()
    }

    /** Si un canal de vídeo/TV sigue sonando (solo audio) al bloquear la pantalla o cambiar de app. Por defecto sí. */
    fun isBackgroundAudio(context: Context): Boolean = prefs(context).getBoolean("background_audio", true)

    fun setBackgroundAudio(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("background_audio", enabled).apply()
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


/**
 * "Continuar viendo": posición donde se dejó cada película/vídeo (no los
 * canales en directo; ver PlayerActivity.saveContinueWatching). Se guardan
 * los últimos [MAX_ENTRIES], los más recientes primero, con el Stream
 * completo para poder reabrirlos desde cualquier lista.
 */
object ContinueWatching {
    private const val PREFS_NAME = "continue_watching"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 30

    /** Menos de esto vistos = no se considera "empezado"; y si falta menos de esto para el final, se considera "terminado". */
    private const val MIN_WATCHED_MS = 30_000L
    private const val FINISHED_MARGIN_MS = 90_000L

    data class Entry(val stream: Stream, val positionMs: Long, val durationMs: Long, val updatedAt: Long)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = ArrayList<Entry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val stream = streamFromJson(obj.getJSONObject("stream").toString()) ?: continue
                result.add(Entry(stream, obj.getLong("pos"), obj.getLong("dur"), obj.optLong("at", 0L)))
            }
            result.sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun positionFor(context: Context, streamId: String): Long? =
        getAll(context).firstOrNull { it.stream.id == streamId }?.positionMs

    /** Guarda (o actualiza) la posición; si se acaba de empezar o ya se vio casi entero, lo quita de la lista en vez de guardarlo. */
    fun save(context: Context, stream: Stream, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val finished = durationMs - positionMs < FINISHED_MARGIN_MS
        if (positionMs < MIN_WATCHED_MS || finished) {
            remove(context, stream.id)
            return
        }
        val entries = getAll(context).filter { it.stream.id != stream.id }.toMutableList()
        entries.add(0, Entry(stream, positionMs, durationMs, System.currentTimeMillis()))
        write(context, entries.take(MAX_ENTRIES))
    }

    fun remove(context: Context, streamId: String) {
        val entries = getAll(context)
        if (entries.none { it.stream.id == streamId }) return
        write(context, entries.filter { it.stream.id != streamId })
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    /** "1:05:30" o "45:10". */
    fun formatClock(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val sec = totalSeconds % 60
        return if (h > 0) String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", h, m, sec)
        else String.format(java.util.Locale.getDefault(), "%d:%02d", m, sec)
    }

    private fun write(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            val obj = JSONObject()
            obj.put("stream", JSONObject(e.stream.toJson()))
            obj.put("pos", e.positionMs)
            obj.put("dur", e.durationMs)
            obj.put("at", e.updatedAt)
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }
}
