package com.example.superplayer.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.superplayer.telegram.TelegramAccountFactory
import com.example.superplayer.telegram.TgAccountBackend
import io.github.pytgcalls.NTgCalls
import io.github.pytgcalls.media.AudioDescription
import io.github.pytgcalls.media.MediaDescription
import io.github.pytgcalls.media.MediaSource
import io.github.pytgcalls.media.StreamDevice
import io.github.pytgcalls.media.StreamMode
import io.github.pytgcalls.media.VideoDescription
import io.github.pytgcalls.models.MediaSegmentPartStatus
import io.github.pytgcalls.models.MediaSegmentQuality
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ver dentro de la app el directo (chat de vídeo) de un canal o grupo de Telegram.
 * TDLib se encarga de unirse y de dar los trozos del directo; ntgcalls los decodifica y
 * entrega el vídeo (I420) y el audio (PCM), que aquí se pintan y se reproducen.
 * Es una función experimental: abajo se muestra un registro de lo que va pasando.
 */
class LiveActivity : AppCompatActivity() {
    private var backend: TgAccountBackend? = null
    private var ntg: NTgCalls? = null
    private var chatId = 0L

    private val control = Executors.newSingleThreadExecutor()
    private val net = Executors.newCachedThreadPool()
    private val render = Executors.newSingleThreadExecutor()
    private val audioExec = Executors.newSingleThreadExecutor()

    private lateinit var image: ImageView
    private lateinit var logView: TextView
    private val logLines = ArrayDeque<String>()

    private val rendering = AtomicBoolean(false)
    private val audioBacklog = AtomicInteger(0)
    private var audioTrack: AudioTrack? = null
    private var framesVideo = 0
    private var framesAudio = 0

