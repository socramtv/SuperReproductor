package com.example.superplayer.data

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/**
 * EPG (guía de programación) a partir de la URL que trae la propia lista
 * (ver el comentario de EPG en model/Playlist.kt para los campos
 * aceptados). Se descarga y parsea una sola vez por URL, en memoria,
 * mientras dure el proceso de la app, y expone "qué programa toca ahora"
 * por tvg-id.
 *
 * Se admiten dos formatos, detectados solos mirando el contenido ya
 * descomprimido (no la URL ni las cabeceras HTTP):
 * - XMLTV estándar (<programme channel="..." start="..." stop="..."><title>).
 * - JSON propio de listas públicas tipo tdtchannels.com: un array raíz de
 *   objetos { "name": "<id, coincide con epg_id/tvgId del canal>",
 *   "events": [ { "hi": <inicio unix en segundos>, "hf": <fin unix en
 *   segundos>, "t": "<título>" } ] }.
 * Ambos, además, pueden venir comprimidos en gzip (.gz): también se
 * detecta solo, por los propios bytes.
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
                // Muchos proveedores de EPG sirven un .xml.gz (o .json.gz) directo
                // sin avisar bien por cabeceras HTTP; se detecta por los propios
                // bytes (firma gzip 0x1f 0x8b), no por la URL ni por Content-Encoding.
                raw.mark(2)
                val b0 = raw.read()
                val b1 = raw.read()
                raw.reset()
                val isGzip = b0 == 0x1f && (b1 and 0xff) == 0x8b
                val decoded: InputStream = if (isGzip) GZIPInputStream(raw) else raw
                val buffered = if (decoded is BufferedInputStream) decoded else BufferedInputStream(decoded)

                // Además de XMLTV, listas públicas tipo tdtchannels.com sirven su
                // EPG en JSON propio; se distingue mirando el primer carácter no
                // vacío del contenido ya descomprimido, sin tocar la URL.
                return if (looksLikeJson(buffered)) {
                    parseJsonEpg(buffered)
                } else {
                    parseXmlTv(buffered)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Mira (sin consumirlos) los primeros bytes para distinguir JSON de XML. */
    private fun looksLikeJson(input: BufferedInputStream): Boolean {
        input.mark(128)
        try {
            for (i in 0 until 128) {
                val b = input.read()
                if (b == -1) return false
                val c = b.toChar()
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue
                return c == '{' || c == '['
            }
            return false
        } finally {
            input.reset()
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

    /**
     * EPG en JSON de listas públicas tipo tdtchannels.com: un array raíz de
     * objetos `{ "name": "<id>", "events": [ { "hi", "hf", "t" } ] }`, donde
     * "name" es el mismo id que "epg_id"/tvgId del canal en la lista, y
     * "hi"/"hf" son timestamps unix en SEGUNDOS (no milisegundos, a
     * diferencia de los epoch millis que usa el resto de esta clase).
     */
    private fun parseJsonEpg(input: InputStream): Map<String, List<Programme>> {
        val text = input.bufferedReader(Charsets.UTF_8).readText().trim()
        val result = LinkedHashMap<String, MutableList<Programme>>()
        if (!text.startsWith("[")) return result
        val channelsJson = JSONArray(text)

        for (i in 0 until channelsJson.length()) {
            val channelObj = channelsJson.optJSONObject(i) ?: continue
            val channelId = channelObj.optStringOrNull("name") ?: continue
            val eventsJson = channelObj.optJSONArray("events") ?: continue

            val programmes = ArrayList<Programme>(eventsJson.length())
            for (j in 0 until eventsJson.length()) {
                val eventObj = eventsJson.optJSONObject(j) ?: continue
                val title = eventObj.optStringOrNull("t") ?: continue
                val startMillis = eventObj.optLong("hi", -1L) * 1000L
                val stopMillis = eventObj.optLong("hf", -1L) * 1000L
                if (startMillis <= 0 || stopMillis <= startMillis) continue
                programmes.add(Programme(startMillis, stopMillis, title))
            }
            if (programmes.isNotEmpty()) {
                result.getOrPut(channelId) { mutableListOf() }.addAll(programmes)
            }
        }
        for (list in result.values) list.sortBy { it.startMillis }
        return result
    }

    /** Como JSONObject.optString, pero un valor JSON null explícito da null (no el texto "null"). */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}
