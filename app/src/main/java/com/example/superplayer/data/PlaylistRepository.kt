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
 *
 * Acepta dos formatos en la raíz y varios alias de campos; ver el
 * comentario en model/Playlist.kt para el detalle completo.
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
        val trimmed = jsonText.trim()
        val categoriesJson: JSONArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).optJSONArray("categories") ?: JSONArray()
        }

        val categories = ArrayList<Category>(categoriesJson.length())

        for (i in 0 until categoriesJson.length()) {
            val catObj = categoriesJson.optJSONObject(i) ?: continue
            val categoryName = catObj.optString("name").ifBlank { "Sin categoría" }
            val streamsJson = catObj.optJSONArray("streams")
                ?: catObj.optJSONArray("samples")
                ?: JSONArray()

            val streams = ArrayList<Stream>(streamsJson.length())

            for (j in 0 until streamsJson.length()) {
                val sObj = streamsJson.optJSONObject(j) ?: continue
                val url = sObj.optString("url").ifBlank { sObj.optString("uri") }
                if (url.isBlank()) continue // un canal sin URL no sirve de nada

                val typeRaw = sObj.optString("type").ifBlank { sObj.optString("extension") }
                val icon = sObj.optString("icon").ifBlank { sObj.optString("image") }
                    .ifBlank { sObj.optString("icono") }.takeIf { it.isNotBlank() }
                val tokenUrl = sObj.optString("token").takeIf { it.isNotBlank() }

                streams.add(
                    Stream(
                        name = sObj.optString("name").ifBlank { "Sin nombre" },
                        type = typeRaw.ifBlank { "HLS" },
                        url = url,
                        icon = icon,
                        category = categoryName,
                        headers = sObj.optJSONObject("headers")?.toStringMap() ?: emptyMap(),
                        drm = buildDrmInfo(sObj),
                        tokenUrl = tokenUrl
                    )
                )
            }
            categories.add(Category(categoryName, streams))
        }
        return PlaylistData(categories)
    }

    private fun buildDrmInfo(sObj: JSONObject): DrmInfo? {
        val drmScheme = sObj.optString("drm_scheme")
        // Un esquema distinto de clearkey (widevine/playready...) necesita su propio
        // servidor de licencias; no está implementado, así que se ignora sin romper nada.
        if (drmScheme.isNotBlank() && !drmScheme.equals("clearkey", ignoreCase = true)) return null

        val licenseJson = sObj.optString("license_key").takeIf { it.isNotBlank() }

        val drmObj = sObj.optJSONObject("drm")
        val keyId = drmObj?.optString("keyId")?.takeIf { it.isNotBlank() }
            ?: sObj.optString("kid").takeIf { it.isNotBlank() }
        val key = drmObj?.optString("key")?.takeIf { it.isNotBlank() }
            ?: sObj.optString("key").takeIf { it.isNotBlank() }

        if (licenseJson == null && (keyId == null || key == null)) return null
        return DrmInfo(keyId = keyId, key = key, rawLicenseJson = licenseJson)
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
