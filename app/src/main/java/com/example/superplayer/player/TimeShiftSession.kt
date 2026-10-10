package com.example.superplayer.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.example.superplayer.model.Stream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Pausa en directo: mientras se ve un canal, un hilo descarga el stream
 * (TS directo o HLS con segmentos TS) a un buffer en disco de los últimos N
 * minutos, y el reproductor ve ese buffer a través de un mini servidor HTTP
 * local (127.0.0.1). Así se puede pausar, retroceder y volver al directo
 * aunque el servidor del canal no ofrezca nada de eso.
 *
 * - Pausar: el reproductor se queda parado y el buffer sigue creciendo.
 * - Retroceder / adelantar: se reinicia la reproducción desde otro punto del
 *   buffer (el servidor local sirve desde el desplazamiento que pida la URL).
 * - Directo: se reinicia desde el final del buffer.
 *
 * El "tiempo" del buffer es tiempo de contenido: en un TS directo es el
 * reloj; en HLS es la suma de las duraciones (EXTINF) de los segmentos.
 *
 * Límites (en ese caso el canal se reproduce normal, sin pausa en directo):
 * DRM, canales con token, DASH, vídeos con extensión de archivo, HLS cifrado
 * o con segmentos fMP4, y contenido que no sea MPEG-TS.
 */
