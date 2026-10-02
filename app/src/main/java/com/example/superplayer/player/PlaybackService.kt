package com.example.superplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.superplayer.ui.MainActivity

/**
 * Dueño real del ExoPlayer, publicado como MediaSession. Gracias a esto,
 * la reproducción (sobre todo de radio/audio) sigue activa aunque
 * PlayerActivity se detenga (se bloquee la pantalla, se pulse Home, se
 * cambie de app), y aparecen los controles de reproducción del sistema
 * (notificación / pantalla de bloqueo).
 *
 * PlayerActivity ya no crea ni controla un ExoPlayer directamente: solo
 * mantiene un MediaController conectado a la sesión publicada aquí.
 *
 * Chromecast: el ExoPlayer local se envuelve en un CastPlayer (ver
 * setLocalPlayer) y es ESE el que se publica en la MediaSession, no el
 * ExoPlayer directamente. Mientras no haya ningún Chromecast elegido,
 * CastPlayer reenvía todo tal cual al ExoPlayer local -así que para
 * PlayerActivity, a través de su MediaController, no cambia nada-; en
 * cuanto se elige un dispositivo con el botón de la pantalla de
 * reproducción (ver PlayerActivity.castButton), pasa a mandar esos mismos
 * comandos (play/pausa/buscar) al receptor remoto en su lugar, sin que
 * PlayerActivity tenga que enterarse de cuál de los dos es en cada
 * momento. Si el dispositivo no tiene Google Play Services (algunas cajas
 * de Android TV sin Google) o el framework de Cast no está bien montado,
 * CastPlayer.Builder lanzará una excepción aquí: se captura y se sigue
 * reproduciendo en local exactamente igual que siempre, sin más que la
 * opción de "enviar" desaparecida.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    // Referencia aparte al ExoPlayer de verdad (aunque la MediaSession
    // publique el CastPlayer que lo envuelve): por si acaso CastPlayer.
    // release() no liberase también el reproductor local que envuelve -no
    // queda documentado del todo-, se libera aquí también en onDestroy.
    // ExoPlayer.release() no hace nada si ya estaba liberado, así que
    // llamarlo dos veces nunca puede ser el origen de un problema nuevo.
    private var localPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(StreamMediaSourceFactory(this))
            .build()
        player.setHandleAudioBecomingNoisy(true)
        localPlayer = player

        val sessionPlayer: Player = try {
            CastPlayer.Builder(this).setLocalPlayer(player).build()
        } catch (e: Exception) {
            player
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(openAppIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaSession ?: return
        val player = session.player
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        localPlayer?.release()
        localPlayer = null
        mediaSession = null
        super.onDestroy()
    }
}
