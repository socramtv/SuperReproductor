package com.example.superplayer.model

import org.json.JSONObject

/**
 * Esquema del JSON que lee esta app. Se acepta la raíz de dos formas:
 *
 * 1) Objeto con "categories":
 *    { "categories": [ { "name": "...", "streams": [ ... ] } ] }
 *
 * 2) Array de categorías directamente en la raíz:
 *    [ { "name": "...", "samples": [ ... ] } ]
 *
 * ("streams" y "samples" son alias del mismo campo.)
 *
 * Cada canal admite estos campos (alias entre paréntesis), todos menos
 * name/url son opcionales:
 * - name
 * - url (o "uri")
 * - type: "DASH"|"HLS"|otro -> progresivo   (o "extension": "mpd"|"m3u8")
 * - icon (o "image"/"icono")
 * - headers: { ... }   cabeceras HTTP propias (auth, referer, etc.)
 * - token: URL que devuelve un token en texto plano. Si "url" contiene el
 *   texto "{token}", se sustituye por lo que devuelva esa URL justo antes
 *   de reproducir.
 * - DRM ClearKey, en cualquiera de estas tres formas:
 *     "drm": { "keyId": "...", "key": "..." }
 *     "kid": "...", "key": "..."       (sueltos, al mismo nivel que "url")
 *     "license_key": "{\"keys\":[{\"kty\":\"oct\",...}],\"type\":\"temporary\"}"
 *   "kid"/"key" aceptan base64url o hexadecimal (se normalizan solos).
 *   Un "drm_scheme" distinto de "clearkey" (p. ej. "widevine") se ignora:
 *   esos esquemas necesitan servidor de licencias propio y no están
 *   implementados aquí.
 *
 * Radio / audio en segundo plano: no hace falta declarar nada especial en
 * el JSON. PlayerActivity detecta solo, a partir de las pistas reales del
 * stream, si un canal no tiene vídeo (radio) y en ese caso mantiene la
 * reproducción activa en segundo plano y en la pantalla de bloqueo a
 * través de PlaybackService.
 *
 * EPG (guía de programación), opcional:
 * - A nivel de lista: "epgUrl" (o "epg_url"/"url-tvg"/"xmltv") en la raíz
 *   del JSON, o el atributo url-tvg/x-tvg-url de la cabecera #EXTM3U en
 *   M3U. Debe apuntar a un XMLTV (.xml o .xml.gz).
 * - A nivel de canal: "tvgId" (o "tvg_id"/"tvg-id"/"epgId") en JSON, o el
 *   atributo tvg-id de cada #EXTINF en M3U. Tiene que coincidir con el
 *   "channel" de ese XMLTV.
 * Con ambos datos, la app muestra el programa que toca ahora (en la lista
 * de canales, y en la pantalla de radio como respaldo si el propio stream
 * no manda su propio título ICY/ID3).
 */

data class PlaylistData(
    val categories: List<Category>,
    val epgUrl: String? = null
)

data class Category(
    val name: String,
    val streams: List<Stream>
)

data class Stream(
    val name: String,
    val type: String,
    val url: String,
    val icon: String?,
    val category: String,
    val headers: Map<String, String> = emptyMap(),
    val drm: DrmInfo? = null,
    val tokenUrl: String? = null,
    val tvgId: String? = null
) {
    /** Identificador estable para favoritos: dos canales con la misma URL son "el mismo". */
    val id: String get() = url
}

data class DrmInfo(
    val keyId: String? = null,
    val key: String? = null,
    val rawLicenseJson: String? = null
)

/**
 * Serializa un Stream suelto a JSON (formato propio de esta app, nada que
 * ver con el JSON de listas que lee PlaylistRepository): para guardar un
 * favorito completo (ver FavoritesStore) y para que viaje dentro del
 * Intent de un acceso directo del icono de la app (ver
 * player/ShortcutsHelper.kt). Un acceso directo puede abrirse con el
 * proceso recién arrancado, sin ninguna lista cargada todavía de la que
 * sacar ese canal por id, así que tiene que traer todos sus datos él
 * mismo -incluidas las cabeceras HTTP o el DRM propios del canal, si los
 * tiene, para que reproduzca igual que abierto desde dentro de la app.
 */
fun Stream.toJson(): String {
    val obj = JSONObject()
    obj.put("name", name)
    obj.put("type", type)
    obj.put("url", url)
    if (icon != null) obj.put("icon", icon)
    obj.put("category", category)
    if (headers.isNotEmpty()) {
        val headersObj = JSONObject()
        for ((key, value) in headers) headersObj.put(key, value)
        obj.put("headers", headersObj)
    }
    val drmValue = drm
    if (drmValue != null) {
        val drmObj = JSONObject()
        if (drmValue.keyId != null) drmObj.put("keyId", drmValue.keyId)
        if (drmValue.key != null) drmObj.put("key", drmValue.key)
        if (drmValue.rawLicenseJson != null) drmObj.put("rawLicenseJson", drmValue.rawLicenseJson)
        obj.put("drm", drmObj)
    }
    if (tokenUrl != null) obj.put("tokenUrl", tokenUrl)
    if (tvgId != null) obj.put("tvgId", tvgId)
    return obj.toString()
}

/** El Stream que guardó [Stream.toJson], o null si [json] no es válido (nunca debería pasar con lo que guarda esta misma app, pero por si acaso). */
fun streamFromJson(json: String): Stream? {
    return try {
        val obj = JSONObject(json)
        val headers = mutableMapOf<String, String>()
        obj.optJSONObject("headers")?.let { headersObj ->
            val keys = headersObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                headers[key] = headersObj.optString(key)
            }
        }
        val drmObj = obj.optJSONObject("drm")
        val drm = if (drmObj != null) {
            DrmInfo(
                keyId = drmObj.optStringOrNull("keyId"),
                key = drmObj.optStringOrNull("key"),
                rawLicenseJson = drmObj.optStringOrNull("rawLicenseJson")
            )
        } else null
        Stream(
            name = obj.getString("name"),
            type = obj.optString("type", ""),
            url = obj.getString("url"),
            icon = obj.optStringOrNull("icon"),
            category = obj.optString("category", ""),
            headers = headers,
            drm = drm,
            tokenUrl = obj.optStringOrNull("tokenUrl"),
            tvgId = obj.optStringOrNull("tvgId")
        )
    } catch (e: Exception) {
        null
    }
}

/** Como JSONObject.optString, pero un valor JSON null explícito (o ausente) da null en vez del texto "null"/"". */
private fun JSONObject.optStringOrNull(key: String): String? {
    if (isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() }
}
