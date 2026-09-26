package com.example.superplayer.model

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
