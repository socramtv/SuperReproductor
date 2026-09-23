package com.example.superplayer.model

/**
 * Esquema del JSON que lee esta app. Guarda tu lista con esta forma
 * (puedes tener tantas categorías y canales como quieras):
 *
 * {
 *   "categories": [
 *     {
 *       "name": "Mis canales",
 *       "streams": [
 *         {
 *           "name": "Canal demo DASH",
 *           "type": "DASH",                 // "DASH" | "HLS" | cualquier otro valor -> progresivo (mp4, etc.)
 *           "url": "https://.../manifest.mpd",
 *           "icon": "https://.../logo.png",  // opcional
 *           "headers": {                     // opcional: cabeceras propias (p. ej. token de tu propio servidor)
 *             "Authorization": "Bearer xxx"
 *           },
 *           "drm": {                         // opcional: solo si TÚ tienes derecho a esa clave ClearKey
 *             "keyId": "base64url...",
 *             "key": "base64url..."
 *           }
 *         }
 *       ]
 *     }
 *   ]
 * }
 */

data class PlaylistData(
    val categories: List<Category>
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
    val drm: DrmInfo? = null
) {
    /** Identificador estable para favoritos: dos canales con la misma URL son "el mismo". */
    val id: String get() = url
}

data class DrmInfo(
    val keyId: String,
    val key: String
)
