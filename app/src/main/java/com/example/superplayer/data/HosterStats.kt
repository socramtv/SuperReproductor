package com.example.superplayer.data

import android.content.Context
import java.net.URI

/**
 * Qué servidores de vídeo (uqload, streamtape…) suelen funcionar en este móvil: se cuenta cada vez que uno da vídeo
 * o falla, y en las fichas se ponen primero los que mejor van.
 */
object HosterStats {
    private const val PREFS = "hoster_stats"

    /** «uqload» de https://uqload.is/embed-x.html */
    fun key(url: String): String? {
        val h = try { URI(url).host?.lowercase() } catch (e: Exception) { null } ?: return null
        val p = h.split('.')
        return if (p.size >= 2) p[p.size - 2] else h
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun get(c: Context, k: String): Pair<Int, Int> {
        val v = prefs(c).getString(k, "0,0") ?: "0,0"
        val a = v.split(',')
        return (a.getOrNull(0)?.toIntOrNull() ?: 0) to (a.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun put(c: Context, k: String, ok: Int, bad: Int) {
        // Se «olvida» poco a poco para que un fallo antiguo no condene a un servidor para siempre.
        val (o, b) = if (ok + bad > 40) (ok / 2) to (bad / 2) else ok to bad
        prefs(c).edit().putString(k, "$o,$b").apply()
    }

    fun good(c: Context, url: String) { val k = key(url) ?: return; val (o, b) = get(c, k); put(c, k, o + 1, b) }
    fun bad(c: Context, url: String) { val k = key(url) ?: return; val (o, b) = get(c, k); put(c, k, o, b + 1) }

    /** Más alto = mejor. */
    fun score(c: Context, url: String): Int { val k = key(url) ?: return 0; val (o, b) = get(c, k); return o * 2 - b }
}
