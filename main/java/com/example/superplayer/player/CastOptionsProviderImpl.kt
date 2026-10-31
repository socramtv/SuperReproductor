package com.example.superplayer.player

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Configuración mínima de Chromecast: usa el receptor multimedia genérico
 * de Google (DEFAULT_MEDIA_RECEIVER_APPLICATION_ID) en vez de uno propio,
 * porque esta app no aloja ningún receptor a medida — con eso basta para
 * enviar vídeo/audio por URL directa (HLS/DASH/progresivo), que es todo lo
 * que hace falta aquí.
 *
 * El framework de Cast localiza esta clase por reflexión a partir del
 * meta-data OPTIONS_PROVIDER_CLASS_NAME en AndroidManifest.xml (con el
 * nombre completo, no vale el punto inicial que sí admiten las Activity de
 * ese mismo manifiesto: quien lo lee no es Android, es esta librería), la
 * primera vez que hace falta un CastContext (al tocar el botón de enviar en
 * PlayerActivity, o al construir el CastPlayer en PlaybackService).
 *
 * IMPORTANTE (limitación conocida, ver README): al enviar a un Chromecast,
 * el receptor genérico pide el stream directamente por su URL, SIN las
 * cabeceras HTTP propias del canal (headers/Referer de PlaylistData) ni el
 * DRM ClearKey que sí entiende el reproductor local (ver
 * StreamMediaSourceFactory) — eso solo lo sabe interpretar el ExoPlayer de
 * esta app, no un Chromecast genérico. Un canal sin cabeceras ni DRM (la
 * mayoría de IPTV por URL directa) se envía sin problema.
 */
class CastOptionsProviderImpl : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
