package com.example.superplayer.telegram

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Servidor HTTP local (solo 127.0.0.1) que sirve un archivo de Telegram con
 * soporte de Range pidiéndole a TDLib solo los trozos que el reproductor
 * necesita: así se reproduce sin descargar el archivo entero antes.
 * URL: http://127.0.0.1:PUERTO/tg/<fileId>?s=<tamaño>&m=<mime>
 */
object TgStreamServer {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var backend: TgAccountBackend? = null
    /** Última conexión abierta para cada archivo: las anteriores se abandonan (si no, pelean entre sí por la descarga y el vídeo se queda "conectando"). */
    private val generations = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicLong>()

    /** Aviso de diagnóstico cuando no llegan datos de Telegram (lo muestra la pantalla de Telegram). */
    @Volatile var onProblem: ((String) -> Unit)? = null

    fun reportProblem(msg: String) {
        try { onProblem?.invoke(msg) } catch (e: Exception) { }
    }

    /** Arranca el servidor si hace falta y devuelve la URL de reproducción. */
    @Synchronized
    fun urlFor(b: TgAccountBackend, fileId: Int, size: Long, mime: String?): String {
        backend = b
        val srv = server ?: ServerSocket(0, 20, InetAddress.getByName("127.0.0.1")).also {
            server = it
            Thread { acceptLoop(it) }.apply { isDaemon = true }.start()
        }
        val m = java.net.URLEncoder.encode(mime ?: "video/mp4", "UTF-8")
        return "http://127.0.0.1:${srv.localPort}/tg/$fileId?s=$size&m=$m"
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (!srv.isClosed) {
            val socket = try { srv.accept() } catch (e: Exception) { return }
            pool.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 60_000
                val input = s.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return
                var rangeHeader: String? = null
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
                }
                val target = requestLine.split(" ").getOrNull(1) ?: return
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "")
                val params = query.split('&').filter { it.contains('=') }.associate {
                    it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }
                val fileId = path.removePrefix("/tg/").toIntOrNull()
                val total = params["s"]?.toLongOrNull() ?: 0L
                val b = backend
                val out = s.getOutputStream()
                if (fileId == null || total <= 0 || b == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val mime = params["m"] ?: "video/mp4"
                val counter = generations.getOrPut(fileId) { java.util.concurrent.atomic.AtomicLong() }
                val gen = counter.incrementAndGet()
                val cancelled = { counter.get() != gen }

                var start = 0L
                var end = total - 1
                var partial = false
                val m = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader.orEmpty())
                if (m != null) {
                    partial = true
                    val a = m.groupValues[1]
                    val z = m.groupValues[2]
                    if (a.isEmpty() && z.isNotEmpty()) {
                        start = (total - z.toLong()).coerceAtLeast(0)
                    } else {
                        start = a.toLongOrNull() ?: 0L
                        if (z.isNotEmpty()) end = minOf(z.toLong(), total - 1)
                    }
                }
                if (start >= total) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val length = end - start + 1
                val head = StringBuilder()
                head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                head.append("Content-Type: $mime\r\n")
                head.append("Accept-Ranges: bytes\r\n")
                if (partial) head.append("Content-Range: bytes $start-$end/$total\r\n")
                head.append("Content-Length: $length\r\n")
                head.append("Connection: close\r\n\r\n")
                out.write(head.toString().toByteArray())
                out.flush()
                copyRange(b, fileId, start, length, out, cancelled)
            } catch (e: Exception) {
                // El reproductor cerró la conexión (saltar, parar...): normal.
            }
        }
    }

    private fun copyRange(b: TgAccountBackend, fileId: Int, start: Long, length: Long, out: OutputStream, cancelled: () -> Boolean) {
        var pos = start
        var remaining = length
        while (remaining > 0 && !cancelled()) {
            val chunk = b.readBytes(fileId, pos, minOf(remaining, 512L * 1024L).toInt(), cancelled) ?: return
            if (chunk.isEmpty()) return
            out.write(chunk)
            pos += chunk.size
            remaining -= chunk.size
        }
        out.flush()
    }
}
