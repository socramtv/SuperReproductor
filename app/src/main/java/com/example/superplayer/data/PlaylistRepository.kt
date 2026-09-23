package com.example.superplayer.data

import android.content.Context
import android.net.Uri
import com.example.superplayer.model.Category
import com.example.superplayer.model.DrmInfo
import com.example.superplayer.model.PlaylistData
import com.example.superplayer.model.Stream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lee y valida el JSON de la playlist, ya sea desde un Uri elegido por el
 * usuario (Storage Access Framework) o desde los assets de la app (lista
 * de ejemplo usada la primera vez que se abre la app).
 */
object PlaylistRepository {

    fun loadFromUri(context: Context, uri: Uri): PlaylistData {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().readText()
        } ?: throw IllegalStateException("No se pudo abrir el archivo seleccionado")
        return parse(text)
    }

    fun loadFromAssets(context: Context, fileName: String): PlaylistData {
        val text = context.assets.open(fileName).use { input ->
            input.bufferedReader().readText()
        }
        return parse(text)
    }

    fun parse(jsonText: String): PlaylistData {
        val root = JSONObject(jsonText)
        val categoriesJson = root.optJSONArray("categories") ?: JSONArray()
        val categories = ArrayList<Category>(categoriesJson.length())

        for (i in 0 until categoriesJson.length()) {
            val catObj = categoriesJson.optJSONObject(i) ?: continue
            val categoryName = catObj.optString("name").ifBlank { "Sin categoría" }
            val streamsJson = catObj.optJSONArray("streams") ?: JSONArray()
            val streams = ArrayList<Stream>(streamsJson.length())

            for (j in 0 until streamsJson.length()) {
                val sObj = streamsJson.optJSONObject(j) ?: continue
                val url = sObj.optString("url")
                if (url.isBlank()) continue // un canal sin URL no sirve de nada

                streams.add(
                    Stream(
                        name = sObj.optString("name").ifBlank { "Sin nombre" },
                        type = sObj.optString("type").ifBlank { "HLS" },
                        url = url,
                        icon = sObj.optString("icon").takeIf { it.isNotBlank() },
                        category = categoryName,
                        headers = sObj.optJSONObject("headers")?.toStringMap() ?: emptyMap(),
                        drm = sObj.optJSONObject("drm")?.let { d ->
                            val keyId = d.optString("keyId")
                            val key = d.optString("key")
                            if (keyId.isNotBlank() && key.isNotBlank()) DrmInfo(keyId, key) else null
                        }
                    )
                )
            }
            categories.add(Category(categoryName, streams))
        }
        return PlaylistData(categories)
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val keysIterator = keys()
        while (keysIterator.hasNext()) {
            val k = keysIterator.next()
            map[k] = optString(k)
        }
        return map
    }
}
