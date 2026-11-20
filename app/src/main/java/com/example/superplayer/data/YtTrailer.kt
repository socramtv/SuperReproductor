package com.example.superplayer.data

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

/**
 * Saca de YouTube la dirección directa de un tráiler (con NewPipeExtractor), para
 * reproducirlo en ExoPlayer sin WebView. Devuelve el vídeo y, si no viene con audio
 * incluido, el audio aparte.
 */
object YtTrailer {
    class Result(val video: String, val audio: String?)

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

    private class Dl : Downloader() {
        private val http = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

        override fun execute(request: Request): Response {
            val b = okhttp3.Request.Builder().url(request.url())
            val method = request.httpMethod()
            val data = request.dataToSend()
            val body = when {
                data != null -> data.toRequestBody(null)
                method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody("text/plain".toMediaTypeOrNull())
                else -> null
            }
            b.method(method, body)
            b.header("User-Agent", UA)
            request.headers().forEach { (k, vs) ->
                b.removeHeader(k)
                vs.forEach { b.addHeader(k, it) }
            }
            http.newCall(b.build()).execute().use { r ->
                if (r.code == 429) throw ReCaptchaException("429", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }

    @Volatile private var ready = false
    private val cache = HashMap<String, Result>()

    /** Bloquea: llamar siempre en un hilo secundario. Devuelve null si no se pudo. */
    fun resolve(key: String): Result? {
        synchronized(cache) { cache[key]?.let { return it } }
        return try {
            if (!ready) {
                synchronized(this) {
                    if (!ready) { NewPipe.init(Dl(), Localization("es", "ES"), ContentCountry("ES")); ready = true }
                }
            }
            val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$key")
            // 1) vídeo con audio incluido, el de mayor resolución
            val muxed = info.videoStreams.filter { it.isUrl && !it.content.isNullOrBlank() }
                .maxByOrNull { it.height }
            val res = if (muxed != null) Result(muxed.content, null) else {
                val v = info.videoOnlyStreams.filter { it.isUrl && it.height in 1..720 }.maxByOrNull { it.height }
                val a = info.audioStreams.filter { it.isUrl }.maxByOrNull { it.averageBitrate }
                if (v != null && a != null) Result(v.content, a.content) else null
            }
            if (res != null) synchronized(cache) { cache[key] = res }
            res
        } catch (e: Throwable) {
            lastError = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(90)
            null
        }
    }

    @Volatile var lastError: String = ""
}