class TimeShiftSession(
    context: Context,
    private val stream: Stream,
    windowMinutes: Int
) {
    private class Chunk(val idx: Int, val startTimeMs: Long)

    private val windowMs = windowMinutes * 60_000L
    private val dir = File(context.applicationContext.cacheDir, "timeshift/${System.nanoTime()}")
    private val t0 = SystemClock.elapsedRealtime()

    private val lock = ReentrantLock()
    private val dataCond = lock.newCondition()

    // Estado compartido (siempre bajo [lock]).
    private var writePos = 0L
    private var tEnd = 0L
    private val chunks = ArrayList<Chunk>()
    private val sampleOffsets = ArrayList<Long>().apply { add(0L) }
    private val sampleTimes = ArrayList<Long>().apply { add(0L) }

    // Solo las toca el hilo que descarga.
    private var wPos = 0L
    private var curRaf: RandomAccessFile? = null
    private var curIdx = -1
    private var lastSampleOff = 0L

    @Volatile private var stopped = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var feederConn: HttpURLConnection? = null
    private val decided = AtomicBoolean(false)
    private val activeClients = AtomicInteger(0)
    @Volatile private var everConnected = false
    @Volatile private var lastClientMs = SystemClock.elapsedRealtime()

    private var onReadyCb: ((TimeShiftSession) -> Unit)? = null
    private var onFailCb: ((TimeShiftSession) -> Unit)? = null

    /** Margen (ms) que se considera "en directo": 3 s en TS directo, 2 segmentos en HLS. */
    @Volatile var liveSlackMs = 3_000L
        private set

    val port: Int get() = server?.localPort ?: 0

    fun isStopped(): Boolean = stopped

    // -----------------------------------------------------------------
    // API para el reproductor
    // -----------------------------------------------------------------

    /** URL local para empezar a ver desde el desplazamiento [offset] del buffer. */
    fun uri(offset: Long): String = "http://127.0.0.1:$port/live.ts?off=$offset"

    /** Instante (ms de contenido) del final del buffer: el directo. */
    fun liveTimeMs(): Long = lock.withLock { tEnd }

    /** Instante más antiguo que todavía se conserva. */
    fun oldestTimeMs(): Long = lock.withLock { timeAtOffsetLocked(oldestOffsetLocked()) }

    fun timeAtOffset(offset: Long): Long = lock.withLock { timeAtOffsetLocked(offset) }

    fun offsetAtTime(timeMs: Long): Long = lock.withLock { offsetAtTimeLocked(timeMs) }

    /** Desplazamiento desde el que arrancar para estar "en directo". */
    fun liveOffset(): Long = lock.withLock {
        val target = (tEnd - liveSlackMs / 2).coerceAtLeast(timeAtOffsetLocked(oldestAlignedLocked()))
        offsetAtTimeLocked(target)
    }

    /**
     * Arranca la descarga y el servidor. [onReady] se llama (desde un hilo de
     * fondo) cuando ya hay algo de vídeo guardado; [onFail] si no se puede
     * (formato no admitido, sin conexión, sin espacio...). Solo se llama a
     * uno de los dos, una vez.
     */
    fun start(onReady: (TimeShiftSession) -> Unit, onFail: (TimeShiftSession) -> Unit) {
        onReadyCb = onReady
        onFailCb = onFail
        try {
            if (!dir.mkdirs() && !dir.exists()) throw IOException("sin carpeta")
            if (dir.usableSpace < MIN_FREE_BYTES) throw IOException("sin espacio")
            val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            server = ss
            Thread({
                while (!stopped) {
                    val socket = try { ss.accept() } catch (e: IOException) { break }
                    Thread({ serve(socket) }, "ts-conn").start()
                }
            }, "ts-accept").start()
        } catch (e: Exception) {
            fail()
            return
        }
        Thread({ runFeeder() }, "ts-feeder").start()
        Thread({ watchdog() }, "ts-watchdog").start()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        try { server?.close() } catch (e: Exception) { }
        try { feederConn?.disconnect() } catch (e: Exception) { }
        lock.withLock { dataCond.signalAll() }
        Thread({
            try { curRaf?.close() } catch (e: Exception) { }
            try { dir.deleteRecursively() } catch (e: Exception) { }
        }, "ts-cleanup").start()
    }

    private fun fail() {
        if (decided.compareAndSet(false, true)) {
            onFailCb?.invoke(this)
        }
        stop()
    }

    private fun ready() {
        if (decided.compareAndSet(false, true)) {
            onReadyCb?.invoke(this)
        }
    }

    /** Vigila: sin respuesta a tiempo = fallo; sin ningún cliente durante un rato = se apaga sola. */
    private fun watchdog() {
        val startedAt = SystemClock.elapsedRealtime()
        while (!stopped) {
            try { Thread.sleep(2_000L) } catch (e: InterruptedException) { return }
            val now = SystemClock.elapsedRealtime()
            if (!decided.get() && now - startedAt > READY_TIMEOUT_MS) {
                fail()
                return
            }
            if (everConnected && activeClients.get() == 0 && now - lastClientMs > IDLE_STOP_MS) {
                stop()
                return
            }
        }
    }

    // -----------------------------------------------------------------
    // Buffer en disco
    // -----------------------------------------------------------------

    private fun chunkFile(idx: Int) = File(dir, "c$idx.bin")

    private fun oldestOffsetLocked(): Long = if (chunks.isEmpty()) 0L else chunks[0].idx * CHUNK_BYTES

    /** Lo más antiguo que se conserva, subido al siguiente paquete TS entero (los trozos no son múltiplo de 188). */
    private fun oldestAlignedLocked(): Long {
        val oldest = oldestOffsetLocked()
        val rest = oldest % TS_PACKET
        return if (rest == 0L) oldest else oldest + TS_PACKET - rest
    }

    private fun timeAtOffsetLocked(offset: Long): Long {
        val n = sampleOffsets.size
        if (offset <= sampleOffsets[0]) return sampleTimes[0]
        if (offset >= sampleOffsets[n - 1]) return sampleTimes[n - 1]
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (sampleOffsets[mid] <= offset) lo = mid else hi = mid
        }
        val span = sampleOffsets[hi] - sampleOffsets[lo]
        if (span <= 0L) return sampleTimes[lo]
        return sampleTimes[lo] + (sampleTimes[hi] - sampleTimes[lo]) * (offset - sampleOffsets[lo]) / span
    }

    private fun offsetAtTimeLocked(timeMs: Long): Long {
        val n = sampleTimes.size
        val offset: Long
        if (timeMs <= sampleTimes[0]) {
            offset = sampleOffsets[0]
        } else if (timeMs >= sampleTimes[n - 1]) {
            offset = sampleOffsets[n - 1]
        } else {
            var lo = 0
            var hi = n - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (sampleTimes[mid] <= timeMs) lo = mid else hi = mid
            }
            val span = sampleTimes[hi] - sampleTimes[lo]
            offset = if (span <= 0L) sampleOffsets[lo]
            else sampleOffsets[lo] + (sampleOffsets[hi] - sampleOffsets[lo]) * (timeMs - sampleTimes[lo]) / span
        }
        // Alineado a paquete TS (188 bytes) y nunca antes de lo que queda en disco.
        return (offset - offset % TS_PACKET).coerceAtLeast(oldestAlignedLocked())
    }

    /** Añade [len] bytes de [buf] al buffer; [timeMs] es el instante de contenido del FINAL de esos bytes. */
    private fun append(buf: ByteArray, from: Int, len: Int, timeMs: Long) {
        var done = 0
        while (done < len) {
            val idx = (wPos / CHUNK_BYTES).toInt()
            if (idx != curIdx) {
                try { curRaf?.close() } catch (e: Exception) { }
                curRaf = RandomAccessFile(chunkFile(idx), "rw")
                curIdx = idx
                lock.withLock { chunks.add(Chunk(idx, timeMs)) }
            }
            val raf = curRaf ?: throw IOException("sin fichero")
            val inChunk = wPos - idx * CHUNK_BYTES
            val n = minOf((len - done).toLong(), CHUNK_BYTES - inChunk).toInt()
            raf.seek(inChunk)
            raf.write(buf, from + done, n)
            done += n
            wPos += n
        }
        var becameReady = false
        lock.withLock {
            writePos = wPos
            tEnd = timeMs
            if (wPos - lastSampleOff >= SAMPLE_BYTES) {
                sampleOffsets.add(wPos)
                sampleTimes.add(timeMs)
                lastSampleOff = wPos
            }
            prune()
            if (wPos >= READY_BYTES) becameReady = true
            dataCond.signalAll()
        }
        if (becameReady) ready()
    }

    /** Borra los trozos que ya quedan fuera de la ventana. Con [lock] cogido. */
    private fun prune() {
        val limit = tEnd - windowMs
        while (chunks.size > 2 && chunks[1].startTimeMs < limit) {
            val old = chunks.removeAt(0)
            try { chunkFile(old.idx).delete() } catch (e: Exception) { }
        }
        val oldest = oldestOffsetLocked()
        while (sampleOffsets.size > 2 && sampleOffsets[1] <= oldest) {
            sampleOffsets.removeAt(0)
            sampleTimes.removeAt(0)
        }
    }

    // -----------------------------------------------------------------
    // Servidor local
    // -----------------------------------------------------------------

    private fun serve(socket: Socket) {
        activeClients.incrementAndGet()
        everConnected = true
        var raf: RandomAccessFile? = null
        try {
            socket.tcpNoDelay = true
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            var pos = requestLine.substringAfter("off=", "0").substringBefore(" ").substringBefore("&")
                .toLongOrNull() ?: 0L
            pos -= pos % TS_PACKET
            val out = socket.getOutputStream()
            out.write(
                ("HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
            )
            val buf = ByteArray(64 * 1024)
            var rafIdx = -1
            while (!stopped) {
                var avail = 0L
                lock.withLock {
                    val oldest = oldestAlignedLocked()
                    if (pos < oldest) pos = oldest
                    while (pos >= writePos && !stopped) dataCond.await(300, TimeUnit.MILLISECONDS)
                    avail = writePos - pos
                }
                if (stopped || avail <= 0L) continue
                val idx = (pos / CHUNK_BYTES).toInt()
                if (idx != rafIdx) {
                    try { raf?.close() } catch (e: Exception) { }
                    raf = null
                    val file = chunkFile(idx)
                    if (!file.exists()) {
                        // Ese trozo ya se borró: se salta al siguiente.
                        pos = (idx + 1) * CHUNK_BYTES
                        continue
                    }
                    raf = RandomAccessFile(file, "r")
                    rafIdx = idx
                }
                val reading = raf ?: continue
                val inChunk = pos - idx * CHUNK_BYTES
                val n = minOf(buf.size.toLong(), CHUNK_BYTES - inChunk, avail).toInt()
                reading.seek(inChunk)
                reading.readFully(buf, 0, n)
                out.write(buf, 0, n)
                pos += n
            }
        } catch (e: Exception) {
            // El reproductor cerró la conexión (cambio de punto, parar...): normal.
        } finally {
            try { raf?.close() } catch (e: Exception) { }
            try { socket.close() } catch (e: Exception) { }
            lastClientMs = SystemClock.elapsedRealtime()
            activeClients.decrementAndGet()
        }
    }

    // -----------------------------------------------------------------
    // Descarga del canal
    // -----------------------------------------------------------------

    private fun elapsed() = SystemClock.elapsedRealtime() - t0

    private fun open(url: String): HttpURLConnection {
        var current = url
        for (i in 0 until 6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            for ((k, v) in stream.headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location.isNullOrBlank()) throw IOException("redirección sin destino")
                current = URI(current).resolve(location).toString()
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP $code")
            }
            feederConn = c
            return c
        }
        throw IOException("demasiadas redirecciones")
    }

    private fun findSync(buf: ByteArray, len: Int): Int {
        var i = 0
        while (i + 2 * TS_PACKET < len) {
            if (buf[i] == SYNC && buf[i + TS_PACKET] == SYNC && buf[i + 2 * TS_PACKET] == SYNC) return i
            i++
        }
        return -1
    }

    private fun runFeeder() {
        var failures = 0
        var firstConnection = true
        while (!stopped) {
            var gotData = false
            try {
                val conn = open(stream.url)
                val input = conn.inputStream
                val first = ByteArray(8192)
                var got = 0
                while (got < 2048) {
                    val r = input.read(first, got, first.size - got)
                    if (r < 0) break
                    got += r
                }
                if (got == 0) throw IOException("vacío")
                val head = String(first, 0, minOf(got, 64), Charsets.ISO_8859_1)
                if (head.trimStart().startsWith("#EXTM3U")) {
                    conn.disconnect()
                    runHls()
                    return
                }
                val sync = findSync(first, got)
                if (sync < 0) {
                    conn.disconnect()
                    if (firstConnection) {
                        fail() // no es MPEG-TS (radio, otro formato...): se reproduce normal
                        return
                    }
                    throw IOException("sin sincronía")
                }
                firstConnection = false
                // Si es una reconexión, se rellena hasta paquete entero para no desalinear lo nuevo.
                padToPacket()
                append(first, sync, got - sync, elapsed())
                gotData = true
                val buf = ByteArray(32 * 1024)
                while (!stopped) {
                    val r = input.read(buf)
                    if (r < 0) break
                    if (r > 0) append(buf, 0, r, elapsed())
                }
                conn.disconnect()
            } catch (e: Exception) {
                // Se reintenta abajo.
            }
            if (stopped) return
            if (gotData) failures = 0 else failures++
            if (failures > 8) {
                if (!decided.get()) fail() else stop()
                return
            }
            try { Thread.sleep(1_000L) } catch (e: InterruptedException) { return }
        }
    }

    private fun padToPacket() {
        val rest = (TS_PACKET - (wPos % TS_PACKET).toInt()) % TS_PACKET
        if (rest > 0) {
            val pad = ByteArray(rest) { 0xFF.toByte() }
            append(pad, 0, rest, elapsed().coerceAtLeast(tEndUnsafe()))
        }
    }

    private fun tEndUnsafe(): Long = lock.withLock { tEnd }

    private fun httpBytes(url: String, maxBytes: Int): ByteArray {
        val c = open(url)
        try {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            val input = c.inputStream
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                if (out.size() > maxBytes) throw IOException("segmento enorme")
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    private fun resolve(base: String, ref: String): String = URI(base).resolve(ref.trim()).toString()

    private fun runHls() {
        var playlistUrl = stream.url
        var lastSeq = -1L
        var virtualTime = 0L
        var failures = 0
        var segmentsDone = 0
        while (!stopped) {
            var waitMs = 1_000L
            try {
                var text = String(httpBytes(playlistUrl, 2_000_000), Charsets.UTF_8)
                if (text.contains("#EXT-X-STREAM-INF")) {
                    playlistUrl = pickVariant(text, playlistUrl)
                    text = String(httpBytes(playlistUrl, 2_000_000), Charsets.UTF_8)
                }
                if (text.contains("#EXT-X-MAP")) {
                    fail()
                    return
                }
                var mediaSeq = 0L
                var targetDur = 6.0
                var pendingDur = -1.0
                val segs = ArrayList<Pair<String, Double>>()
                for (raw in text.lines()) {
                    val line = raw.trim()
                    when {
                        line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE") -> {
                            fail()
                            return
                        }
                        line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                            mediaSeq = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                        line.startsWith("#EXT-X-TARGETDURATION:") ->
                            targetDur = line.substringAfter(":").trim().toDoubleOrNull() ?: 6.0
                        line.startsWith("#EXTINF:") ->
                            pendingDur = line.substringAfter(":").substringBefore(",").trim().toDoubleOrNull() ?: -1.0
                        line.isNotEmpty() && !line.startsWith("#") -> {
                            segs.add(resolve(playlistUrl, line) to (if (pendingDur > 0) pendingDur else targetDur))
                            pendingDur = -1.0
                        }
                    }
                }
                liveSlackMs = (targetDur * 2_000).toLong().coerceAtLeast(3_000L)
                if (segs.isEmpty()) throw IOException("lista sin segmentos")
                if (lastSeq < 0) lastSeq = mediaSeq + maxOf(0, segs.size - 2) - 1
                var downloaded = 0
                for ((i, seg) in segs.withIndex()) {
                    if (stopped) return
                    val seq = mediaSeq + i
                    if (seq <= lastSeq) continue
                    val bytes = httpBytes(seg.first, 40_000_000)
                    if (bytes.isEmpty() || bytes[0] != SYNC) {
                        fail() // segmentos que no son TS (fMP4, AAC suelto...)
                        return
                    }
                    val durMs = (seg.second * 1000).toLong()
                    val startT = virtualTime
                    var off = 0
                    while (off < bytes.size) {
                        val n = minOf(64 * 1024, bytes.size - off)
                        off += n
                        append(bytes, off - n, n, startT + durMs * off / bytes.size)
                    }
                    virtualTime += durMs
                    lastSeq = seq
                    downloaded++
                    segmentsDone++
                }
                failures = 0
                if (text.contains("#EXT-X-ENDLIST")) {
                    if (segmentsDone == 0) fail()
                    return
                }
                waitMs = if (downloaded > 0) 300L else maxOf(1_000L, (targetDur * 500).toLong())
            } catch (e: Exception) {
                if (stopped) return
                failures++
                if (failures > 6) {
                    if (!decided.get()) fail() else stop()
                    return
                }
            }
            try { Thread.sleep(waitMs) } catch (e: InterruptedException) { return }
        }
    }

    /** De una lista maestra, la variante de más calidad que no pase de ~6 Mbps (o la más baja si todas pasan). */
    private fun pickVariant(master: String, baseUrl: String): String {
        var bestUrl: String? = null
        var bestBw = -1L
        var lowestUrl: String? = null
        var lowestBw = Long.MAX_VALUE
        val lines = master.lines().map { it.trim() }
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val abs = resolve(baseUrl, uri)
            if (bw <= MAX_VARIANT_BANDWIDTH && bw > bestBw) {
                bestBw = bw
                bestUrl = abs
            }
            if (bw < lowestBw) {
                lowestBw = bw
                lowestUrl = abs
            }
        }
        return bestUrl ?: lowestUrl ?: throw IOException("lista maestra sin variantes")
    }

    companion object {
        private const val CHUNK_BYTES = 8L * 1024 * 1024
        private const val SAMPLE_BYTES = 128 * 1024
        private const val READY_BYTES = 400 * 1024L
        private const val TS_PACKET = 188
        private const val SYNC: Byte = 0x47
        private const val MIN_FREE_BYTES = 300L * 1024 * 1024
        private const val READY_TIMEOUT_MS = 20_000L
        private const val IDLE_STOP_MS = 3 * 60_000L
        private const val MAX_VARIANT_BANDWIDTH = 6_000_000L

        private val NOT_LIVE_EXTENSIONS = listOf(
            ".mp4", ".mkv", ".avi", ".mov", ".m4v", ".wmv", ".flv", ".webm",
            ".mp3", ".aac", ".m4a", ".ogg", ".opus", ".flac", ".wav", ".mpd"
        )

        @Volatile private var current: TimeShiftSession? = null

        /** ¿Se puede intentar la pausa en directo con este canal? (la comprobación definitiva la hace la descarga). */
        fun isEligible(stream: Stream): Boolean {
            if (stream.drm != null || stream.tokenUrl != null || stream.url.contains("{token}")) return false
            val url = stream.url.trim()
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
            val type = stream.type.trim().uppercase()
            if (type == "DASH" || type == "MPD" || type == "YOUTUBE") return false
            val path = Uri.parse(url).path.orEmpty().lowercase()
            return NOT_LIVE_EXTENSIONS.none { path.endsWith(it) }
        }

        /** Crea la sesión de este canal parando la anterior (solo hay una a la vez). Falta llamar a [start]. */
        fun create(context: Context, stream: Stream, windowMinutes: Int): TimeShiftSession {
            current?.stop()
            val session = TimeShiftSession(context, stream, windowMinutes)
            current = session
            return session
        }

        fun stopCurrent() {
            current?.stop()
            current = null
        }

        /** Borra lo que haya quedado en disco de una ejecución anterior (la app se cerró a la fuerza). */
        fun cleanupLeftovers(context: Context) {
            Thread({
                try { File(context.applicationContext.cacheDir, "timeshift").deleteRecursively() } catch (e: Exception) { }
            }, "ts-leftovers").start()
        }
    }
}
