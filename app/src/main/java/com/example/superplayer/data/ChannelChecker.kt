package com.example.superplayer.data

import com.example.superplayer.model.Stream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprobador de canales caídos: pide la URL de cada canal (con sus
 * cabeceras propias) y mira si el servidor contesta bien. Solo lee los
 * primeros bytes y corta, así que no gasta datos aunque sea un directo.
 *
 * No es infalible: un servidor que exige algo especial puede dar un falso
 * "caído", por eso la pantalla enseña la lista y deja desmarcar antes de
 * ocultar nada. Los canales con token ({token}) no se pueden comprobar
 * desde fuera y se dan por buenos.
 */
object ChannelChecker {

    class Job {
        internal val cancelled = AtomicBoolean(false)
        fun cancel() { cancelled.set(true) }
    }

    private const val TIMEOUT_MS = 7_000
    private const val PARALLEL = 8

    /**
     * Comprueba [streams] en segundo plano. [onProgress] (hechos, total) y
     * [onDone] (los caídos) se llaman desde hilos de fondo: la pantalla debe
     * pasarlos al hilo principal.
     */
    fun check(
        streams: List<Stream>,
        onProgress: (Int, Int) -> Unit,
        onDone: (List<Stream>) -> Unit
    ): Job {
        val job = Job()
        val total = streams.size
        if (total == 0) {
            onDone(emptyList())
            return job
        }
        val done = AtomicInteger(0)
        val down = java.util.Collections.synchronizedList(ArrayList<Stream>())
        val pool = Executors.newFixedThreadPool(PARALLEL)
        for (stream in streams) {
            pool.execute {
                if (!job.cancelled.get()) {
                    if (!isAlive(stream)) down.add(stream)
                }
                val n = done.incrementAndGet()
                if (!job.cancelled.get()) onProgress(n, total)
                if (n == total) {
                    pool.shutdown()
                    if (!job.cancelled.get()) onDone(streams.filter { it in down })
                }
            }
        }
        return job
    }

    private fun isAlive(stream: Stream): Boolean {
        if (stream.tokenUrl != null || stream.url.contains("{token}")) return true
        val url = stream.url.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return true
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            for ((k, v) in stream.headers) connection.setRequestProperty(k, v)
            val code = connection.responseCode
            if (code !in 200..299) return false
            // Que llegue al menos un byte: hay servidores que contestan 200 y se quedan mudos.
            // (Sin cerrar el stream aparte: disconnect() de abajo corta la conexión sin esperar al resto del directo.)
            connection.inputStream.read() != -1
        } catch (e: Exception) {
            false
        } finally {
            try { connection?.disconnect() } catch (e: Exception) { }
        }
    }
}
