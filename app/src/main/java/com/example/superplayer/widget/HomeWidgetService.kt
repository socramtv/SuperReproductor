package com.example.superplayer.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.superplayer.R
import com.example.superplayer.data.ContinueWatching
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.data.FavoritesStore
import com.example.superplayer.model.toJson
import com.example.superplayer.sports.GoalAlerts
import com.example.superplayer.sports.MatchFeed
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lista desplazable del widget "Socram TV+" (ver HomeWidgetProvider): según
 * la pestaña elegida enseña tus favoritos (con lo que echan ahora y su
 * barra de progreso), "Continuar viendo" (con por dónde vas) o los partidos
 * de fútbol de hoy (marcador y minuto). Cada fila, al tocarla, pasa por
 * WidgetClickActivity.
 */
class HomeWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory = Factory(applicationContext)

    private data class Row(
        val title: String,
        val sub: String,
        val logoUrl: String?,
        /** 0..100, o -1 si no hay barra. */
        val progress: Int,
        val streamJson: String? = null,
        val home: String? = null,
        val away: String? = null
    )

    private class Factory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
        private var rows: List<Row> = emptyList()
        private val logoCache = object : LinkedHashMap<String, Bitmap?>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?): Boolean = size > 80
        }

        override fun onCreate() {}

        override fun onDataSetChanged() {
            rows = try {
                when (HomeWidgetProvider.getMode(context)) {
                    HomeWidgetProvider.MODE_CONTINUE -> continueRows()
                    HomeWidgetProvider.MODE_MATCHES -> matchRows()
                    else -> favoriteRows()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun onDestroy() {
            rows = emptyList()
            logoCache.clear()
        }

        override fun getCount(): Int = rows.size

        override fun getViewAt(position: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_home_item)
            val row = rows.getOrNull(position) ?: return views
            views.setTextViewText(R.id.widgetItemTitle, row.title)
            views.setTextViewText(R.id.widgetItemSub, row.sub)
            views.setViewVisibility(R.id.widgetItemSub, if (row.sub.isBlank()) View.GONE else View.VISIBLE)
            if (row.progress in 0..100) {
                views.setViewVisibility(R.id.widgetItemProgress, View.VISIBLE)
                views.setProgressBar(R.id.widgetItemProgress, 100, row.progress, false)
            } else {
                views.setViewVisibility(R.id.widgetItemProgress, View.GONE)
            }
            val logo = loadLogoCached(row.logoUrl)
            if (logo != null) views.setImageViewBitmap(R.id.widgetItemLogo, logo)
            else views.setImageViewResource(R.id.widgetItemLogo, R.drawable.ic_placeholder)

            val fill = Intent()
            if (row.streamJson != null) fill.putExtra(WidgetClickActivity.EXTRA_STREAM_JSON, row.streamJson)
            if (row.home != null && row.away != null) {
                fill.putExtra(GoalAlerts.EXTRA_HOME, row.home)
                fill.putExtra(GoalAlerts.EXTRA_AWAY, row.away)
            }
            views.setOnClickFillInIntent(R.id.widgetItemRoot, fill)
            return views
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false

        // ---- Datos de cada pestaña ----

        private fun favoriteRows(): List<Row> {
            return FavoritesStore(context).getAllStreams().map { stream ->
                val now = EpgRepository.schedule(stream.tvgId).now
                var sub = ""
                var progress = -1
                if (now != null) {
                    sub = now.title
                    val total = now.stopMillis - now.startMillis
                    if (total > 0) {
                        progress = ((System.currentTimeMillis() - now.startMillis) * 100 / total).toInt().coerceIn(0, 100)
                    }
                }
                Row(stream.name, sub, stream.icon, progress, streamJson = stream.toJson())
            }
        }

        private fun continueRows(): List<Row> {
            return ContinueWatching.getAll(context).map { entry ->
                val progress = if (entry.durationMs > 0) {
                    (entry.positionMs * 100 / entry.durationMs).toInt().coerceIn(0, 100)
                } else -1
                val sub = context.getString(
                    R.string.continue_progress,
                    ContinueWatching.formatClock(entry.positionMs),
                    ContinueWatching.formatClock(entry.durationMs)
                )
                Row(entry.stream.name, sub, entry.stream.icon, progress, streamJson = entry.stream.toJson())
            }
        }

        private fun matchRows(): List<Row> {
            val result = MatchFeed.fetchAll()
            val order = mapOf("in" to 0, "pre" to 1, "post" to 2)
            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            return result.matches
                .sortedWith(compareBy({ order[it.state] ?: 3 }, { it.startMillis }))
                .take(25)
                .map { m ->
                    val title = if (m.state == "pre") "${m.home} - ${m.away}"
                    else "${m.home} ${m.homeScore} - ${m.awayScore} ${m.away}"
                    val status = when (m.state) {
                        "in" -> context.getString(R.string.match_live, m.clock.ifBlank { "·" })
                        "post" -> context.getString(R.string.match_final)
                        else -> context.getString(R.string.match_upcoming, timeFmt.format(Date(m.startMillis)))
                    }
                    Row(title, "$status · ${m.league}", m.homeLogo, -1, home = m.home, away = m.away)
                }
        }

        // ---- Logos ----

        private fun loadLogoCached(url: String?): Bitmap? {
            if (url.isNullOrBlank()) return null
            if (logoCache.containsKey(url)) return logoCache[url]
            val bitmap = loadLogo(url)
            logoCache[url] = bitmap
            return bitmap
        }

        /** Descarga un logo y lo reduce a ~96 px (las RemoteViews tienen un límite de memoria para imágenes). */
        private fun loadLogo(url: String): Bitmap? {
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                try {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size > 2_000_000) return null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 96 && bounds.outHeight / (sample * 2) >= 96) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
