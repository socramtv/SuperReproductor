package com.example.superplayer.data

import android.content.Context
import com.example.superplayer.model.Stream
import com.example.superplayer.model.streamFromJson
import com.example.superplayer.model.toJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Copia de seguridad en un archivo JSON (ver README, "Copia de seguridad"):
 * favoritos (con los datos completos de cada canal), las URLs de los 5
 * huecos de lista, el filtro y el orden de categorías de cada lista y
 * el modo claro/oscuro. No incluye las listas descargadas ni la guía: se vuelven a
 * bajar solas de sus URLs.
 *
 * Al importar, los favoritos se SUMAN a los que ya haya (nunca se borra
 * ninguno); las URLs, los filtros y el modo claro/oscuro se sustituyen por
 * los de la copia.
 */
object BackupManager {

    private const val FORMAT_VERSION = 1
    private const val APP_ID = "socram-tv-backup"

    data class ImportResult(val newFavorites: Int, val listUrls: Int, val filters: Int, val darkModeChanged: Boolean)

    fun export(context: Context): String {
        val root = JSONObject()
        root.put("app", APP_ID)
        root.put("version", FORMAT_VERSION)

        val favs = JSONArray()
        for (stream in FavoritesStore(context).getAllStreams()) {
            favs.put(JSONObject(stream.toJson()))
        }
        root.put("favorites", favs)

        val urls = JSONObject()
        for (slot in 1..5) {
            AppPrefs.getListUrl(context, slot)?.takeIf { it.isNotBlank() }?.let { urls.put(slot.toString(), it) }
        }
        root.put("listUrls", urls)

        val hidden = JSONObject()
        for ((key, names) in AppPrefs.getAllHiddenCategories(context)) {
            if (names.isNotEmpty()) hidden.put(key, JSONArray(names.toList()))
        }
        root.put("hiddenCategories", hidden)

        val orders = JSONObject()
        for ((key, names) in AppPrefs.getAllCategoryOrders(context)) {
            orders.put(key, JSONArray(names))
        }
        root.put("categoryOrder", orders)

        root.put("darkMode", AppPrefs.isDarkMode(context))
        return root.toString(2)
    }

    /** Lanza IllegalArgumentException si el texto no es una copia de esta app. */
    fun restore(context: Context, text: String): ImportResult {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("No es un archivo de copia válido")
        }
        if (root.optString("app") != APP_ID) throw IllegalArgumentException("Este archivo no es una copia de Socram TV+")

        val streams = ArrayList<Stream>()
        val favs = root.optJSONArray("favorites")
        if (favs != null) {
            for (i in 0 until favs.length()) {
                streamFromJson(favs.getJSONObject(i).toString())?.let { streams.add(it) }
            }
        }
        val newFavorites = FavoritesStore(context).addAll(streams)

        var urlCount = 0
        val urls = root.optJSONObject("listUrls")
        if (urls != null) {
            for (slot in 1..5) {
                val url = urls.optString(slot.toString())
                if (url.isNotBlank()) {
                    AppPrefs.saveListUrl(context, slot, url)
                    urlCount++
                }
            }
        }

        var filterCount = 0
        val hidden = root.optJSONObject("hiddenCategories")
        if (hidden != null) {
            val keys = hidden.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = hidden.optJSONArray(key) ?: continue
                val names = HashSet<String>()
                for (i in 0 until arr.length()) names.add(arr.getString(i))
                AppPrefs.setHiddenCategories(context, key, names)
                filterCount++
            }
        }

        val orders = root.optJSONObject("categoryOrder")
        if (orders != null) {
            val keys = orders.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = orders.optJSONArray(key) ?: continue
                AppPrefs.setCategoryOrder(context, key, List(arr.length()) { arr.getString(it) })
            }
        }

        var darkChanged = false
        if (root.has("darkMode")) {
            val dark = root.optBoolean("darkMode", true)
            darkChanged = dark != AppPrefs.isDarkMode(context)
            if (darkChanged) AppPrefs.setDarkMode(context, dark)
        }
        return ImportResult(newFavorites, urlCount, filterCount, darkChanged)
    }
}
