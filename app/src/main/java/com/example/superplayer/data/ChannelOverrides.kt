package com.example.superplayer.data

import android.content.Context
import com.example.superplayer.model.Category
import com.example.superplayer.model.PlaylistData
import com.example.superplayer.model.Stream
import org.json.JSONObject

/**
 * Ajustes propios sobre los canales de las listas, sin tocar la lista
 * original: canales OCULTOS y canales con otro NOMBRE. Se identifican por
 * Stream.id (la URL), así que valen en cualquier lista donde salga ese canal
 * y sobreviven a las actualizaciones de la lista remota.
 *
 * [apply] devuelve la lista ya con los ajustes puestos (la que usa toda la
 * app: categorías, favoritos, guía, mini-guía...). [version] sube con cada
 * cambio para que las pantallas ya abiertas sepan que deben repintarse.
 */
object ChannelOverrides {
    private const val PREFS_NAME = "channel_overrides"
    private const val KEY_HIDDEN = "hidden"   // JSON { id: nombre con el que se ocultó }
    private const val KEY_NAMES = "names"     // JSON { id: nombre propio }

    @Volatile
    var version: Int = 0
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readMap(context: Context, key: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val raw = prefs(context).getString(key, null) ?: return result
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                result[k] = obj.optString(k)
            }
        } catch (e: Exception) {
            // Datos dañados: se ignoran.
        }
        return result
    }

    private fun writeMap(context: Context, key: String, map: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        val editor = prefs(context).edit()
        if (map.isEmpty()) editor.remove(key) else editor.putString(key, obj.toString())
        editor.apply()
        version++
    }

    fun isHidden(context: Context, streamId: String): Boolean = readMap(context, KEY_HIDDEN).containsKey(streamId)

    /** Canales ocultos: id -> nombre (para poder listarlos y mostrarlos otra vez). */
    fun getHidden(context: Context): Map<String, String> = readMap(context, KEY_HIDDEN)

    fun hide(context: Context, streams: List<Stream>) {
        if (streams.isEmpty()) return
        val map = readMap(context, KEY_HIDDEN)
        for (s in streams) map[s.id] = s.name
        writeMap(context, KEY_HIDDEN, map)
    }

    fun unhide(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val map = readMap(context, KEY_HIDDEN)
        for (id in ids) map.remove(id)
        writeMap(context, KEY_HIDDEN, map)
    }

    /** Nombres propios: id -> nombre. */
    fun getNames(context: Context): Map<String, String> = readMap(context, KEY_NAMES)

    /** Copia de seguridad: suma estos ocultos y nombres a los que ya hay (los nombres de la copia pisan). Devuelve (ocultos nuevos, nombres nuevos). */
    fun merge(context: Context, hidden: Map<String, String>, names: Map<String, String>): Pair<Int, Int> {
        val h = readMap(context, KEY_HIDDEN)
        val n = readMap(context, KEY_NAMES)
        val newHidden = hidden.keys.count { it !in h }
        val newNames = names.keys.count { it !in n }
        h.putAll(hidden)
        n.putAll(names)
        if (hidden.isNotEmpty()) writeMap(context, KEY_HIDDEN, h)
        if (names.isNotEmpty()) writeMap(context, KEY_NAMES, n)
        return newHidden to newNames
    }

    fun hasCustomName(context: Context, streamId: String): Boolean = readMap(context, KEY_NAMES).containsKey(streamId)

    /** Pone un nombre propio; un nombre vacío quita el cambio y vuelve al original. */
    fun rename(context: Context, streamId: String, newName: String) {
        val map = readMap(context, KEY_NAMES)
        if (newName.isBlank()) map.remove(streamId) else map[streamId] = newName.trim()
        writeMap(context, KEY_NAMES, map)
    }

    /** La lista con los nombres propios puestos y los canales ocultos fuera (las categorías que se quedan vacías, también). */
    fun apply(context: Context, data: PlaylistData): PlaylistData {
        val hidden = readMap(context, KEY_HIDDEN)
        val names = readMap(context, KEY_NAMES)
        if (hidden.isEmpty() && names.isEmpty()) return data
        val categories = data.categories.mapNotNull { category ->
            val streams = category.streams
                .filter { !hidden.containsKey(it.id) }
                .map { stream ->
                    val custom = names[stream.id]
                    if (custom != null) stream.copy(name = custom) else stream
                }
            // Una categoría que solo tenía canales ocultos desaparece; una que ya venía vacía se respeta.
            if (streams.isEmpty() && category.streams.isNotEmpty()) null else Category(category.name, streams)
        }
        return data.copy(categories = categories)
    }

    /** Pone el nombre propio (si lo hay) a un canal suelto, p. ej. los de "Continuar viendo". */
    fun renamed(context: Context, stream: Stream): Stream {
        val custom = readMap(context, KEY_NAMES)[stream.id] ?: return stream
        return stream.copy(name = custom)
    }
}
