package com.example.superplayer.player

import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.example.superplayer.R
import com.example.superplayer.databinding.ActivityPlayerBinding
import com.example.superplayer.model.Stream

/**
 * Pantalla de reproducción. Construye la fuente correcta según stream.type
 * (DASH / HLS / progresivo), aplicando cabeceras HTTP propias y, si el JSON
 * las trae, claves ClearKey para streams cifrados a los que el usuario
 * tiene derecho de acceso.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val stream = pendingStream
        if (stream == null) {
            Toast.makeText(this, getString(R.string.player_error, "canal no encontrado"), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        title = stream.name
        preparePlayer(stream)
    }

    private fun preparePlayer(stream: Stream) {
        val dataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(stream.headers)
            .setAllowCrossProtocolRedirects(true)

        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        binding.playerView.player = exoPlayer
        binding.playerView.keepScreenOn = true

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.player_error, error.message ?: error.errorCodeName),
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        try {
            val mediaSource = buildMediaSource(stream, dataSourceFactory)
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.playWhenReady = true
            exoPlayer.prepare()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.player_error, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun buildMediaSource(stream: Stream, dataSourceFactory: DataSource.Factory): MediaSource {
        val mimeType = when (stream.type.trim().uppercase()) {
            "DASH", "MPD" -> MimeTypes.APPLICATION_MPD
            "HLS", "M3U8" -> MimeTypes.APPLICATION_M3U8
            else -> null
        }

        val mediaItem = MediaItem.Builder()
            .setUri(stream.url)
            .apply { mimeType?.let { setMimeType(it) } }
            .build()

        val factory: MediaSource.Factory = when (mimeType) {
            MimeTypes.APPLICATION_MPD -> DashMediaSource.Factory(dataSourceFactory)
            MimeTypes.APPLICATION_M3U8 -> HlsMediaSource.Factory(dataSourceFactory)
            else -> ProgressiveMediaSource.Factory(dataSourceFactory)
        }

        stream.drm?.let { drm ->
            factory.setDrmSessionManagerProvider {
                buildClearKeyDrmSessionManager(drm.keyId, drm.key)
            }
        }

        return factory.createMediaSource(mediaItem)
    }

    /**
     * ClearKey "local": la clave ya viene en el JSON (stream.drm), así que
     * no hace falta golpear un servidor de licencias, se construye la
     * respuesta ClearKey en el propio dispositivo.
     */
    private fun buildClearKeyDrmSessionManager(keyId: String, key: String): DrmSessionManager {
        val json = """{"keys":[{"kty":"oct","k":"$key","kid":"$keyId"}],"type":"temporary"}"""
        val callback = LocalMediaDrmCallback(json.toByteArray(Charsets.UTF_8))
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(callback)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        pendingStream = null
    }

    companion object {
        var pendingStream: Stream? = null
    }
}
