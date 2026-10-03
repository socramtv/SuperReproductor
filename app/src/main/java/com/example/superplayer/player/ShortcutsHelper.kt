package com.example.superplayer.player

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.superplayer.R
import com.example.superplayer.model.Stream
import com.example.superplayer.model.toJson

/**
 * Accesos directos a los canales favoritos manteniendo pulsado el icono de
 * la app. Son "dinámicos" (ShortcutManagerCompat, en vez de un <shortcuts>
 * fijo en XML): dependen de los favoritos de cada usuario, que cambian con
 * el tiempo, así que se vuelven a publicar entera cada vez que cambian (ver
 * MainActivity.refreshShortcuts) en vez de declararse una sola vez.
 *
 * El Intent de cada acceso directo lleva el Stream ENTERO serializado a
 * JSON (ver Stream.toJson/streamFromJson en model/Playlist.kt), no solo su
 * id: un acceso directo puede abrirse con la app completamente cerrada
 * (arranque en frío), y en ese caso no hay ninguna lista cargada todavía
 * de la que sacar ese canal por id -PlayerActivity.pendingStream (el
 * mecanismo normal de MainActivity/StreamListActivity/EpgGridActivity) es
 * un simple campo en memoria que no sobrevive a que el proceso ni siquiera
 * haya arrancado-, así que el acceso directo tiene que traer todo lo
 * necesario (incluidas cabeceras HTTP o DRM propios del canal, si los
 * tiene) él mismo. Ver PlayerActivity.readStreamFromShortcutIntent.
 *
 * Usa el mismo icono de estrella (ic_favorite) para todos en vez del logo
 * de cada canal: ese logo viene de una URL y descargarlo aquí sería una
 * descarga de red bloqueante por cada favorito justo al guardar/quitar
 * uno, para un beneficio puramente estético.
 */
object ShortcutsHelper {

    const val EXTRA_SHORTCUT_STREAM_JSON = "shortcut_stream_json"

    private const val FALLBACK_MAX_SHORTCUTS = 4

    /** Vuelve a publicar los accesos directos a partir de los favoritos actuales. Barato: se puede llamar cada vez que cambian. */
    fun refresh(context: Context, favorites: List<Stream>) {
        val maxCount = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
            .takeIf { it > 0 } ?: FALLBACK_MAX_SHORTCUTS

        val shortcuts = favorites.take(maxCount).mapIndexed { index, stream ->
            val intent = Intent(context, PlayerActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_SHORTCUT_STREAM_JSON, stream.toJson())
            }
            ShortcutInfoCompat.Builder(context, "fav_${stream.id}")
                .setShortLabel(stream.name)
                .setLongLabel(stream.name)
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_favorite))
                .setIntent(intent)
                .setRank(index)
                .build()
        }

        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (e: Exception) {
            // Si el fabricante/launcher no admite accesos directos dinámicos
            // (o falla por lo que sea), la app sigue funcionando exactamente
            // igual, simplemente sin ellos.
        }
    }
}
