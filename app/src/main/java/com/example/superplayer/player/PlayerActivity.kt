package com.example.superplayer.player

import android.os.Bundle
import android.util.Base64
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
import com.example.superplayer.model.DrmInfo
import com.example.superplayer.model.Stream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pantalla de reproducción. Construye la fuente correcta según stream.type
 * (DASH / HLS / progresivo), aplicando cabeceras HTTP propias y, si el JSON
 * las trae, claves ClearKey para streams cifrados a los que el usuario
 * tiene derecho de acceso. Si el canal trae tokenUrl, primero se pide un
 * token (en un hilo aparte) y se sustituye "{token}" en la URL antes de
 * reproducir.
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
        resolveAndPlay(stream)
    }

    private fun resolveAndPlay(stream: Stream) {
        val tokenUrl = stream.tokenUrl
        if (tokenUrl.isNullOrBlank()) {
            startPlayback(stream)
            return
        }
        Thread {
            val token = try {
                fetchToken(tokenUrl, stream.headers)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (token.isNullOrBlank()) {
                    Toast.makeText(
                        this,
                        getString(R.string.player_error, "no se pudo obtener el token de $tokenUrl"),
                        Toast.LENGTH_LONG
                    ).show()
                    startPlayback(stream)
                } else {
                    startPlayback(stream.copy(url = stream.url.replace("{token}", token)))
                }
            }
        }.start()
    }

    private fun fetchToken(tokenUrl: String, headers: Map<String, String>): String {
        val connection = URL(tokenUrl).openConnection() as HttpURLConnection
        return try {
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.inputStream.bufferedReader().readText().trim()
        } finally {
            connection.disconnect()
        }
    }

    private fun startPlayback(stream: Stream) {
        if (isFinishing || isDestroyed) return

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
                buildClearKeyDrmSessionManager(drm)
            }
        }

        return factory.createMediaSource(mediaItem)
    }

    /**
     * ClearKey "local": si el JSON trae license_key, se usa tal cual (ya es
     * la respuesta ClearKey completa). Si trae kid/key sueltos, se arma el
     * JSON y se normalizan a base64url si vienen en hexadecimal.
     */
    private fun buildClearKeyDrmSessionManager(drm: DrmInfo): DrmSessionManager {
        val json = drm.rawLicenseJson?.takeIf { it.isNotBlank() } ?: run {
            val keyId = normalizeClearKeyValue(drm.keyId.orEmpty())
            val key = normalizeClearKeyValue(drm.key.orEmpty())
            """{"keys":[{"kty":"oct","k":"$key","kid":"$keyId"}],"type":"temporary"}"""
        }
        val callback = LocalMediaDrmCallback(json.toByteArray(Charsets.UTF_8))
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(callback)
    }

    /** Si el valor es hexadecimal lo convierte a base64url (sin padding); si no, se deja igual. */
    /**
     * kid/key pueden llegar en tres formatos según de dónde se copien:
     * hexadecimal, base64 "normal" (con +, / o = de relleno) o ya en
     * base64url (lo que ClearKey necesita). Los tres se normalizan aquí.
     */
    private fun normalizeClearKeyValue(value: String): String {
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
