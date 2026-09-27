package com.example.superplayer.player

import android.os.Bundle
import com.example.superplayer.model.Stream

/**
 * Empaqueta lo que [StreamMediaSourceFactory] necesita para construir el
 * MediaSource correcto (tipo DASH/HLS/progresivo, cabeceras HTTP propias,
 * DRM ClearKey) dentro del Bundle de [androidx.media3.common.MediaMetadata]
 * de un MediaItem, para que sobreviva el viaje desde PlayerActivity (que
 * resuelve el token/URL final) hasta PlaybackService (dueño del ExoPlayer
 * real, que es quien construye los MediaSource).
 */
object StreamMediaExtras {
    private const val KEY_TYPE = "spr_type"
    private const val KEY_HEADERS = "spr_headers"
    private const val KEY_DRM_KEY_ID = "spr_drm_key_id"
    private const val KEY_DRM_KEY = "spr_drm_key"
    private const val KEY_DRM_LICENSE_JSON = "spr_drm_license_json"
    private const val KEY_STATION_NAME = "spr_station_name"

    fun build(stream: Stream): Bundle {
        val bundle = Bundle()
        bundle.putString(KEY_TYPE, stream.type)
        if (stream.headers.isNotEmpty()) {
            val headersBundle = Bundle()
            for ((k, v) in stream.headers) headersBundle.putString(k, v)
            bundle.putBundle(KEY_HEADERS, headersBundle)
        }
        stream.drm?.let { drm ->
            drm.keyId?.let { bundle.putString(KEY_DRM_KEY_ID, it) }
            drm.key?.let { bundle.putString(KEY_DRM_KEY, it) }
            drm.rawLicenseJson?.let { bundle.putString(KEY_DRM_LICENSE_JSON, it) }
        }
        bundle.putString(KEY_STATION_NAME, stream.name)
        return bundle
    }

    fun type(bundle: Bundle?): String? = bundle?.getString(KEY_TYPE)

    fun headers(bundle: Bundle?): Map<String, String> {
        val headersBundle = bundle?.getBundle(KEY_HEADERS) ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        for (key in headersBundle.keySet()) {
            headersBundle.getString(key)?.let { map[key] = it }
        }
        return map
    }

    fun hasDrm(bundle: Bundle?): Boolean {
        if (bundle == null) return false
        return bundle.containsKey(KEY_DRM_LICENSE_JSON) ||
            (bundle.containsKey(KEY_DRM_KEY_ID) && bundle.containsKey(KEY_DRM_KEY))
    }

    fun drmKeyId(bundle: Bundle?): String? = bundle?.getString(KEY_DRM_KEY_ID)
    fun drmKey(bundle: Bundle?): String? = bundle?.getString(KEY_DRM_KEY)
    fun drmLicenseJson(bundle: Bundle?): String? = bundle?.getString(KEY_DRM_LICENSE_JSON)
    fun stationName(bundle: Bundle?): String? = bundle?.getString(KEY_STATION_NAME)
}
