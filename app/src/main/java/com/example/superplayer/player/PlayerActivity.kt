package com.example.superplayer.player

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
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
import androidx.media3.common.MimeTypes
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
import com.example.superplayer.model.streamFromJson
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
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
 * tocar (sin arrastrar) el tercio izquierdo/derecho de la pantalla pasa al
 * canal anterior/siguiente de esa misma lista (dando la vuelta al llegar a
 * un extremo), sin recrear la pantalla; el tercio central sigue mostrando/
 * ocultando los controles, como antes (ver playerTapGestureDetector).
 * Deslizar el dedo en vez de solo tocar (en cualquier zona) adelanta o
 * retrasa el vídeo si el canal actual lo permite (un directo puro no
 * tiene nada que avanzar/retroceder); toque y arrastre no se pisan porque
 * son gestos distintos (ver onScroll en playerTapGestureDetector). En el
 * mando de TV pasa algo parecido: izquierda/derecha cambia de canal solo
 * mientras los controles están ocultos; en cuanto se abren, esas mismas
 * teclas pasan a hacer lo de siempre en Media3 (mover el foco, o avanzar/
 * retroceder si el foco está en la barra de progreso; ver dispatchKeyEvent).
 *
 * Arrastrar verticalmente sube/baja el volumen en la mitad derecha de la
 * pantalla (igual que YouTube) o el brillo en la mitad izquierda, cada uno
 * con su propio indicador (el del sistema para volumen, uno propio que se
 * oculta solo para brillo); ver handleVolumeDrag/handleBrightnessDrag/
 * handleSeekDrag, repartidos desde onScroll según a qué se decida que
 * corresponde el gesto la primera vez que se reconoce como arrastre.
 *
 * Imagen en imagen (PiP): para canales de vídeo (la radio no lo necesita,
 * ya sigue sonando en segundo plano sin más), tocar el botón de PiP o
 * salir de la app (Inicio, cambiar de app) mete el vídeo en una ventana
 * flotante en vez de pausarlo (ver maybeEnterPictureInPicture,
 * onUserLeaveHint). Desactivado del todo en Android TV (ver isTvDevice):
 * ahí no se comportaba bien y además interfería con cambiar de canal con
 * el mando.
 *
 * Reconexión automática: si el canal se corta del todo (no un simple
 * corte de red puntual, que ExoPlayer ya reintenta por su cuenta) salta
 * onPlayerError; en vez de solo avisar con un Toast como antes, se
 * reintenta solo unas pocas veces con una espera creciente entre cada
 * una, y solo si se agotan los reintentos se avisa del error (ver
 * scheduleAutoReconnectOrShowError).
 *
 * Chromecast: el botón de "enviar" (castButton, arriba a la derecha, junto
 * al de PiP) lo monta CastButtonFactory sobre un MediaRouteButton normal;
 * la propia librería se encarga de buscar dispositivos y de mandar
 * play/pausa/buscar al que se elija, porque PlaybackService publica un
 * CastPlayer que envuelve el ExoPlayer local (ver su comentario) y esta
 * pantalla sigue hablando con la MISMA MediaSession de siempre a través de
 * su MediaController, sin enterarse de cuál de los dos hay detrás en cada
 * momento. Lo único que sí hace falta llevar aquí a mano es la parte
 * visual: mientras se esté enviando (ver registerCastSessionListener/
 * onCastSessionChanged) no hay vídeo propio que pintar en el teléfono -el
 * de verdad se ve en el Chromecast-, así que se reutiliza el mismo overlay
 * negro de "radio" con un aviso de "Enviando a <dispositivo>", y el botón
 * de PiP se oculta (no tiene sentido flotar una ventana sin vídeo propio).
 * Oculto del todo en Android TV (igual que PiP: no tiene sentido "enviar"
 * desde la propia TV) y si el dispositivo no tiene Google Play Services
 * (ver isCastButtonUsable).
 *
 * LIMITACIÓN CONOCIDA (ver README): el Chromecast recibe la URL del canal
 * directamente desde el receptor multimedia genérico de Google, sin pasar
 * por StreamMediaSourceFactory; un canal con cabeceras HTTP propias
 * (headers/Referer) o con DRM ClearKey puede no reproducirse ahí aunque
 * funcione perfectamente en el reproductor local de esta app.
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

    // -----------------------------------------------------------------
    // Chromecast (ver el comentario de la clase, arriba, y PlaybackService):
    // isCastButtonUsable se decide una vez en onCreate (false si el
    // dispositivo no tiene Google Play Services o el framework de Cast no
    // está bien montado: ver applyControlsVisibility, que oculta el botón
    // del todo en ese caso). isCastingRemote/castDeviceName los mantiene al
    // día registerCastSessionListener mientras la pantalla está visible.
    // -----------------------------------------------------------------
    private var isCastButtonUsable = false
    private var isCastingRemote = false
    private var castDeviceName: String? = null
    private var castSessionManagerListener: SessionManagerListener<CastSession>? = null

    private val epgHandler = Handler(Looper.getMainLooper())
    private val epgRefreshRunnable = object : Runnable {
        override fun run() {
            refreshNowPlayingDisplay()
            epgHandler.postDelayed(this, EPG_REFRESH_INTERVAL_MS)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* da igual el resultado */ }

    // -----------------------------------------------------------------
    // Reconexión automática: cuenta de reintentos ya hechos para el canal
    // actual (se reinicia en switchChannel y en cuanto onIsPlayingChanged
    // confirma que de verdad está sonando/viéndose) y el Runnable
    // pendiente, si hay uno, para poder cancelarlo (cambio de canal,
    // onStop/onDestroy) y que no reaparezca un canal viejo de golpe.
    // -----------------------------------------------------------------
    private var autoReconnectAttempts = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var pendingReconnectRunnable: Runnable? = null

    private fun cancelPendingReconnect() {
        pendingReconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        pendingReconnectRunnable = null
    }

    private fun scheduleAutoReconnectOrShowError(error: PlaybackException) {
        val stream = currentStream
        if (stream == null || isFinishing || isDestroyed) return
        if (autoReconnectAttempts >= RECONNECT_DELAYS_MS.size) {
            Toast.makeText(
                this,
                getString(R.string.player_error, error.message ?: error.errorCodeName),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val delayMs = RECONNECT_DELAYS_MS[autoReconnectAttempts]
        autoReconnectAttempts++
        Toast.makeText(this, getString(R.string.player_reconnecting), Toast.LENGTH_SHORT).show()
        val runnable = Runnable {
            pendingReconnectRunnable = null
            if (isFinishing || isDestroyed) return@Runnable
            // Se relee currentStream (no se captura `stream` de arriba) por si
            // ha cambiado de canal mientras esperaba: aunque switchChannel ya
            // cancela este runnable, es una comprobación barata de más.
            val freshStream = currentStream ?: return@Runnable
            playbackStarted = false
            pendingMediaItem = null
            resolveAndPlay(freshStream)
        }
        pendingReconnectRunnable = runnable
        reconnectHandler.postDelayed(runnable, delayMs)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            scheduleAutoReconnectOrShowError(error)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // De verdad está sonando/viéndose: lo que sea que haya fallado
            // antes se ha recuperado, así que el canal actual vuelve a tener
            // su cupo completo de reintentos para la próxima vez que se corte.
            if (isPlaying) {
                autoReconnectAttempts = 0
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
            isCurrentStreamRadio = !hasVideo
            updateNowPlayingOverlayVisibility()
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
    //
    // onScroll (arrastrar el dedo, no solo tocar) se reparte entre dos
    // gestos posibles, decidido una sola vez por gesto (al primer
    // movimiento ya reconocido como arrastre) y fijado a partir de ahí
    // -aunque el dedo tuerza a mitad de camino- para que no cambie de uno a
    // otro mientras se está arrastrando:
    //   - predominantemente horizontal, en cualquier zona -> avanzar/
    //     retroceder el vídeo (handleSeekDrag). No hace falta acertar en la
    //     barra de progreso nativa de Media3 (fina, y solo pintada ahí con
    //     los controles ya abiertos).
    //   - predominantemente vertical, empezando en la mitad derecha ->
    //     subir/bajar el volumen (handleVolumeDrag), como YouTube. Empezando
    //     en la mitad izquierda -> subir/bajar el brillo (handleBrightnessDrag).
    // GestureDetector ya distingue por su cuenta un toque de un arrastre
    // (onScroll solo se dispara si el dedo se movió más del umbral normal
    // de gesto de Android), así que ninguno de los dos compite con el
    // cambio de canal de onSingleTapUp: cada gesto acaba siendo uno u otro,
    // nunca dos a la vez.
    // -----------------------------------------------------------------

    private enum class DragMode { NONE, SEEK, VOLUME, BRIGHTNESS }
    private var dragMode = DragMode.NONE
    private var seekStartCaptured = false
    private var seekDragStartPositionMs = 0L
    private var volumeStartCaptured = false
    private var volumeDragStartLevel = 0
    private var brightnessStartCaptured = false
    private var brightnessDragStartLevel = 0f

    private val audioManager: AudioManager by lazy { getSystemService(AudioManager::class.java) }

    // Brillo: a diferencia del volumen (que reutiliza el indicador del
    // propio sistema vía FLAG_SHOW_UI), no hay ningún indicador del sistema
    // para un brillo de ventana a medida, así que se muestra uno propio
    // (brightnessIndicator) y se oculta solo un rato después del último
    // cambio, con el mismo patrón Handler.postDelayed que ya usan EPG y la
    // reconexión automática en esta misma clase.
    private val brightnessHandler = Handler(Looper.getMainLooper())
    private val hideBrightnessIndicatorRunnable = Runnable {
        binding.brightnessIndicator.visibility = View.GONE
    }

    private fun handleSeekDrag(totalDx: Float): Boolean {
        val ctrl = controller ?: return true
        if (!ctrl.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return true
        val duration = ctrl.duration
        if (duration <= 0 || duration == C.TIME_UNSET) return true // directo puro: nada que avanzar/retroceder

        if (!seekStartCaptured) {
            seekStartCaptured = true
            seekDragStartPositionMs = ctrl.currentPosition
        }
        val width = binding.playerView.width
        if (width <= 0) return true
        val targetMs = (seekDragStartPositionMs + (totalDx / width) * duration)
            .toLong()
            .coerceIn(0, duration)
        ctrl.seekTo(targetMs)
        binding.playerView.showController()
        return true
    }

    private fun handleVolumeDrag(totalDy: Float): Boolean {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) return true
        if (!volumeStartCaptured) {
            volumeStartCaptured = true
            volumeDragStartLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        val height = binding.playerView.height
        if (height <= 0) return true
        // totalDy negativo = ha deslizado hacia arriba = sube el volumen.
        val target = Math.round(volumeDragStartLevel - (totalDy / height) * maxVolume)
            .coerceIn(0, maxVolume)
        // FLAG_SHOW_UI: reutiliza el propio indicador de volumen del
        // sistema (el mismo que sale con los botones físicos) en vez de
        // montar uno a medida aquí.
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return true
    }

    /**
     * Brillo actual de la ventana: si ya hay un valor propio aplicado antes
     * (0f a 1f), se usa ese; si todavía no hay ninguno (BRIGHTNESS_OVERRIDE_NONE,
     * -1f: se está mostrando con el brillo normal del sistema), se lee el
     * brillo real del sistema para partir de ahí, y no dar un salto brusco
     * en el primer arrastre.
     */
    private fun currentScreenBrightness(): Float {
        val override = window.attributes.screenBrightness
        if (override in 0f..1f) return override
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                .coerceIn(0, 255) / 255f
        } catch (e: Settings.SettingNotFoundException) {
            0.5f
        }
    }

    private fun showBrightnessIndicator(level: Float) {
        binding.brightnessIndicator.text = getString(R.string.brightness_percent, Math.round(level * 100))
        binding.brightnessIndicator.visibility = View.VISIBLE
        brightnessHandler.removeCallbacks(hideBrightnessIndicatorRunnable)
        brightnessHandler.postDelayed(hideBrightnessIndicatorRunnable, BRIGHTNESS_INDICATOR_HIDE_DELAY_MS)
    }

    private fun handleBrightnessDrag(totalDy: Float): Boolean {
        val height = binding.playerView.height
        if (height <= 0) return true
        if (!brightnessStartCaptured) {
            brightnessStartCaptured = true
            brightnessDragStartLevel = currentScreenBrightness()
        }
        // totalDy negativo = ha deslizado hacia arriba = sube el brillo;
        // recorrer toda la altura de la pantalla equivale al rango de
        // brillo completo, igual que con el volumen.
        val target = (brightnessDragStartLevel - totalDy / height).coerceIn(MIN_SCREEN_BRIGHTNESS, 1f)
        val params = window.attributes
        params.screenBrightness = target
        window.attributes = params
        showBrightnessIndicator(target)
        return true
    }

    private val playerTapGestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                dragMode = DragMode.NONE
                seekStartCaptured = false
                volumeStartCaptured = false
                brightnessStartCaptured = false
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val start = e1 ?: return false
                val totalDx = e2.x - start.x
                val totalDy = e2.y - start.y

                if (dragMode == DragMode.NONE) {
                    dragMode = when {
                        Math.abs(totalDx) > Math.abs(totalDy) -> DragMode.SEEK
                        start.x > binding.playerView.width / 2f -> DragMode.VOLUME
                        else -> DragMode.BRIGHTNESS
                    }
                }
                return when (dragMode) {
                    DragMode.SEEK -> handleSeekDrag(totalDx)
                    DragMode.VOLUME -> handleVolumeDrag(totalDy)
                    DragMode.BRIGHTNESS -> handleBrightnessDrag(totalDy)
                    DragMode.NONE -> false
                }
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val width = binding.playerView.width
                val height = binding.playerView.height

                // Franja de abajo: ahí es donde Media3 pone la barra de
                // progreso (llega hasta 100dp desde el borde inferior) y,
                // encima, la fila de ajustes/subtítulos/pantalla completa
                // (60dp) -las dos a lo ancho de TODA la pantalla-, así que
                // esa franja se deja siempre para esos controles nativos,
                // aunque en ese momento no haya ninguno pintado ahí. 120dp
                // deja un margen de sobra sobre esos 100dp reales.
                val bottomControlsPx = BOTTOM_CONTROLS_DP * resources.displayMetrics.density
                val inBottomControlsBand = height > 0 && e.y > height - bottomControlsPx

                if (!inBottomControlsBand) {
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
     * Visibilidad de los botones/carteles que aparecen y desaparecen junto
     * con los controles nativos de Media3 (llamada tanto desde el
     * ControllerVisibilityListener de onCreate como desde
     * onCastSessionChanged: empezar o acabar de enviar a un Chromecast
     * puede cambiar qué debe verse sin que los controles hayan cambiado de
     * visibilidad por su cuenta).
     */
    private fun applyControlsVisibility(visibility: Int) {
        binding.trackSelectionButton.visibility = visibility
        // En Android TV, o si el framework de Cast no está disponible en
        // este dispositivo, el botón se queda oculto del todo (ver
        // isCastButtonUsable).
        binding.castButton.visibility = if (isTvDevice || !isCastButtonUsable) View.GONE else visibility
        // PiP no tiene sentido para radio (ya suena en segundo plano sola),
        // en Android TV (ver isTvDevice), ni mientras se envía a un
        // Chromecast (no hay vídeo propio del teléfono que flotar: el de
        // verdad se ve en el Chromecast).
        binding.pipButton.visibility = if (isCurrentStreamRadio || isTvDevice || isCastingRemote) View.GONE else visibility
        binding.videoNowPlayingBar.visibility = if (isCurrentStreamRadio || isCastingRemote) View.GONE else visibility
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

        cancelPendingReconnect()
        autoReconnectAttempts = 0
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

    // -----------------------------------------------------------------
    // Mando de TV: izquierda/derecha del D-pad (y, si el mando los trae,
    // canal- /canal+, o anterior/siguiente de pista) cambian de canal igual
    // que tocar los lados en el móvil. No es un toque (MotionEvent), es una
    // pulsación de tecla física (KeyEvent), así que se coge aparte aquí.
    // dispatchKeyEvent() de la Activity es el primer sitio por el que pasa
    // cualquier tecla, antes de que le llegue a ningún botón de dentro de
    // PlayerView; así nos aseguramos de quedarnos la tecla nosotros primero.
    // Si no hay ninguna lista por la que moverse, no se consume la tecla y
    // se deja que el sistema haga lo que hiciera por defecto (por ejemplo,
    // mover el foco entre los botones de los controles).
    //
    // Excepción: con los controles ya abiertos, izquierda/derecha NO se
    // cogen aquí, se dejan pasar tal cual (super.dispatchKeyEvent). Así,
    // si el foco está en la barra de progreso, Media3 la mueve él solo
    // (DefaultTimeBar.onKeyDown ya sabe responder a izquierda/derecha
    // avanzando/retrocediendo, y de hecho no hace nada si el canal actual
    // no admite avance/retroceso -un directo puro-, todo esto de serie, sin
    // tocar nada aquí); y si el foco está en otro control, las mismas
    // teclas mueven el foco entre ellos, como es normal. O sea: con los
    // controles ocultos (el caso normal viendo la tele) izquierda/derecha
    // cambian de canal; en cuanto se abren los controles, pasan a hacer lo
    // de siempre en Media3.
    // -----------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isDpadLeftRight = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (isDpadLeftRight && binding.playerView.isControllerFullyVisible) {
            return super.dispatchKeyEvent(event)
        }

        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_MEDIA_NEXT -> 1
            else -> 0
        }
        val canSwitchChannel = direction != 0 && currentIndex >= 0 && channelList.size > 1
        if (canSwitchChannel) {
            // Se consume tanto la bajada como la subida de la tecla (para
            // que no le llegue nada suelto a ningún botón con el foco),
            // pero solo se cambia de canal una vez, en la bajada.
            if (event.action == KeyEvent.ACTION_DOWN) {
                switchChannel(direction)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val stream = pendingStream ?: readStreamFromShortcutIntent()
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
        binding.pipButton.setOnClickListener { maybeEnterPictureInPicture() }

        // Chromecast: en Android TV ni se intenta montar el botón (no tiene
        // sentido "enviar" desde la propia TV; ver el comentario de la
        // clase). CastButtonFactory puede lanzar si el dispositivo no tiene
        // Google Play Services o el framework de Cast no está bien montado
        // -se captura para que, en ese caso, el resto de la pantalla
        // funcione exactamente igual, solo sin esta opción (ver
        // applyControlsVisibility, que oculta el botón del todo si
        // isCastButtonUsable queda en false).
        isCastButtonUsable = if (isTvDevice) {
            false
        } else {
            try {
                CastButtonFactory.setUpMediaRouteButton(this, binding.castButton)
                true
            } catch (e: Exception) {
                false
            }
        }

        binding.playerView.setControllerVisibilityListener(
            // Tipo explícito: PlayerView tiene dos overloads de este método
            // (el actual ControllerVisibilityListener y el antiguo, obsoleto,
            // PlayerControlView.VisibilityListener), y ambos son interfaces de
            // un solo método con la misma forma (Int) -> Unit, así que una
            // lambda suelta es ambigua para el compilador ("overload
            // resolution ambiguity"); hay que decir cuál de las dos es.
            PlayerView.ControllerVisibilityListener { visibility -> applyControlsVisibility(visibility) }
        )
        binding.playerView.keepScreenOn = true
        binding.playerView.setOnTouchListener { _, event -> playerTapGestureDetector.onTouchEvent(event) }

        ensureNotificationPermission()
        resolveAndPlay(stream)
    }

    /**
     * Si esta pantalla se abrió desde un acceso directo del icono de la app
     * (ver ShortcutsHelper) en vez de desde dentro de la propia app
     * (MainActivity/StreamListActivity/EpgGridActivity, que rellenan
     * pendingStream justo antes de abrir esta pantalla): el Stream completo
     * viaja en el propio Intent, porque un acceso directo puede abrirse con
     * el proceso recién arrancado, sin ninguna lista cargada todavía de la
     * que sacarlo por id. null si esta pantalla no se abrió así (el Intent
     * no trae ese extra), que es lo normal cuando sí hay pendingStream.
     */
    private fun readStreamFromShortcutIntent(): Stream? {
        val json = intent.getStringExtra(ShortcutsHelper.EXTRA_SHORTCUT_STREAM_JSON) ?: return null
        return streamFromJson(json)
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
        registerCastSessionListener()
    }

    // -----------------------------------------------------------------
    // Imagen en imagen (PiP): onUserLeaveHint salta justo antes de salir de
    // esta pantalla por Inicio o al cambiar de app (NO con el botón Atrás,
    // que sigue cerrando el reproductor como siempre); es el punto de
    // entrada que recomienda la propia documentación de Android para PiP
    // automático. El resto de la lógica (comprobaciones, construcción de
    // PictureInPictureParams, ocultar/mostrar los controles propios de esta
    // pantalla) vive junto a onPictureInPictureModeChanged, más abajo.
    // -----------------------------------------------------------------

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        maybeEnterPictureInPicture()
    }

    override fun onStop() {
        super.onStop()
        unregisterCastSessionListener()
        epgHandler.removeCallbacks(epgRefreshRunnable)
        cancelPendingReconnect()
        autoReconnectAttempts = 0
        val ctrl = controller
        if (ctrl != null) {
            if (isFinishing) {
                // Salimos de verdad hacia la lista de canales: paramos del todo.
                ctrl.stop()
                ctrl.clearMediaItems()
                playbackStarted = false
            } else if (!isCurrentStreamRadio && !isCastingRemote) {
                // Vídeo/TV en segundo plano (bloqueo, Home...): igual que
                // antes, se pausa (no tiene sentido gastar datos/batería
                // decodificando vídeo que no se ve).
                ctrl.pause()
            }
            // Radio, o enviando a un Chromecast, + no isFinishing (p. ej. se
            // bloqueó la pantalla): se deja sonando/enviando; la reproducción
            // (local o remota) es independiente de que esta pantalla esté en
            // primer plano, igual que ya pasaba con la radio.
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
    // Imagen en imagen (PiP): comprobaciones y construcción de los
    // parámetros antes de entrar (maybeEnterPictureInPicture, llamada tanto
    // desde pipButton como desde onUserLeaveHint, arriba), y ocultar/mostrar
    // a mano los controles propios de esta pantalla al entrar/salir, ya que
    // el sistema solo oculta los controles nativos de PlayerView (a través
    // de useController), no las vistas propias de esta app.
    // -----------------------------------------------------------------

    // PiP se desactiva del todo en Android TV: en el dispositivo de pruebas
    // del usuario no se comportaba bien (ventana rota) y además interfería
    // con cambiar de canal con el D-pad; en TV tampoco aporta gran cosa (no
    // hay "cambiar de app" en primer plano igual que en móvil). Se detecta
    // con la misma característica que ya declara el manifiesto para
    // Android TV (android.software.leanback), así que en TV ni se muestra
    // el botón (ver el ControllerVisibilityListener en onCreate) ni se
    // entra solo al salir de la app.
    private val isTvDevice: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun maybeEnterPictureInPicture() {
        if (isCurrentStreamRadio) return // la radio ya sigue sonando en segundo plano sin PiP
        if (isTvDevice) return
        if (controller == null) return
        if (isInPictureInPictureMode) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(currentVideoAspectRatio())
            .build()
        try {
            enterPictureInPictureMode(params)
        } catch (e: IllegalStateException) {
            // El sistema puede negarse (p. ej. políticas del dispositivo o
            // del fabricante); no hay nada que hacer salvo seguir en
            // pantalla completa normal.
        }
    }

    /**
     * Relación de ancho/alto del vídeo actual para la ventana de PiP,
     * recortada al rango que admite Android (ver MAX_PIP_ASPECT_RATIO /
     * MIN_PIP_ASPECT_RATIO: fuera de ese rango, setAspectRatio lanza
     * IllegalArgumentException). Si todavía no se conoce el tamaño real del
     * vídeo (p. ej. justo al entrar, antes del primer fotograma), se usa
     * 16:9 como valor por defecto razonable.
     */
    private fun currentVideoAspectRatio(): Rational {
        val videoSize = controller?.videoSize
        val width = videoSize?.width ?: 0
        val height = videoSize?.height ?: 0
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio > MAX_PIP_ASPECT_RATIO -> Rational(239, 100)
            ratio < MIN_PIP_ASPECT_RATIO -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // Los controles nativos de PlayerView y los botones propios de
            // esta pantalla no caben ni hacen falta en la ventana flotante
            // (el sistema ya pone encima sus propios botones de cerrar/
            // expandir). useController=false, además de ocultarlos, evita
            // que un toque los vuelva a sacar mientras se está en PiP.
            binding.playerView.useController = false
            binding.trackSelectionButton.visibility = View.GONE
            binding.castButton.visibility = View.GONE
            binding.pipButton.visibility = View.GONE
            binding.videoNowPlayingBar.visibility = View.GONE
        } else {
            // Al volver a pantalla completa se restauran los controles
            // nativos y se muestran los propios otra vez a través del mismo
            // ControllerVisibilityListener de siempre (ver onCreate), que ya
            // sabe qué le toca a cada uno según si el canal es de radio.
            binding.playerView.useController = true
            binding.playerView.showController()
        }
    }

    // -----------------------------------------------------------------
    // Chromecast: CastButtonFactory (ver onCreate) ya se encarga solo de
    // buscar dispositivos y de mandar los comandos de reproducción al sitio
    // correcto (local o remoto, a través del CastPlayer que publica
    // PlaybackService). Lo único que hace falta llevar aquí a mano es
    // enterarse de CUÁNDO se empieza/acaba de enviar -para la parte visual
    // de esta pantalla (ver applyControlsVisibility/
    // updateNowPlayingOverlayVisibility)-, y eso se consigue con un
    // SessionManagerListener clásico del SDK de Cast, no con el propio
    // CastPlayer: así se puede leer el nombre del dispositivo
    // (CastDevice.friendlyName) para el aviso en pantalla.
    // -----------------------------------------------------------------

    private fun registerCastSessionListener() {
        if (!isCastButtonUsable) return
        try {
            val sessionManager = CastContext.getSharedInstance(this).sessionManager
            val listener = object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(session: CastSession, sessionId: String) = onCastSessionChanged(session)
                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onCastSessionChanged(session)
                override fun onSessionEnded(session: CastSession, error: Int) = onCastSessionChanged(null)
                override fun onSessionSuspended(session: CastSession, reason: Int) = onCastSessionChanged(null)
                override fun onSessionStarting(session: CastSession) {}
                override fun onSessionStartFailed(session: CastSession, error: Int) {}
                override fun onSessionEnding(session: CastSession) {}
                override fun onSessionResuming(session: CastSession, sessionId: String) {}
                override fun onSessionResumeFailed(session: CastSession, error: Int) {}
            }
            sessionManager.addSessionManagerListener(listener, CastSession::class.java)
            castSessionManagerListener = listener
            // Por si ya había una sesión activa de antes (p. ej. se vuelve a
            // esta pantalla con el móvil ya conectado a un Chromecast de una
            // vez anterior): refleja el estado actual ya mismo, sin esperar
            // a que cambie.
            onCastSessionChanged(sessionManager.currentCastSession)
        } catch (e: Exception) {
            // Sin esto no hay ni Cast que listar (ver isCastButtonUsable,
            // que ya habría evitado llegar aquí en la mayoría de los casos).
        }
    }

    private fun unregisterCastSessionListener() {
        val listener = castSessionManagerListener ?: return
        castSessionManagerListener = null
        try {
            CastContext.getSharedInstance(this).sessionManager
                .removeSessionManagerListener(listener, CastSession::class.java)
        } catch (e: Exception) {
            // Nada que limpiar si ni siquiera había Cast disponible.
        }
    }

    private fun onCastSessionChanged(session: CastSession?) {
        isCastingRemote = session?.isConnected == true
        castDeviceName = session?.castDevice?.friendlyName
        updateNowPlayingOverlayVisibility()
        refreshNowPlayingDisplay()
        applyControlsVisibility(if (binding.playerView.isControllerFullyVisible) View.VISIBLE else View.GONE)
    }

    /**
     * El overlay negro de "radio" (logo + texto centrado) también sirve
     * para avisar de que se está enviando a un Chromecast: en los dos casos
     * no hay vídeo propio que mostrar en esta pantalla (ver el comentario
     * de la clase). El texto en sí lo decide refreshNowPlayingDisplay.
     */
    private fun updateNowPlayingOverlayVisibility() {
        // El logo ya lo carga onCreate()/switchChannel() cada vez que
        // cambia el canal; aquí solo hace falta decidir si el overlay se ve
        // o no (el texto de dentro lo decide refreshNowPlayingDisplay).
        binding.nowPlayingOverlay.visibility = if (isCurrentStreamRadio || isCastingRemote) View.VISIBLE else View.GONE
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
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(stream.name)
            .setExtras(StreamMediaExtras.build(stream))

        // Logo del canal en la notificación / pantalla de bloqueo: sin esto,
        // el MediaMetadata no lleva ninguna imagen y el sistema pinta el
        // icono genérico de "música" en vez del logo. PlaybackService no
        // configura ningún BitmapLoader propio, así que usa el que trae
        // Media3 por defecto (un DataSourceBitmapLoader sobre
        // DefaultDataSource.Factory, confirmado en el fuente real de
        // Media3): ese ya sabe descargar una URL http/https igual que
        // cualquier otra petición de red de la app, así que basta con pasar
        // la URL del logo, sin montar nada aparte aquí ni en PlaybackService.
        stream.icon?.takeIf { it.isNotBlank() }?.let { icon ->
            metadataBuilder.setArtworkUri(Uri.parse(icon))
        }
        val metadata = metadataBuilder.build()

        pendingMediaItem = MediaItem.Builder()
            .setMediaId(stream.id)
            .setUri(stream.url)
            .setMimeType(guessMimeTypeForCast(stream))
            .setMediaMetadata(metadata)
            .build()

        maybeStartPlayback()
    }

    /**
     * Solo le interesa a Chromecast: el reproductor local resuelve el tipo
     * real por su cuenta a partir de StreamMediaExtras (ver
     * StreamMediaSourceFactory), pero el receptor multimedia genérico de
     * Google necesita que el propio MediaItem declare el tipo de contenido
     * para saber qué hacer con la URL en vez de adivinarlo. Misma lógica que
     * ya usa StreamMediaSourceFactory a partir de stream.type (DASH/MPD,
     * HLS/M3U8); si el canal no trae ese dato, se mira la extensión de la
     * URL como última opción antes de dejarlo sin tipo declarado.
     */
    private fun guessMimeTypeForCast(stream: Stream): String? {
        val explicit = when (stream.type.trim().uppercase()) {
            "DASH", "MPD" -> MimeTypes.APPLICATION_MPD
            "HLS", "M3U8" -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
        if (explicit != null) return explicit
        val path = Uri.parse(stream.url).path.orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
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

        // Enviando a un Chromecast: no hay vídeo propio que mostrar en esta
        // pantalla (el de verdad se ve en el Chromecast), así que este
        // aviso gana sobre el "ahora suena"/EPG normal, para que quede
        // claro que se está enviando y a dónde.
        val castingTo = castDeviceName
        if (isCastingRemote && castingTo != null) {
            val castText = getString(R.string.casting_to_device, castingTo)
            applyNowPlayingText(binding.nowPlayingTitle, binding.nowPlayingSubtitle, stationName, castText)
            applyNowPlayingText(binding.videoNowPlayingTitle, binding.videoNowPlayingSubtitle, stationName, castText)
            return
        }

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

        // Alto (en dp) de la franja inferior reservada para los controles
        // nativos de Media3 (barra de progreso + fila de ajustes), a todo
        // lo ancho de la pantalla: ver el comentario en onSingleTapUp.
        private const val BOTTOM_CONTROLS_DP = 120f

        // Reconexión automática: espera (en ms) antes de cada reintento tras
        // un corte total del canal; creciente para no machacar un servidor
        // que ya está teniendo problemas. El número de reintentos antes de
        // rendirse y mostrar el error es RECONNECT_DELAYS_MS.size (ver
        // scheduleAutoReconnectOrShowError).
        private val RECONNECT_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L)

        // Imagen en imagen: relación de aspecto máxima/mínima que admite
        // Android (documentado por la propia PictureInPictureParams.Builder.
        // setAspectRatio); fuera de este rango lanza IllegalArgumentException,
        // así que currentVideoAspectRatio() recorta a este rango antes de
        // construir los parámetros de PiP.
        private const val MAX_PIP_ASPECT_RATIO = 2.39f
        private const val MIN_PIP_ASPECT_RATIO = 1f / 2.39f

        // Gesto de brillo: nunca se deja a 0 del todo (una pantalla
        // totalmente negra sería difícil de recuperar a ciegas), y el
        // indicador en pantalla se oculta solo un rato después del último
        // cambio (ver showBrightnessIndicator).
        private const val MIN_SCREEN_BRIGHTNESS = 0.02f
        private const val BRIGHTNESS_INDICATOR_HIDE_DELAY_MS = 800L
    }
}
