package com.example.superplayer.player

import android.util.Base64
import com.example.superplayer.model.DrmInfo

/**
 * ClearKey "local": si el JSON trae license_key, se usa tal cual (ya es la
 * respuesta ClearKey completa). Si trae kid/key sueltos, se arma el JSON y
 * se normalizan a base64url si vienen en hexadecimal o en base64 "normal".
 *
 * Misma lógica que usaba PlayerActivity cuando construía el DRM session
 * manager directamente; ahora vive aquí porque StreamMediaSourceFactory
 * (dentro de PlaybackService) es quien construye el MediaSource real.
 */
object ClearKeyUtil {

    fun buildLicenseJson(drm: DrmInfo): String {
        drm.rawLicenseJson?.takeIf { it.isNotBlank() }?.let { return it }
        val keyId = normalizeClearKeyValue(drm.keyId.orEmpty())
        val key = normalizeClearKeyValue(drm.key.orEmpty())
        return """{"keys":[{"kty":"oct","k":"$key","kid":"$keyId"}],"type":"temporary"}"""
    }

    /**
     * kid/key pueden llegar en tres formatos según de dónde se copien:
     * hexadecimal, base64 "normal" (con +, / o = de relleno) o ya en
     * base64url (lo que ClearKey necesita). Los tres se normalizan aquí.
     */
    fun normalizeClearKeyValue(value: String): String {
        val clean = value.trim()
        if (clean.isEmpty()) return clean

        val looksHex = clean.length % 2 == 0 && clean.all { it in "0123456789abcdefABCDEF" }
        if (looksHex) {
            return try {
                val bytes = ByteArray(clean.length / 2) { i ->
                    clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
            } catch (e: Exception) {
                clean
            }
        }

        if (clean.contains('+') || clean.contains('/') || clean.contains('=')) {
            return try {
                val bytes = Base64.decode(clean, Base64.DEFAULT)
                Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
            } catch (e: Exception) {
                clean
            }
        }

        return clean
    }
}
