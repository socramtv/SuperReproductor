package com.example.superplayer.player

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.example.superplayer.model.DrmInfo

/**
 * Construye el MediaSource correcto (DASH / HLS / progresivo), con las
 * cabeceras HTTP propias del canal y, si trae claves ClearKey, el DRM
 * session manager para descifrarlo. Es exactamente la misma lógica que
 * tenía PlayerActivity.buildMediaSource() cuando el ExoPlayer vivía en la
 * Activity; ahora vive aquí porque el ExoPlayer real vive en
 * PlaybackService (para poder seguir sonando en segundo plano / pantalla
 * de bloqueo), y este factory es lo que el servicio usa para traducir cada
 * MediaItem que le llega a través del MediaController.
 */
@OptIn(UnstableApi::class)
class StreamMediaSourceFactory(@Suppress("UNUSED_PARAMETER") context: Context) : MediaSource.Factory {

    private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null

    override fun setDrmSessionManagerProvider(provider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider): MediaSource.Factory {
        // Se ignora a propósito: el DRM de cada canal depende del propio
        // MediaItem (sus extras), así que se decide por-item dentro de
        // createMediaSource(), no de forma global para todo el factory.
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        this.loadErrorHandlingPolicy = policy
        return this
    }

    override fun getSupportedTypes(): IntArray =
        intArrayOf(C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val extras = mediaItem.mediaMetadata.extras
        val headers = StreamMediaExtras.headers(extras)
        val type = StreamMediaExtras.type(extras).orEmpty()

        val dataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)

        val mimeType = when (type.trim().uppercase()) {
            "DASH", "MPD" -> MimeTypes.APPLICATION_MPD
            "HLS", "M3U8" -> MimeTypes.APPLICATION_M3U8
            else -> null
        }

        val factory: MediaSource.Factory = when (mimeType) {
            MimeTypes.APPLICATION_MPD -> DashMediaSource.Factory(dataSourceFactory)
            MimeTypes.APPLICATION_M3U8 -> HlsMediaSource.Factory(dataSourceFactory)
            else -> ProgressiveMediaSource.Factory(dataSourceFactory)
        }

        if (StreamMediaExtras.hasDrm(extras)) {
            factory.setDrmSessionManagerProvider { buildClearKeyDrmSessionManager(extras) }
        }
        loadErrorHandlingPolicy?.let { factory.setLoadErrorHandlingPolicy(it) }

        return factory.createMediaSource(mediaItem)
    }

    private fun buildClearKeyDrmSessionManager(extras: Bundle?): DrmSessionManager {
        val drm = DrmInfo(
            keyId = StreamMediaExtras.drmKeyId(extras),
            key = StreamMediaExtras.drmKey(extras),
            rawLicenseJson = StreamMediaExtras.drmLicenseJson(extras)
        )
        val json = ClearKeyUtil.buildLicenseJson(drm)
        val callback = LocalMediaDrmCallback(json.toByteArray(Charsets.UTF_8))
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(callback)
    }
}
