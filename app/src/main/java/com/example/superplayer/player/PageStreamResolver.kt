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
    const val UA =
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
        if (PAGE_EXT.any { path.endsWith(it) }) return true
        // Páginas «embed» sin extensión (p. ej. https://emb.sitio/p2p/?id=268668): reproductor por JavaScript.
        val last = path.substringAfterLast('/')
        return !last.contains('.') && EMBED_HINTS.any { path.contains(it) }
    }

    private val EMBED_HINTS = listOf("/embed", "/p2p/", "/player", "/iframe", "/watch/", "/live/embed", "/stream.php", "/api/player/")

    /** [referer] vacío = sin Referer propio. [type]: "HLS", "DASH" o "PROGRESSIVE" si ya se sabe (p. ej. el script devuelve directamente el vídeo). */
    class Result(val url: String, val referer: String, val ua: String? = null, val type: String? = null)

    private fun header(h: Map<String, String>, name: String): String? =
        h.entries.firstOrNull { it.key.equals(name, true) }?.value?.takeIf { it.isNotBlank() }

    fun resolve(pageUrl: String, headers: Map<String, String>): Result? {
        val visited = HashSet<String>()
        return find(pageUrl, header(headers, "Referer"), header(headers, "User-Agent") ?: UA, 0, visited)
    }

    private fun find(pageUrl: String, referer: String?, ua: String, depth: Int, visited: HashSet<String>): Result? {
        if (depth > 2 || !visited.add(pageUrl)) return null
        val probe = probe(pageUrl, referer, ua) ?: return null
        // Muchos .php no son una página sino un script que devuelve directamente el vídeo (TS de ffmpeg, lista m3u8…).
        if (probe.direct != null) return Result(probe.finalUrl, "", ua, probe.direct)
        val text = normalize(probe.body ?: return null)
        val found = candidates(text, pageUrl)
        if (found.isNotEmpty()) return Result(found.first(), origin(pageUrl), ua)
        for (m in IFRAME.findAll(text)) {
            val child = absolute(pageUrl, m.groupValues[1]) ?: continue
            if (!child.startsWith("http")) continue
            find(child, origin(pageUrl), ua, depth + 1, visited)?.let { return it }
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

    private class Probe(val direct: String?, val body: String?, val finalUrl: String)

    /** Pide la dirección y mira qué es: vídeo directo (TS/audio/vídeo), lista HLS o una página de texto. */
    private fun probe(url: String, referer: String?, ua: String): Probe? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
            if (c.responseCode !in 200..299) return null
            val finalUrl = c.url.toString()
            val ct = (c.contentType ?: "").lowercase()
            c.inputStream.use { ins ->
                val head = ByteArray(4096)
                var n = 0
                while (n < head.size) {
                    val r = ins.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n <= 0) return null
                val start = String(head, 0, minOf(n, 16), Charsets.ISO_8859_1)
                val isTs = n > 188 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte()
                when {
                    start.startsWith("#EXTM3U") -> return Probe("HLS", null, finalUrl)
                    isTs -> return Probe("PROGRESSIVE", null, finalUrl)
                    ct.contains("mpegurl") -> return Probe("HLS", null, finalUrl)
                    ct.startsWith("video/") || ct.startsWith("audio/") -> return Probe("PROGRESSIVE", null, finalUrl)
                    ct.contains("octet-stream") && head[0] != '<'.code.toByte() && head[0] != ' '.code.toByte() -> return Probe("PROGRESSIVE", null, finalUrl)
                }
                val buf = java.io.ByteArrayOutputStream()
                buf.write(head, 0, n)
                val tmp = ByteArray(16 * 1024)
                while (buf.size() < 1_500_000) {
                    val r = ins.read(tmp)
                    if (r < 0) break
                    buf.write(tmp, 0, r)
                }
                Probe(null, buf.toString("UTF-8"), finalUrl)
            }
        } catch (e: Exception) {
            null
        } finally {
            try { c?.disconnect() } catch (e: Exception) { }
        }
    }

    /** Stream listo para ExoPlayer a partir de lo encontrado (Referer = sitio de la página, mismo User-Agent). */
    fun toStream(orig: Stream, r: Result): Stream {
        val low = r.url.substringBefore('?').lowercase()
        val hdrs = LinkedHashMap<String, String>()
        (header(orig.headers, "Referer") ?: r.referer.takeIf { it.isNotBlank() })?.let { hdrs["Referer"] = it }
        hdrs["User-Agent"] = header(orig.headers, "User-Agent") ?: r.ua ?: UA
        return orig.copy(
            url = r.url,
            type = r.type ?: if (low.endsWith(".mpd")) "DASH" else "HLS",
            headers = hdrs
        )
    }
}
