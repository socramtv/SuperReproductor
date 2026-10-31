package com.example.superplayer.ui

import android.content.Context
import android.content.Intent
import com.example.superplayer.model.PlaylistData

/**
 * Listas (m3u, m3u8, json, w3u, txt) que llegan por Telegram y se cargan como lista principal:
 * la pantalla de Telegram deja aquí la lista ya leída y abre MainActivity, que la recoge en onResume.
 */
object TelegramImport {
    @Volatile
    var pending: PlaylistData? = null

    fun looksLikeListName(name: String?): Boolean {
        val n = (name ?: "").lowercase()
        return n.endsWith(".m3u") || n.endsWith(".m3u8") || n.endsWith(".json") || n.endsWith(".txt") || n.endsWith(".w3u")
    }

    fun looksLikeListMime(mime: String?): Boolean {
        val m = (mime ?: "").lowercase()
        return m.contains("mpegurl") || m.contains("json") || m == "text/plain"
    }

    /** Enlaces que apuntan a una lista (no a un canal suelto): extensión de lista o get.php de IPTV. */
    fun looksLikeListUrl(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u") || path.endsWith(".json") || path.endsWith(".txt") || path.endsWith(".w3u") ||
            lower.contains("get.php") || lower.contains("type=m3u") || lower.contains("output=m3u")
    }

    fun openInMain(context: Context, data: PlaylistData) {
        pending = data
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }
}
