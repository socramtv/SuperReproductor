package com.example.superplayer.data

import android.content.Context

/** Favoritos guardados localmente en SharedPreferences (identificados por URL del stream). */
class FavoritesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun isFavorite(streamId: String): Boolean =
        prefs.getStringSet(KEY_IDS, emptySet())?.contains(streamId) == true

    /** Cambia el estado de favorito y devuelve el nuevo valor (true = ahora es favorito). */
    fun toggle(streamId: String): Boolean {
        val current = HashSet(prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet())
        val nowFavorite = if (current.contains(streamId)) {
            current.remove(streamId)
            false
        } else {
            current.add(streamId)
            true
        }
        prefs.edit().putStringSet(KEY_IDS, current).apply()
        return nowFavorite
    }

    fun getAll(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()

    companion object {
        private const val KEY_IDS = "favorite_ids"
    }
}

/** Recuerda cuál fue el último JSON de playlist cargado, para reabrirlo al iniciar la app. */
object AppPrefs {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_LAST_URI = "last_playlist_uri"

    fun saveLastPlaylistUri(context: Context, uriString: String) {
        prefs(context).edit().putString(KEY_LAST_URI, uriString).apply()
    }

    fun getLastPlaylistUri(context: Context): String? =
        prefs(context).getString(KEY_LAST_URI, null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
