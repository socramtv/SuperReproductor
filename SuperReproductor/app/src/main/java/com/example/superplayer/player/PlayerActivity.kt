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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
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
 *
 * Cambio de canal tocando la pantalla: si este canal viene de una lista
 * (categoría, favoritos o resultados de búsqueda; ver companion.pendingChannelList),
 * tocar el tercio izquierdo/derecho de la pantalla pasa al canal
 * anterior/siguiente de esa misma lista (dando la vuelta al llegar a un
 * extremo), sin recrear la pantalla; el tercio central sigue mostrando/
 * ocultando los controles, como antes (ver playerTapGestureDetector).
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    private var currentStream: Stream? = null
    private var pendingMediaItem: MediaItem? = null
    private var playbackStarted = false
    private var isCurrentStreamRadio = false

    // Lista de canales de la categoría/favoritos/búsqueda desde la que se
    // abrió este canal (ver companion.pendingChannelList) y la posición de
    // currentStream dentro de ella; permiten "canal siguiente/anterior" al
    // tocar los lados de la pantalla (ver playerTapGestureDetector). Si el
    // canal no viene de ninguna lista, o la lista solo tiene un canal,
    // quedan vacíos/-1 y esos toques laterales no hacen nada especial (solo
    // el tercio central sigue mostrando/ocultando los controles).
    private var channelList: List<Stream> = emptyList()
    private var currentIndex: Int = -1

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

    // -----------------------------------------------------------------
    // Toques en la pantalla del reproductor: tercio izquierdo -> canal
    // anterior, tercio derecho -> canal siguiente, tercio central -> mostrar/
    // ocultar controles. Se implementa entero aquí, en vez de dejar que
    // PlayerView siga gestionando también el toque central por su cuenta,
    // porque PlayerView necesita ver el gesto completo (bajada Y subida)
    // para reconocer su propio toque; si esta app solo decidiera "esto no es
    // lateral, que lo procese PlayerView" al llegar la subida, a PlayerView
    // le llegaría un gesto incompleto (sin la bajada) y no lo detectaría.
    // Por eso onDown() devuelve siempre true (nos quedamos el gesto entero)
    // y el tercio central se resuelve aquí mismo, con los mismos métodos
    // (isControllerFullyVisible/showController/hideController) que usa
    // PlayerView internamente para lo mismo.
    // -----------------------------------------------------------------

    private val playerTapGestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val width = binding.playerView.width
                val canSwitchChannel = currentIndex >= 0 && channelList.size > 1 && width > 0
                if (canSwitchChannel) {
                    when {
                        e.x < width * SIDE_ZONE_FRACTION -> {
                            switchChannel(-1)
                            return true
                        }
                        e.x > width * (1 - SIDE_ZONE_FRACTION) -> {
                            switchChannel(1)
                            return true
                        }
                    }
                }
                toggleController()
                return true
            }
        })
    }

    private fun toggleController() {
        if (binding.playerView.isControllerFullyVisible) {
            binding.playerView.hideController()
        } else {
            binding.playerView.showController()
        }
    }

    /**
     * Cambia al canal en `currentIndex + direction` dentro de channelList
     * (da la vuelta al llegar a un extremo: siguiente desde el último vuelve
     * al primero, y viceversa) y lo reproduce ahí mismo, sin recrear la
     * pantalla. Reutiliza resolveAndPlay/onStreamResolved -los mismos que
     * usa onCreate() para el canal inicial-, así que el token, el tipo, las
     * cabeceras y el DRM del nuevo canal se resuelven exactamente igual.
     */
    private fun switchChannel(direction: Int) {
        val size = channelList.size
        if (size <= 1 || currentIndex < 0) return
        val newIndex = ((currentIndex + direction) % size + size) % size
        val newStream = channelList[newIndex]
        currentIndex = newIndex

        playbackStarted = false
        pendingMediaItem = null
        lastDynamicTitle = null
        currentStream = newStream

        title = newStream.name
        binding.nowPlayingTitle.text = newStream.name
        binding.nowPlayingLogo.load(newStream.icon) {
            placeholder(R.drawable.ic_radio)
            error(R.drawable.ic_radio)
        }
        refreshNowPlayingDisplay()
        resolveAndPlay(newStream)
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
        channelList = pendingChannelList
        currentIndex = channelList.indexOfFirst { it.id == stream.id }

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
                // Para radio, el overlay de arriba ya se encarga (ver
                // onTracksChanged): este cartel es solo para vídeo, y
                // aparece/desaparece junto con los controles normales.
                binding.videoNowPlayingBar.visibility = if (isCurrentStreamRadio) View.GONE else visibility
            }
        )
        binding.playerView.keepScreenOn = true
        binding.playerView.setOnTouchListener { _, event -> playerTapGestureDetector.onTouchEvent(event) }

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
        pendingChannelList = emptyList()
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
        // Mismo texto en las dos vistas: el overlay permanente de radio, y
        // el cartel de vídeo que aparece/desaparece con los controles.
        applyNowPlayingText(binding.nowPlayingTitle, binding.nowPlayingSubtitle, stationName, nowTitle)
        applyNowPlayingText(binding.videoNowPlayingTitle, binding.videoNowPlayingSubtitle, stationName, nowTitle)
    }

    private fun applyNowPlayingText(titleView: TextView, subtitleView: TextView, stationName: String, nowTitle: String?) {
        if (!nowTitle.isNullOrBlank()) {
            titleView.text = nowTitle
            subtitleView.text = stationName
            subtitleView.visibility = View.VISIBLE
        } else {
            titleView.text = stationName
            subtitleView.visibility = View.GONE
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

        // Lista de canales de la categoría/favoritos/búsqueda desde la que
        // se abre pendingStream; la rellenan MainActivity y StreamListActivity
        // justo antes de lanzar esta pantalla (mismo patrón que pendingStream:
        // propiedad estática en vez de extra del Intent, para no toparse con
        // el límite de tamaño de Binder en playlists grandes).
        var pendingChannelList: List<Stream> = emptyList()

        private const val MENU_ID_VIDEO = 1
        private const val MENU_ID_AUDIO = 2
        private const val MENU_ID_SUBTITLES = 3
        private const val EPG_REFRESH_INTERVAL_MS = 60_000L

        // Ancho de las zonas laterales de toque (izquierda/derecha), como
        // fracción del ancho de PlayerView: con 1/3, el tercio izquierdo pasa
        // al canal anterior, el tercio derecho al siguiente, y el tercio
        // central de en medio muestra/oculta los controles.
        private const val SIDE_ZONE_FRACTION = 1f / 3f
    }
}
