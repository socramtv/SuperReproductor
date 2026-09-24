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
 * Lee y valida la playlist, ya sea desde un Uri elegido por el usuario
 * (Storage Access Framework) o desde los assets de la app (lista de
 * ejemplo usada la primera vez que se abre la app). Admite JSON (ver el
 * comentario en model/Playlist.kt para el detalle de campos/alias) y
 * listas M3U/M3U8 extendidas (líneas #EXTM3U / #EXTINF).
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

    fun parse(text: String): PlaylistData {
        val trimmed = text.trim()
        return if (trimmed.startsWith("#EXTM3U", ignoreCase = true)) {
            parseM3u(trimmed)
        } else {
            parseJson(trimmed)
        }
    }

    // ---------- JSON ----------

    private fun parseJson(trimmed: String): PlaylistData {
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

    // ---------- M3U / M3U8 extendido ----------

    private fun parseM3u(text: String): PlaylistData {
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String = "Sin categoría"
        val byCategory = LinkedHashMap<String, MutableList<Stream>>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#EXTM3U", ignoreCase = true)) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val commaIndex = line.indexOf(',')
                val attrsPart = if (commaIndex >= 0) line.substring(0, commaIndex) else line
                val titlePart = if (commaIndex >= 0) line.substring(commaIndex + 1).trim() else ""

                pendingLogo = extractAttr(attrsPart, "tvg-logo")
                pendingGroup = extractAttr(attrsPart, "group-title")?.takeIf { it.isNotBlank() }
                    ?: "Sin categoría"
                pendingName = (extractAttr(attrsPart, "tvg-name")?.takeIf { it.isNotBlank() }
                    ?: titlePart).ifBlank { "Sin nombre" }
            } else if (!line.startsWith("#")) {
                // Cualquier línea que no sea una etiqueta "#..." es la URL del canal.
                val type = when {
                    line.contains(".m3u8", ignoreCase = true) -> "HLS"
                    line.contains(".mpd", ignoreCase = true) -> "DASH"
                    else -> "PROGRESSIVE"
                }
                val stream = Stream(
                    name = pendingName ?: "Sin nombre",
                    type = type,
                    url = line,
                    icon = pendingLogo,
                    category = pendingGroup
                )
                byCategory.getOrPut(pendingGroup) { mutableListOf() }.add(stream)
                pendingName = null
                pendingLogo = null
            }
            // Otras etiquetas (#EXTGRP, #EXTVLCOPT, #EXT-X-..., comentarios) se ignoran.
        }

        val categories = byCategory.map { (name, streams) -> Category(name, streams) }
        return PlaylistData(categories)
    }

    private fun extractAttr(source: String, key: String): String? =
        Regex("$key=\"([^\"]*)\"").find(source)?.groupValues?.get(1)
}
