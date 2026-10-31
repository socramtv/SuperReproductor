package com.example.superplayer.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.example.superplayer.R
import com.example.superplayer.data.ChannelOverrides
import com.example.superplayer.data.ContinueWatching

/**
 * "Continuar viendo" en la pantalla de inicio de Android TV / Fire TV con
 * Google TV: publica las películas/vídeos que se dejaron a medias en la fila
 * "Reproducir siguiente" (Watch Next) del sistema, con su barra de progreso;
 * al elegir uno, se abre en el punto donde se dejó (ver [WatchNextActivity]).
 *
 * Se vuelve a sincronizar entera cada vez que cambia "Continuar viendo" (ver
 * ContinueWatching): se borra lo que esta app publicó antes y se vuelve a
 * poner lo actual, que son pocas filas. Solo en dispositivos de TV, y todo
 * con try/catch: si el sistema no admite la fila (algunos Fire TV antiguos)
 * simplemente no se publica nada, sin afectar al resto de la app.
 */
object WatchNextSync {

    private const val MAX_ROWS = 10

    fun sync(context: Context) {
        val app = context.applicationContext
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
        Thread {
            try {
                val resolver = app.contentResolver
                // El proveedor solo deja ver y borrar lo publicado por esta misma app.
                resolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null)
                val fallbackArt = Uri.parse("android.resource://${app.packageName}/${R.drawable.header_logo}")
                for (entry in ContinueWatching.getAll(app).take(MAX_ROWS)) {
                    if (ChannelOverrides.isHidden(app, entry.stream.id)) continue
                    val stream = ChannelOverrides.renamed(app, entry.stream)
                    val intent = Intent(app, WatchNextActivity::class.java).apply {
                        putExtra(WatchNextActivity.EXTRA_STREAM_ID, stream.id)
                    }
                    val art = stream.icon?.takeIf { it.startsWith("http") }?.let { Uri.parse(it) } ?: fallbackArt
                    val program = WatchNextProgram.Builder()
                        .setType(TvContractCompat.WatchNextPrograms.TYPE_CLIP)
                        .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                        .setLastEngagementTimeUtcMillis(entry.updatedAt)
                        .setTitle(stream.name)
                        .setDescription(
                            app.getString(
                                R.string.continue_progress,
                                ContinueWatching.formatClock(entry.positionMs),
                                ContinueWatching.formatClock(entry.durationMs)
                            )
                        )
                        .setPosterArtUri(art)
                        .setPosterArtAspectRatio(0) // 0 = ASPECT_RATIO_16_9
                        .setLastPlaybackPositionMillis(entry.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        .setDurationMillis(entry.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        .setInternalProviderId(stream.id)
                        .setIntent(intent)
                        .build()
                    resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues())
                }
            } catch (e: Exception) {
                // Sin soporte o sin permiso: no pasa nada, solo no sale la fila en el inicio.
            }
        }.start()
    }
}