    @Volatile private var scale = 0
    @Volatile private var closed = false
    private val segCache = LinkedHashMap<String, ByteArray>()
    private val segLock = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        chatId = intent.getLongExtra("chatId", 0L)
        title = intent.getStringExtra("title") ?: "Directo"

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        logView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 11f
            setPadding(16, 8, 16, 8)
            setOnClickListener { visibility = View.GONE }
        }
        root.addView(logView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // Tocar la imagen muestra u oculta el registro.
        image.setOnClickListener { logView.visibility = if (logView.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        setContentView(root)

        val b = TelegramAccountFactory.create(this)
        backend = b
        if (b == null || chatId == 0L) {
            log("Telegram no está disponible en esta compilación")
            return
        }
        control.execute { start(b) }
    }

    private fun log(msg: String) {
        Log.d("LiveActivity", msg)
        runOnUiThread {
            logLines.addLast(msg)
            while (logLines.size > 14) logLines.removeFirst()
            logView.text = logLines.joinToString("\n")
        }
    }

    private fun fld(obj: Any?, vararg names: String): Any? {
        if (obj == null) return null
        for (n in names) {
            try {
                return obj.javaClass.getField(n).get(obj)
            } catch (e: Exception) { }
        }
        return null
    }

    private fun start(b: TgAccountBackend) {
        try {
            val n = NTgCalls()
            ntg = n
            log("ntgcalls: " + NTgCalls.ping())

            n.onConnectionChange { _, info -> log("conexión: ${info.state} / ${info.kind}") }
            n.onStreamEnd { _, type, device -> log("fin de flujo: $type $device") }
            n.onRemoteSourceChange { _, src -> log("fuente remota: ${src.device} ${src.state} ssrc=${src.ssrc}") }
            n.onFrames { _, _, device, frames -> onFrames(device, frames) }
            n.onRequestBroadcastTimestamp { _ -> net.execute { answerTimestamp(b, n) } }
            n.onRequestBroadcastPart { _, req -> net.execute { answerPart(b, n, req) } }

            val audio = AudioDescription(MediaSource.EXTERNAL, SAMPLE_RATE, CHANNELS, "", false)
            val video = VideoDescription(MediaSource.EXTERNAL, VIDEO_W, VIDEO_H, 30, "", false)
            n.setStreamSources(KEY, StreamMode.PLAYBACK, MediaDescription(null, audio, video, video))

            val payload = n.createCall(KEY)
            log("createCall ok (${payload.length} bytes)")
            val ssrc = try { JSONObject(payload).optLong("ssrc").toInt() } catch (e: Exception) { 0 }

            val latch = CountDownLatch(1)
            var response: String? = null
            b.liveJoin(chatId, ssrc, payload) { resp, info ->
                response = resp
                log("unirse: " + info.trim())
                latch.countDown()
            }
            latch.await(30, TimeUnit.SECONDS)
            val resp = response
            if (resp == null) {
                log("No se pudo unir al directo")
                return
            }
            log("respuesta de Telegram (${resp.length} bytes), conectando…")
            n.connect(KEY, resp, false)
            log("connect hecho; modo: " + try { n.getConnectionMode(KEY) } catch (e: Throwable) { "?" })
        } catch (e: Throwable) {
            log("Error: $e")
        }
    }

    private fun answerTimestamp(b: TgAccountBackend, n: NTgCalls) {
        val latch = CountDownLatch(1)
        var res: LongArray? = null
        var info = ""
        b.liveStreams(chatId) { r, i -> res = r; info = i; latch.countDown() }
        latch.await(15, TimeUnit.SECONDS)
        val r = res
        if (r == null) {
            log("streams: ${info.trim()}")
            return
        }
        scale = r[1].toInt()
        log("marca de tiempo ${r[0]} escala ${r[1]} canal ${r[2]}")
        try { n.sendBroadcastTimestamp(KEY, r[0]) } catch (e: Throwable) { log("sendBroadcastTimestamp: $e") }
    }

    private fun answerPart(b: TgAccountBackend, n: NTgCalls, req: io.github.pytgcalls.models.SegmentPartRequest) {
        if (closed) return
        val quality = when (req.quality) {
            MediaSegmentQuality.FULL -> 3
            MediaSegmentQuality.MEDIUM -> 2
            MediaSegmentQuality.THUMBNAIL -> 1
            else -> 0
        }
        val key = "${req.segment_id}/${req.channel_id}/$quality"
        var data: ByteArray?
        var err = ""
        synchronized(segLock) {
            data = segCache[key]
            if (data == null) {
                val latch = CountDownLatch(1)
                b.liveSegment(chatId, req.segment_id, scale, req.channel_id, quality) { d, e ->
                    data = d; err = e; latch.countDown()
                }
                latch.await(20, TimeUnit.SECONDS)
                data?.let {
                    segCache[key] = it
                    while (segCache.size > 6) segCache.remove(segCache.keys.first())
                }
            }
        }
        try {
            val d = data
            if (d == null) {
                val status = if (err.contains("TOO_SMALL", true)) MediaSegmentPartStatus.RESYNC_NEEDED else MediaSegmentPartStatus.NOT_READY
                if (!err.contains("TOO_BIG", true)) log("trozo ${req.segment_id}: $status ${err.trim().take(120)}")
                n.sendBroadcastPart(KEY, req.segment_id, req.part_id, status, req.quality_update, null)
            } else {
                val from = minOf(req.part_id.toLong() * req.limit, d.size.toLong()).toInt()
                val to = minOf(from.toLong() + req.limit, d.size.toLong()).toInt()
                n.sendBroadcastPart(KEY, req.segment_id, req.part_id, MediaSegmentPartStatus.SUCCESS, req.quality_update, d.copyOfRange(from, to))
            }
        } catch (e: Throwable) {
            log("sendBroadcastPart: $e")
        }
    }

    // ---------------- Fotogramas recibidos ----------------

    private fun onFrames(device: StreamDevice, frames: List<*>) {
        if (closed) return
        for (f in frames) {
            val data = fld(f, "data") as? ByteArray ?: continue
            if (device == StreamDevice.SPEAKER || device == StreamDevice.MICROPHONE) {
                playAudio(data)
            } else {
                val fd = fld(f, "frame_data", "frameData")
                val w = (fld(fd, "width") as? Number)?.toInt() ?: VIDEO_W
                val h = (fld(fd, "height") as? Number)?.toInt() ?: VIDEO_H
                showVideo(data, w, h)
            }
        }
    }

    private fun playAudio(data: ByteArray) {
        framesAudio++
        if (framesAudio == 1) log("llega audio (${data.size} bytes por trozo)")
        if (audioBacklog.get() > 40) return
        audioBacklog.incrementAndGet()
        audioExec.execute {
            try {
                var t = audioTrack
                if (t == null) {
                    val channelMask = if (CHANNELS == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                    t = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(channelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(min, SAMPLE_RATE * CHANNELS * 2 / 2))
                        .build()
                    t.play()
                    audioTrack = t
                }
                t.write(data, 0, data.size)
            } catch (e: Throwable) {
                log("audio: $e")
            } finally {
                audioBacklog.decrementAndGet()
            }
        }
    }

    private fun showVideo(data: ByteArray, w: Int, h: Int) {
        framesVideo++
        if (framesVideo == 1) log("llega vídeo ${w}x$h (${data.size} bytes)")
        if (w <= 0 || h <= 0 || data.size < w * h * 3 / 2) return
        if (!rendering.compareAndSet(false, true)) return // si va atrasado, se salta el fotograma
        render.execute {
            try {
                val bmp = i420ToBitmap(data, w, h)
                runOnUiThread { if (!closed) image.setImageBitmap(bmp) }
            } catch (e: Throwable) {
                log("vídeo: $e")
            } finally {
                rendering.set(false)
            }
        }
    }

    private fun i420ToBitmap(d: ByteArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        val ySize = w * h
        val cw = w / 2
        val uOff = ySize
        val vOff = ySize + cw * (h / 2)
        for (j in 0 until h) {
            val yRow = j * w
            val cRow = (j shr 1) * cw
            for (i in 0 until w) {
                val c = (d[yRow + i].toInt() and 0xFF) - 16
                val u = (d[uOff + cRow + (i shr 1)].toInt() and 0xFF) - 128
                val v = (d[vOff + cRow + (i shr 1)].toInt() and 0xFF) - 128
                val y298 = 298 * c + 128
                var r = (y298 + 409 * v) shr 8
                var g = (y298 - 100 * u - 208 * v) shr 8
                var b = (y298 + 516 * u) shr 8
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                px[yRow + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    override fun onDestroy() {
        closed = true
        val n = ntg
        val b = backend
        val cid = chatId
        control.execute {
            try { n?.stop(KEY) } catch (e: Throwable) { }
            try { b?.liveLeave(cid) } catch (e: Throwable) { }
        }
        audioExec.execute {
            try { audioTrack?.stop(); audioTrack?.release() } catch (e: Throwable) { }
            audioTrack = null
        }
        super.onDestroy()
    }

    companion object {
        private const val KEY = 1L
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
        private const val VIDEO_W = 960
        private const val VIDEO_H = 540
    }
}
