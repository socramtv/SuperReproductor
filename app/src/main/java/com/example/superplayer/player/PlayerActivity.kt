package com.example.superplayer.player

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import coil.load
import com.example.superplayer.R
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.databinding.ActivityPlayerBinding
import com.example.superplayer.model.Stream
import com.google.common.util.concurrent.ListenableFuture
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pantalla de reproducción. Ya no crea el ExoPlayer aquí: se conecta como
 * MediaController a la sesión que publica PlaybackService (así la
 * reproducción puede seguir sonando en segundo plano / pantalla de
 * bloqueo). Sigue resolviendo aquí lo que ya resolvía antes de construir
 * el reproductor: si el canal trae tokenUrl, primero se pide un token (en
 * un hilo aparte, mandando las cabeceras propias del canal) y se sustituye
 * "{token}" en la URL; el tipo (DASH/HLS/progresivo), las cabeceras HTTP y
 * el DRM ClearKey viajan dentro del MediaItem (ver StreamMediaExtras) para
 * que PlaybackService pueda construir el MediaSource real.
 *
 * Si el canal no tiene pista de vídeo (radio), se detecta solo a partir de
 * las pistas reales del stream y se muestra el logo + "ahora suena" en vez
 * del hueco negro del vídeo; ese es también el único caso en que dejamos
 * la reproducción seguir cuando la pantalla se bloquea o se cambia de app.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    private var currentStream: Stream? = null
    private var pendingMediaItem: MediaItem? = null
    private var playbackStarted = false
    private var isCurrentStreamRadio = false

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    // Título dinámico ICY/ID3 (si el propio stream lo trae); se guarda aparte
    // del de EPG porque, cuando hay uno, siempre gana sobre el de la guía.
    private var lastDynamicTitle: String? = null

    private val epgHandler = Handler(Looper.getMainLooper())
    private val epgRefreshRunnable = object : Runnable {
        override fun run() {
            refreshNowPlayingDisplay()
            epgHandler.postDelayed(this, EPG_REFRESH_INTERVAL_MS)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* da igual el resultado */ }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@PlayerActivity,
                getString(R.string.player_error, error.message ?: error.errorCodeName),
                Toast.LENGTH_LONG
            ).show()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
            isCurrentStreamRadio = !hasVideo
            binding.nowPlayingOverlay.visibility = if (isCurrentStreamRadio) View.VISIBLE else View.GONE
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            val stationName = currentStream?.name
            val dynamic = mediaMetadata.title?.toString()?.trim()
            lastDynamicTitle = dynamic.takeIf { !it.isNullOrBlank() && !it.equals(stationName, ignoreCase = true) }
            refreshNowPlayingDisplay()
        }
    }

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
        if (stream.type.equals("YOUTUBE", ignoreCase = true)) {
            // Un enlace de YouTube no es un stream que ExoPlayer pueda
            // reproducir directamente; lo abrimos en la app de YouTube (o en
            // el navegador si no está instalada) y cerramos esta pantalla.
            openExternally(stream)
            return
        }
        title = stream.name
        currentStream = stream

        binding.nowPlayingTitle.text = stream.name
        binding.nowPlayingLogo.load(stream.icon) {
            placeholder(R.drawable.ic_radio)
            error(R.drawable.ic_radio)
        }

        binding.trackSelectionButton.setOnClickListener { anchor -> showTrackSelectionMenu(anchor) }
        binding.playerView.setControllerVisibilityListener(
            // Tipo explícito: PlayerView tiene dos overloads de este método
            // (el actual ControllerVisibilityListener y el antiguo, obsoleto,
            // PlayerControlView.VisibilityListener), y ambos son interfaces de
            // un solo método con la misma forma (Int) -> Unit, así que una
            // lambda suelta es ambigua para el compilador ("overload
            // resolution ambiguity"); hay que decir cuál de las dos es.
            PlayerView.ControllerVisibilityListener { visibility ->
                binding.trackSelectionButton.visibility = visibility
            }
        )
        binding.playerView.keepScreenOn = true

        ensureNotificationPermission()
        resolveAndPlay(stream)
    }

    /** Abre `stream.url` fuera de la app (YouTube, o el navegador si no está instalada) y cierra esta pantalla. */
    private fun openExternally(stream: Stream) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(stream.url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                getString(R.string.player_error, "no hay ninguna app que pueda abrir ${stream.url}"),
                Toast.LENGTH_LONG
            ).show()
        }
        finish()
    }

    override fun onStart() {
        super.onStart()
        if (currentStream == null) return // canal no válido, o ya redirigido a una app externa (ver onCreate)
        epgHandler.removeCallbacks(epgRefreshRunnable)
        epgHandler.postDelayed(epgRefreshRunnable, EPG_REFRESH_INTERVAL_MS)
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (future.isDone && !future.isCancelled) {
                    try {
                        onControllerConnected(future.get())
                    } catch (e: Exception) {
                        Toast.makeText(this, getString(R.string.player_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    override fun onStop() {
        super.onStop()
        epgHandler.removeCallbacks(epgRefreshRunnable)
        val ctrl = controller
        if (ctrl != null) {
            if (isFinishing) {
                // Salimos de verdad hacia la lista de canales: paramos del todo.
                ctrl.stop()
                ctrl.clearMediaItems()
                playbackStarted = false
            } else if (!isCurrentStreamRadio) {
                // Vídeo/TV en segundo plano (bloqueo, Home...): igual que
                // antes, se pausa (no tiene sentido gastar datos/batería
                // decodificando vídeo que no se ve).
                ctrl.pause()
            }
            // Radio + no isFinishing (p. ej. se bloqueó la pantalla): se deja
            // sonando; PlaybackService sigue vivo y controla la sesión.
        }
        ctrl?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        binding.playerView.player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingStream = null
    }

    // -----------------------------------------------------------------
    // Conexión con PlaybackService a través de MediaController
    // -----------------------------------------------------------------

    private fun onControllerConnected(mediaController: MediaController) {
        controller = mediaController
        binding.playerView.player = mediaController
        mediaController.addListener(playerListener)
        maybeStartPlayback()
    }

    private fun maybeStartPlayback() {
        val item = pendingMediaItem ?: return
        val ctrl = controller ?: return
        if (playbackStarted) return
        playbackStarted = true
        ctrl.setMediaItem(item)
        ctrl.playWhenReady = true
        ctrl.prepare()
    }

    // -----------------------------------------------------------------
    // Token + construcción del MediaItem (tipo, cabeceras, DRM viajan en
    // las extras: ver StreamMediaExtras)
    // -----------------------------------------------------------------

    private fun resolveAndPlay(stream: Stream) {
        val tokenUrl = stream.tokenUrl
        if (tokenUrl.isNullOrBlank()) {
            onStreamResolved(stream)
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
                    onStreamResolved(stream)
                } else {
                    onStreamResolved(stream.copy(url = stream.url.replace("{token}", token)))
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

    private fun onStreamResolved(stream: Stream) {
        val metadata = MediaMetadata.Builder()
            .setTitle(stream.name)
            .setExtras(StreamMediaExtras.build(stream))
            .build()

        pendingMediaItem = MediaItem.Builder()
            .setMediaId(stream.id)
            .setUri(stream.url)
            .setMediaMetadata(metadata)
            .build()

        maybeStartPlayback()
    }

    // -----------------------------------------------------------------
    // Texto de "ahora suena": por prioridad, (1) el título dinámico ICY/ID3
    // que el propio stream de radio trae (Media3 lo fusiona solo en
    // onMediaMetadataChanged), (2) si no hay, el programa que marca la
    // guía EPG ahora mismo para el tvg-id de este canal (si la lista trae
    // EPG y hay coincidencia), y si no hay ninguno de los dos, (3) el
    // nombre fijo del canal. epgRefreshRunnable llama a esto cada minuto
    // mientras la pantalla está abierta, porque el programa de la guía
    // puede cambiar sin que llegue ningún onMediaMetadataChanged nuevo.
    // -----------------------------------------------------------------

    private fun refreshNowPlayingDisplay() {
        val stationName = currentStream?.name ?: getString(R.string.now_playing_fallback)
        val dynamic = lastDynamicTitle
        val epgTitle = EpgRepository.currentTitle(currentStream?.tvgId)
            ?.takeIf { !it.equals(stationName, ignoreCase = true) }

        val nowTitle = dynamic ?: epgTitle
        if (!nowTitle.isNullOrBlank()) {
            binding.nowPlayingTitle.text = nowTitle
            binding.nowPlayingSubtitle.text = stationName
            binding.nowPlayingSubtitle.visibility = View.VISIBLE
        } else {
            binding.nowPlayingTitle.text = stationName
            binding.nowPlayingSubtitle.visibility = View.GONE
        }
    }

    /** Menú "Vídeo" / "Audio" / "Subtítulos" que abre el selector de pistas de Media3 para el tipo elegido. */
    private fun showTrackSelectionMenu(anchor: View) {
        val ctrl = controller ?: return
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_ID_VIDEO, 0, getString(R.string.track_video))
        popup.menu.add(0, MENU_ID_AUDIO, 1, getString(R.string.track_audio))
        popup.menu.add(0, MENU_ID_SUBTITLES, 2, getString(R.string.track_subtitles))
        popup.setOnMenuItemClickListener { item ->
            val trackType = when (item.itemId) {
                MENU_ID_VIDEO -> C.TRACK_TYPE_VIDEO
                MENU_ID_AUDIO -> C.TRACK_TYPE_AUDIO
                else -> C.TRACK_TYPE_TEXT
            }
            TrackSelectionDialogBuilder(this, item.title ?: "", ctrl, trackType)
                .build()
                .show()
            true
        }
        popup.show()
    }

    // -----------------------------------------------------------------
    // Permiso de notificaciones (Android 13+): sin él, el servicio sigue
    // reproduciendo igual, pero no se ven los controles en la pantalla de
    // bloqueo / notificación.
    // -----------------------------------------------------------------

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        var pendingStream: Stream? = null
        private const val MENU_ID_VIDEO = 1
        private const val MENU_ID_AUDIO = 2
        private const val MENU_ID_SUBTITLES = 3
        private const val EPG_REFRESH_INTERVAL_MS = 60_000L
    }
}
