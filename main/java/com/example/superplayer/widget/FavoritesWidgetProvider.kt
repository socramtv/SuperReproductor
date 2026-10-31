package com.example.superplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import com.example.superplayer.R
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.data.FavoritesStore
import com.example.superplayer.model.Stream
import com.example.superplayer.model.toJson
import com.example.superplayer.player.PlayerActivity
import com.example.superplayer.player.ShortcutsHelper
import com.example.superplayer.ui.MainActivity
import java.net.HttpURLConnection
import java.net.URL

/**
 * Widget de la pantalla de inicio con tus favoritos (hasta 6, en el orden
 * de "Ordenar favoritos"). Cada uno abre su canal directamente (mismo
 * mecanismo que los accesos directos del icono: el Intent lleva el canal
 * entero en un extra, ver ShortcutsHelper.EXTRA_SHORTCUT_STREAM_JSON).
 *
 * Los logos se descargan en un hilo aparte (goAsync) y se reducen para que
 * quepan en el límite de tamaño de las RemoteViews; si un logo falla, sale
 * el icono genérico. El "ahora" del canal solo aparece si la guía EPG ya
 * está cargada en ese momento (si la app no se ha abierto desde que se
 * encendió el móvil, saldrá solo el nombre). Se actualiza cada 30 minutos y
 * en cuanto cambian los favoritos (ver ShortcutsHelper.refresh).
 */
class FavoritesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                val favorites = FavoritesStore(context).getAllStreams().take(SLOT_COUNT)
                val logos = favorites.map { loadLogo(it.icon) }
                for (id in appWidgetIds) {
                    manager.updateAppWidget(id, buildViews(context, favorites, logos))
                }
            } catch (e: Exception) {
                // Un fallo aquí no debe tumbar nada: el widget se queda como estaba.
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun buildViews(context: Context, favorites: List<Stream>, logos: List<Bitmap?>): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_favorites)

        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widgetHeader, openApp)

        val empty = favorites.isEmpty()
        views.setViewVisibility(R.id.widgetEmpty, if (empty) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widgetRow1, if (empty) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widgetRow2, if (empty || favorites.size <= 3) View.GONE else View.VISIBLE)

        for (i in 0 until SLOT_COUNT) {
            val slotId = SLOT_IDS[i]
            if (i >= favorites.size) {
                views.setViewVisibility(slotId, View.INVISIBLE)
                continue
            }
            val stream = favorites[i]
            views.setViewVisibility(slotId, View.VISIBLE)
            views.setTextViewText(NAME_IDS[i], stream.name)
            val logo = logos.getOrNull(i)
            if (logo != null) views.setImageViewBitmap(LOGO_IDS[i], logo)
            else views.setImageViewResource(LOGO_IDS[i], R.drawable.ic_placeholder)

            val now = EpgRepository.currentTitle(stream.tvgId)
            if (now != null) {
                views.setTextViewText(NOW_IDS[i], now)
                views.setViewVisibility(NOW_IDS[i], View.VISIBLE)
            } else {
                views.setViewVisibility(NOW_IDS[i], View.GONE)
            }

            val play = Intent(context, PlayerActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(ShortcutsHelper.EXTRA_SHORTCUT_STREAM_JSON, stream.toJson())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            // requestCode distinto por hueco: los Intent solo difieren en extras, que PendingIntent ignora al compararlos.
            val pi = PendingIntent.getActivity(
                context, 100 + i, play, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(slotId, pi)
        }
        return views
    }

    /** Descarga un logo y lo reduce a ~128 px (las RemoteViews tienen un límite de memoria para imágenes). */
    private fun loadLogo(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            try {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size > MAX_LOGO_BYTES) return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val SLOT_COUNT = 6
        private const val TARGET_PX = 128
        private const val MAX_LOGO_BYTES = 2_000_000

        private val SLOT_IDS = intArrayOf(
            R.id.widgetSlot1, R.id.widgetSlot2, R.id.widgetSlot3,
            R.id.widgetSlot4, R.id.widgetSlot5, R.id.widgetSlot6
        )
        private val LOGO_IDS = intArrayOf(
            R.id.widgetLogo1, R.id.widgetLogo2, R.id.widgetLogo3,
            R.id.widgetLogo4, R.id.widgetLogo5, R.id.widgetLogo6
        )
        private val NAME_IDS = intArrayOf(
            R.id.widgetName1, R.id.widgetName2, R.id.widgetName3,
            R.id.widgetName4, R.id.widgetName5, R.id.widgetName6
        )
        private val NOW_IDS = intArrayOf(
            R.id.widgetNow1, R.id.widgetNow2, R.id.widgetNow3,
            R.id.widgetNow4, R.id.widgetNow5, R.id.widgetNow6
        )

        /** Pide a todos los widgets colocados que se repinten (favoritos cambiados o reordenados). */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FavoritesWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, FavoritesWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
