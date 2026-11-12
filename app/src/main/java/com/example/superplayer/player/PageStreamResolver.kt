package com.example.superplayer.player

import android.util.Base64
import com.example.superplayer.model.Stream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Muchos «canales» no son un stream directo sino una PÁGINA con un reproductor dentro
 * (p. ej. https://sitio/live1.php?stream=canal). ExoPlayer no sabe reproducir una página, así que antes de
 * empezar se abre esa página (y los iframes que lleve, hasta 2 niveles), se busca dentro la dirección real
 * (.m3u8 / .mpd, también escapada, en base64 o con \/ ) y se reproduce esa, con el Referer y el User-Agent de la página.
 */
object PageStreamResolver {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val PAGE_EXT = listOf(".php", ".html", ".htm", ".asp", ".aspx", ".jsp", ".shtml")
    private val MEDIA_URL = Regex("""https?://[^\s"'<>\\()\[\]{}]+?\.(?:m3u8|mpd)(?:\?[^\s"'<>\\()\[\]{}]*)?""", RegexOption.IGNORE_CASE)
    private val REL_URL = Regex("""["'`]((?:/|\.\./|\./)?[A-Za-z0-9_\-./%]+\.(?:m3u8|mpd)(?:\?[^"'`\s]*)?)["'`]""", RegexOption.IGNORE_CASE)
    private val IFRAME = Regex("""<iframe[^>]+?src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val B64 = Regex("""(?:atob\(\s*|["'])([A-Za-z0-9+/=]{40,})["']?""")

    /** ¿La dirección es una página web (y no un stream directo)? */
    fun needsResolve(stream: Stream): Boolean {
        val t = stream.type.lowercase()
        if (t.contains("hls") || t.contains("dash") || t.contains("m3u8") || t.contains("mpd") || t.contains("radio") || t.contains("audio")) return false
        val u = stream.url
        if (!u.startsWith("http", ignoreCase = true)) return false
        val path = u.substringBefore('?').substringBefore('#').lowercase()
        return PAGE_EXT.any { path.endsWith(it) }
    }

    class Result(val url: String, val referer: String)

    fun resolve(pageUrl: String, headers: Map<String, String>): Result? {
        val visited = HashSet<String>()
        return find(pageUrl, headers["Referer"] ?: headers["referer"], 0, visited)
    }

    private fun find(pageUrl: String, referer: String?, depth: Int, visited: HashSet<String>): Result? {
        if (depth > 2 || !visited.add(pageUrl)) return null
        val body = fetch(pageUrl, referer) ?: return null
        val text = normalize(body)
        val found = candidates(text, pageUrl)
        if (found.isNotEmpty()) return Result(found.first(), origin(pageUrl))
        for (m in IFRAME.findAll(text)) {
            val child = absolute(pageUrl, m.groupValues[1]) ?: continue
            if (!child.startsWith("http")) continue
            find(child, origin(pageUrl), depth + 1, visited)?.let { return it }
        }
        return null
    }

    private fun candidates(text: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in MEDIA_URL.findAll(text)) out.add(m.value)
        if (out.isEmpty()) {
            for (m in REL_URL.findAll(text)) absolute(base, m.groupValues[1])?.let { out.add(it) }
        }
        if (out.isEmpty()) {
            // Cadenas en base64 (atob("...")) que escondan una dirección
            for (m in B64.findAll(text)) {
                val dec = try { String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { continue }
                for (x in MEDIA_URL.findAll(normalize(dec))) out.add(x.value)
            }
        }
        return out.toList()
    }

    private fun normalize(s: String): String =
        s.replace("\\/", "/").replace("&amp;", "&").replace("\\u0026", "&").replace("\\u002F", "/").replace("\\x2F", "/")

    private fun origin(u: String): String = try {
        val x = URI(u); x.scheme + "://" + x.authority + "/"
    } catch (e: Exception) { u }

    private fun absolute(base: String, ref: String): String? = try {
        when {
            ref.startsWith("//") -> URI(base).scheme + ":" + ref
            else -> URL(URL(base), ref).toString()
        }
    } catch (e: Exception) { null }

    private fun fetch(url: String, referer: String?): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 12_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
        if (c.responseCode !in 200..299) null
        else c.inputStream.use { ins ->
            val buf = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(16 * 1024)
            while (buf.size() < 1_500_000) {
                val n = ins.read(tmp)
                if (n < 0) break
                buf.write(tmp, 0, n)
            }
            buf.toString("UTF-8")
        }
    } catch (e: Exception) { null }

    /** Stream listo para ExoPlayer a partir de lo encontrado (Referer = sitio de la página, mismo User-Agent). */
    fun toStream(orig: Stream, r: Result): Stream {
        val low = r.url.substringBefore('?').lowercase()
        return orig.copy(
            url = r.url,
            type = if (low.endsWith(".mpd")) "DASH" else "HLS",
            headers = mapOf("Referer" to r.referer, "User-Agent" to UA)
        )
    }
}
