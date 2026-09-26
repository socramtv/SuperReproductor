package com.example.superplayer.data

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * EPG (guía de programación) a partir de la URL XMLTV que trae la propia
 * lista (ver el comentario de EPG en model/Playlist.kt para los campos
 * aceptados). Se descarga y parsea una sola vez por URL, en memoria,
 * mientras dure el proceso de la app, y expone "qué programa toca ahora"
 * por tvg-id.
 *
 * No hace falta que la lista traiga EPG: si no hay URL, o si falla la
 * descarga/parseo (formato inesperado, servidor caído...), la app sigue
 * funcionando exactamente igual, solo sin ese dato extra — los errores se
 * ignoran a propósito en vez de interrumpir nada.
 */
object EpgRepository {

    private data class Programme(val startMillis: Long, val stopMillis: Long, val title: String)

    @Volatile private var loadedUrl: String? = null
    @Volatile private var loading = false
    @Volatile private var byChannel: Map<String, List<Programme>> = emptyMap()

    /**
     * Lanza la descarga/parseo en un hilo aparte si `epgUrl` no es nulo/vacío
     * y no es ya la URL que tenemos cargada (o en curso). Se puede llamar
     * cada vez que se carga una lista sin problema: si es la misma URL de
     * siempre, no vuelve a descargar nada.
     */
    fun load(epgUrl: String?) {
        val url = epgUrl?.trim()
        if (url == null || url.isEmpty() || url == loadedUrl || loading) return
        loading = true
        Thread {
            try {
                val parsed = fetchAndParse(url)
                byChannel = parsed
                loadedUrl = url
            } catch (e: Exception) {
                // Sin EPG para esta URL; el resto de la app sigue igual.
            } finally {
                loading = false
            }
        }.start()
    }

    /** Título del programa que está en emisión ahora mismo para ese tvg-id, o null si no hay dato. */
    fun currentTitle(tvgId: String?, nowMillis: Long = System.currentTimeMillis()): String? {
        if (tvgId.isNullOrBlank()) return null
        val list = byChannel[tvgId] ?: return null
        for (p in list) {
            if (nowMillis >= p.startMillis && nowMillis < p.stopMillis) return p.title
        }
        return null
    }

    private fun fetchAndParse(urlString: String): Map<String, List<Programme>> {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        try {
            connection.connect()
            BufferedInputStream(connection.inputStream).use { raw ->
                // Muchos proveedores de EPG sirven un .xml.gz directo sin avisar
                // bien por cabeceras HTTP; se detecta por los propios bytes
                // (firma gzip 0x1f 0x8b), no por la URL ni por Content-Encoding.
                raw.mark(2)
                val b0 = raw.read()
                val b1 = raw.read()
                raw.reset()
                val isGzip = b0 == 0x1f && (b1 and 0xff) == 0x8b
                val input: InputStream = if (isGzip) GZIPInputStream(raw) else raw
                return parseXmlTv(input)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Parseo en streaming (XmlPullParser): no carga el XML entero en memoria, solo lo que nos interesa. */
    private fun parseXmlTv(input: InputStream): Map<String, List<Programme>> {
        val result = LinkedHashMap<String, MutableList<Programme>>()

        val parser: XmlPullParser = android.util.Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var inProgramme = false
        var inTitle = false
        var channel: String? = null
        var start = -1L
        var stop = -1L
        var title: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        inProgramme = true
                        channel = parser.getAttributeValue(null, "channel")
                        start = parseXmlTvDate(parser.getAttributeValue(null, "start"))
                        stop = parseXmlTvDate(parser.getAttributeValue(null, "stop"))
                        title = null
                    }
                    "title" -> if (inProgramme) inTitle = true
                }
                XmlPullParser.TEXT -> if (inProgramme && inTitle && title == null) {
                    val text = parser.text?.trim()
                    if (!text.isNullOrBlank()) title = text
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> inTitle = false
                    "programme" -> {
                        val ch = channel
                        val t = title
                        if (!ch.isNullOrBlank() && !t.isNullOrBlank() && start > 0 && stop > start) {
                            result.getOrPut(ch) { mutableListOf() }.add(Programme(start, stop, t))
                        }
                        inProgramme = false
                        inTitle = false
                        channel = null
                        title = null
                        start = -1L
                        stop = -1L
                    }
                }
            }
            event = parser.next()
        }

        for (list in result.values) list.sortBy { it.startMillis }
        return result
    }

    /** XMLTV: "20260926140000 +0200" (a veces sin el offset). Devuelve epoch millis, o -1 si no se puede leer. */
    private fun parseXmlTvDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return -1L
        val value = raw.trim()
        return try {
            if (value.length > 14) {
                SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(value)?.time ?: -1L
            } else {
                val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(value)?.time ?: -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }
}
