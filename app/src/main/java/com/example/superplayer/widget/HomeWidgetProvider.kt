package com.example.superplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.example.superplayer.R
import com.example.superplayer.data.Profiles
import com.example.superplayer.ui.MainActivity

/**
 * Widget "Socram TV+" completo, de tamaño ajustable: una lista que se
 * desplaza con tres pestañas -⭐ Favoritos, ▶ Continuar viendo y ⚽ Partidos-
 * más un botón de refrescar. El contenido lo pone HomeWidgetService. La
 * pestaña elegida se recuerda (la misma para todos los widgets puestos).
 * El widget sencillo de 6 favoritos (FavoritesWidgetProvider) sigue ahí.
 */
class HomeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            manager.updateAppWidget(id, buildViews(context, id))
            manager.notifyAppWidgetViewDataChanged(id, R.id.widgetList)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_MODE -> {
                setMode(context, intent.getIntExtra(EXTRA_MODE, MODE_FAVORITES))
                refreshAll(context)
            }
            ACTION_REFRESH -> refreshAll(context)
            else -> super.onReceive(context, intent)
        }
    }

    private fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_home)
        val mode = getMode(context)

        val serviceIntent = Intent(context, HomeWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widgetList, serviceIntent)
        views.setEmptyView(R.id.widgetList, R.id.widgetListEmpty)
        views.setTextViewText(
            R.id.widgetListEmpty,
            context.getString(
                when (mode) {
                    MODE_CONTINUE -> R.string.widget_home_empty_continue
                    MODE_MATCHES -> R.string.widget_home_empty_matches
                    else -> R.string.widget_empty
                }
            )
        )

        val profile = Profiles.active(context)
        val title = if (Profiles.isDefaultActive(context)) context.getString(R.string.app_widget_title)
        else context.getString(R.string.app_widget_title_profile, profile.name)
        views.setTextViewText(R.id.widgetHomeTitle, title)

        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widgetHomeTitle, openApp)

        // Una sola plantilla para todas las filas: WidgetClickActivity decide qué abrir según lo que traiga cada fila.
        val template = PendingIntent.getActivity(
            context, 1, Intent(context, WidgetClickActivity::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setPendingIntentTemplate(R.id.widgetList, template)

        val tabs = listOf(
            Triple(R.id.widgetTabFav, MODE_FAVORITES, 10),
            Triple(R.id.widgetTabContinue, MODE_CONTINUE, 11),
            Triple(R.id.widgetTabMatches, MODE_MATCHES, 12)
        )
        for ((viewId, tabMode, code) in tabs) {
            val intent = Intent(context, HomeWidgetProvider::class.java).apply {
                action = ACTION_SET_MODE
                putExtra(EXTRA_MODE, tabMode)
            }
            views.setOnClickPendingIntent(
                viewId,
                PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            views.setInt(
                viewId, "setBackgroundResource",
                if (tabMode == mode) R.drawable.bg_widget_tab_active else 0
            )
            views.setTextColor(viewId, if (tabMode == mode) 0xFFFFFFFF.toInt() else 0xFFB0B0BC.toInt())
        }

        val refresh = Intent(context, HomeWidgetProvider::class.java).apply { action = ACTION_REFRESH }
        views.setOnClickPendingIntent(
            R.id.widgetRefresh,
            PendingIntent.getBroadcast(context, 13, refresh, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        )
        return views
    }

    companion object {
        const val MODE_FAVORITES = 0
        const val MODE_CONTINUE = 1
        const val MODE_MATCHES = 2
        private const val ACTION_SET_MODE = "com.example.superplayer.widget.SET_MODE"
        private const val ACTION_REFRESH = "com.example.superplayer.widget.REFRESH"
        private const val EXTRA_MODE = "mode"
        private const val PREFS = "home_widget"

        fun getMode(context: Context): Int =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("mode", MODE_FAVORITES)

        private fun setMode(context: Context, mode: Int) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("mode", mode).apply()
        }

        /** Repinta todos los widgets de este tipo puestos (pestaña cambiada, favoritos o perfil cambiados). */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HomeWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, HomeWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
