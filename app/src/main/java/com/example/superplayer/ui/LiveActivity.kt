package com.example.superplayer.ui

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
import android.os.Handler
import android.os.Looper

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
    private val audioExec = Executors.newSingleThreadExecutor()

    private lateinit var image: YuvView
    private var muted = false
    private lateinit var firstButton: View
    private lateinit var logView: TextView
    private lateinit var controls: View
    private val hideControls = Runnable { controls.visibility = View.GONE }
    private val logLines = ArrayDeque<String>()

    private val audioBacklog = AtomicInteger(0)
    private var audioTrack: AudioTrack? = null
    private var framesVideo = 0
    private var framesAudio = 0

    private val reqCount = AtomicInteger(0)
    private val okCount = AtomicInteger(0)
    private val failCount = AtomicInteger(0)
    private val ui = Handler(Looper.getMainLooper())
    private var lastStatus = ""
    private val consecFails = AtomicInteger(0)
    private val rejoinCount = AtomicInteger(0)
    @Volatile private var lastOkAt = 0L
    @Volatile private var lastRejoinAt = 0L
    private var beats = 0
    private val heartbeat = object : Runnable {
        override fun run() {
            val st = "partes: pedidas ${reqCount.get()}, ok ${okCount.get()}, fallos ${failCount.get()} | audio $framesAudio, vídeo $framesVideo | reconexiones ${rejoinCount.get()}"
            if (st != lastStatus) {
                lastStatus = st
                log(st)
            }
            keepAlive()
            if (!closed) ui.postDelayed(this, 4000)
        }
    }

    /**
     * Vigilante de la sesión: cada 4 s comprueba que Telegram sigue dándonos trozos y, cada 8 s,
     * pregunta por los flujos (si responde JOIN_MISSING, nos ha dado de baja: se vuelve a unir
     * enseguida, sin esperar a que falle un trozo y se congele la imagen).
     */
    private fun keepAlive() {
        val b = backend ?: return
        if (closed || joinPayload.isEmpty() || rejoining.get()) return
        beats++
        val now = System.currentTimeMillis()
        if (lastOkAt != 0L && now - lastOkAt > 12000) {
            net.execute { rejoin(b, "sin trozos desde hace ${(now - lastOkAt) / 1000} s") }
            return
        }
        if (beats % 2 == 0) {
            net.execute {
                val latch = CountDownLatch(1)
                var msg = ""
                b.liveStreams(chatId) { _, i -> msg = i; latch.countDown() }
                latch.await(10, TimeUnit.SECONDS)
                if (msg.contains("JOIN_MISSING", true)) rejoin(b, "sondeo: JOIN_MISSING")
            }
        }
    }

    @Volatile private var scale = 0
    @Volatile private var joinSsrc = 0
    @Volatile private var joinPayload = ""
    private val rejoining = AtomicBoolean(false)
    private val seenChannels = java.util.Collections.synchronizedSet(HashSet<String>())
    private val seenDevices = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var closed = false
    private val segCache = LinkedHashMap<String, ByteArray>()
    private val segLock = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        chatId = intent.getLongExtra("chatId", 0L)
        title = intent.getStringExtra("title") ?: "Directo"

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = YuvView(this)
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Registro de depuración: oculto; se muestra con una pulsación larga sobre la imagen.
        logView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 11f
            setPadding(16, 8, 16, 8)
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        root.addView(logView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Barra superior (cerrar, título, girar): aparece al tocar y se oculta sola.
        val dp = resources.displayMetrics.density
        fun barButton(text: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            isFocusable = true
            isClickable = true
            // Resaltado visible al seleccionar con el mando (TV).
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x66FFFFFF)
                    setStroke((3 * dp).toInt(), Color.WHITE)
                    cornerRadius = 8 * dp
                })
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
            setOnClickListener { onClick(); showControls() }
        }
        controls = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
            val closeBtn = barButton("✕") { finish() }
            firstButton = closeBtn
            addView(closeBtn)
            addView(TextView(this@LiveActivity).apply {
                text = "🔴 " + title
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(barButton("🔊") { }.also { btn ->
                btn.setOnClickListener { v ->
                    muted = !muted
                    (v as TextView).text = if (muted) "🔇" else "🔊"
                    audioTrack?.setVolume(if (muted) 0f else 1f)
                    showControls()
                }
            })
            addView(barButton("⟳") {
                requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            })
        }
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        image.isFocusable = true
        image.setOnClickListener { if (controls.visibility == View.VISIBLE) controls.visibility = View.GONE else showControls() }
        image.setOnLongClickListener {
            logView.visibility = if (logView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }
        setContentView(root)

        // Pantalla completa (se ocultan las barras del sistema; se recuperan deslizando desde el borde).
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, root).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        showControls()

        val b = TelegramAccountFactory.create(this)
        backend = b
        if (b == null || chatId == 0L) {
            log("Telegram no está disponible en esta compilación")
            return
        }
        control.execute { start(b) }
        ui.postDelayed(heartbeat, 4000)
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        ui.removeCallbacks(hideControls)
        ui.postDelayed(hideControls, 6000)
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

            val payload = n.createCall(KEY)
            log("createCall ok (${payload.length} bytes)")
            // En reproducción, el audio recibido va por "microphone" (ntgcalls lo activa solo si existe). Las fuentes se configuran DESPUÉS de crear la llamada (antes da "Connection not found").
            val audio = AudioDescription(MediaSource.EXTERNAL, SAMPLE_RATE, CHANNELS, "", false)
            val video = VideoDescription(MediaSource.EXTERNAL, VIDEO_W, VIDEO_H, 30, "", false)
            n.setStreamSources(KEY, StreamMode.PLAYBACK, MediaDescription(audio, null, video, video))
            log("fuentes de reproducción configuradas")
            val ssrc = try { JSONObject(payload).optLong("ssrc").toInt() } catch (e: Exception) { 0 }
            joinSsrc = ssrc
            joinPayload = payload

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
        val n0 = reqCount.incrementAndGet()
        if (seenChannels.add("${req.channel_id}/${req.quality}")) log("NUEVO canal pedido: ${req.channel_id} calidad ${req.quality}")
        if (n0 <= 4) log("pide trozo seg=${req.segment_id} parte=${req.part_id} límite=${req.limit} canal=${req.channel_id} calidad=${req.quality}")
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
                val f = failCount.incrementAndGet()
                val cf = consecFails.incrementAndGet()
                if (f <= 6) log("trozo ${req.segment_id}: $status ${err.trim().take(160)}")
                n.sendBroadcastPart(KEY, req.segment_id, req.part_id, status, req.quality_update, null)
                if (err.contains("JOIN_MISSING", true)) rejoin(b, "JOIN_MISSING")
                else if (cf >= 4 && !err.contains("TOO_SMALL", true)) rejoin(b, "$cf fallos seguidos")
            } else {
                // La parte 0 lleva el trozo ENTERO (ntgcalls no pide más partes aunque pase del límite).
                val chunk = if (req.part_id == 0) d else {
                    val from = minOf(req.part_id.toLong() * req.limit, d.size.toLong()).toInt()
                    val to = minOf(from.toLong() + req.limit, d.size.toLong()).toInt()
                    d.copyOfRange(from, to)
                }
                n.sendBroadcastPart(KEY, req.segment_id, req.part_id, MediaSegmentPartStatus.SUCCESS, req.quality_update, chunk)
                consecFails.set(0)
                lastOkAt = System.currentTimeMillis()
                if (okCount.incrementAndGet() <= 3) log("trozo ok: ${d.size} bytes (parte ${req.part_id})")
            }
        } catch (e: Throwable) {
            log("sendBroadcastPart: $e")
        }
    }

    /**
     * Telegram puede dar de baja la sesión (GROUPCALL_JOIN_MISSING). Se vuelve a unir con el mismo
     * payload/ssrc, así ntgcalls sigue conectado y no hay que reiniciar nada. Primero se prueba
     * unirse directamente (sin hueco); si no, salir, esperar y volver a entrar, con reintentos.
     */
    private fun rejoin(b: TgAccountBackend, reason: String) {
        if (closed || joinPayload.isEmpty()) return
        if (System.currentTimeMillis() - lastRejoinAt < 2500) return
        if (!rejoining.compareAndSet(false, true)) return
        try {
            lastRejoinAt = System.currentTimeMillis()
            val n = rejoinCount.incrementAndGet()
            log("reconectando #$n ($reason)")
            var ok = false
            var attempt = 0
            while (!ok && !closed && attempt < 4) {
                if (attempt > 0) {
                    try { b.liveLeave(chatId) } catch (e: Throwable) { }
                    Thread.sleep(800L * attempt)
                }
                val latch = CountDownLatch(1)
                b.liveJoin(chatId, joinSsrc, joinPayload) { resp, info ->
                    ok = resp != null
                    if (!ok) log("reunirse (intento ${attempt + 1}): FALLÓ " + info.trim().take(160))
                    latch.countDown()
                }
                latch.await(20, TimeUnit.SECONDS)
                attempt++
            }
            if (ok) {
                consecFails.set(0)
                lastOkAt = System.currentTimeMillis()
                log("reconexión #$n ok")
            } else if (!closed) {
                log("reconexión #$n fallida")
            }
        } finally {
            lastRejoinAt = System.currentTimeMillis()
            rejoining.set(false)
        }
    }

    // ---------------- Fotogramas recibidos ----------------

    private fun onFrames(device: StreamDevice, frames: List<*>) {
        if (closed) return
        for (f in frames) {
            val data = fld(f, "data") as? ByteArray ?: continue
            if (seenDevices.add(device.toString())) log("frames de $device: ${data.size} bytes, ssrc=${fld(f, "ssrc")}")
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
                    t.setVolume(if (muted) 0f else 1f)
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
        image.setFrame(data, w, h)
    }

    // Mando de TV: cualquier tecla de dirección o OK muestra los controles y los selecciona.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                if (controls.visibility != View.VISIBLE) {
                    showControls()
                    firstButton.requestFocus()
                    return true
                }
                showControls()
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MUTE -> {
                muted = !muted
                audioTrack?.setVolume(if (muted) 0f else 1f)
                log(if (muted) "silenciado" else "con sonido")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        image.onResume()
    }

    override fun onPause() {
        image.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        closed = true
        ui.removeCallbacks(heartbeat)
        ui.removeCallbacks(hideControls)
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
