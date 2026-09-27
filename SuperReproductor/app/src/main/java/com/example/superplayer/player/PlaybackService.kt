package com.example.superplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
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
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(StreamMediaSourceFactory(this))
            .build()
        player.setHandleAudioBecomingNoisy(true)

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
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
        mediaSession = null
        super.onDestroy()
    }
}
