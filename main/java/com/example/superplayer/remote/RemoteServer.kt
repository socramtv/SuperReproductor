package com.example.superplayer.remote

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.view.View
import android.os.Looper
import com.example.superplayer.model.Stream
import com.example.superplayer.model.streamFromJson
import com.example.superplayer.player.PlayerActivity
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Control remoto desde otro móvil (el móvil como mando de la tele): esta
 * pantalla (normalmente la tele o el Fire TV) abre un mini servidor HTTP en
 * la red local y se anuncia por NSD (_socramtv._tcp); otro móvil con la app
 * la encuentra (o se le escribe la IP) y le manda órdenes: canal +/-,
 * pausa, volumen, retroceder/adelantar, abrir un canal...
 *
 * Seguridad: todas las órdenes llevan un código de 4 cifras (X-Pin) que se
 * ve en la pantalla de esta app (menú ⋮ → Control remoto…). Solo funciona
 * en la red local. Por defecto está activado en TV y desactivado en el móvil.
 *
 * Mientras el reproductor esté abierto, PlayerActivity se registra en
 * [player] para recibir las órdenes de canal/pausa; abrir un canal desde el
 * móvil funciona con la app abierta en la tele (portada o reproductor).
 */
object RemoteServer {
    const val DEFAULT_PORT = 8765
    private const val PREFS = "remote_control"
    private const val SERVICE_TYPE = "_socramtv._tcp."

    /** Lo que PlayerActivity sabe hacer; siempre se llama en el hilo principal. */
    interface PlayerCommands {
        fun next()
        fun previous()
        fun playPause()
        fun seek(deltaMs: Long)
        fun open(stream: Stream)
        /** JSON con lo que se está viendo (canal, programa, guía…). */
        fun status(): String
    }

    @Volatile var player: PlayerCommands? = null

    /** Pantalla de la app que está al frente: recibe las flechas / OK / Atrás del mando del móvil. */
    @Volatile private var resumed: java.lang.ref.WeakReference<Activity>? = null
    private var callbacksRegistered = false
    private var savedAppVolume = 1f

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null
    private var appContext: Context? = null
    private var registration: NsdManager.RegistrationListener? = null

    @Volatile var port: Int = 0
        private set

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Sigue qué pantalla de la app está al frente (se llama una vez desde la Application, antes de crear ninguna pantalla). */
    @Synchronized
    fun trackActivities(context: Context) {
        if (callbacksRegistered) return
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumed = java.lang.ref.WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) { if (resumed?.get() === activity) resumed = null }
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        callbacksRegistered = true
    }

    fun isTv(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", isTv(context))

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        if (enabled) ensureStarted(context) else stop()
    }

    /** Código de 4 cifras; se crea la primera vez. */
    fun pin(context: Context): String {
        val p = prefs(context)
        val existing = p.getString("pin", null)
        if (existing != null) return existing
        return newPin(context)
    }

    fun newPin(context: Context): String {
        val pin = String.format("%04d", java.security.SecureRandom().nextInt(10_000))
        prefs(context).edit().putString("pin", pin).apply()
        return pin
    }

    fun deviceName(): String = (Build.MODEL ?: "Android").take(30)

    /** Primera IPv4 de la red local de este aparato (para escribirla a mano en el móvil), o null. */
    fun localAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    /** Arranca el servidor si está activado y aún no corre. Se puede llamar las veces que haga falta. */
    @Synchronized
    fun ensureStarted(context: Context) {
        val app = context.applicationContext
        appContext = app
        trackActivities(app)
        if (server != null || !isEnabled(app)) return
        val socket = try {
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(DEFAULT_PORT))
                }
            } catch (e: IOException) {
                ServerSocket(0)
            }
        } catch (e: Exception) {
            return
        }
        server = socket
        port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                Thread({ handle(app, client) }, "remote-conn").start()
            }
        }, "remote-accept").start()
        registerNsd(app, port)
    }

    @Synchronized
    fun stop() {
        val app = appContext
        try { server?.close() } catch (e: Exception) { }
        server = null
        port = 0
        val listener = registration
        registration = null
        if (app != null && listener != null) {
            try {
                (app.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(listener)
            } catch (e: Exception) { }
        }
    }

    private fun registerNsd(app: Context, servicePort: Int) {
        try {
            val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "SocramTV-" + deviceName()
                serviceType = SERVICE_TYPE
                this.port = servicePort
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            }
            registration = listener
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            // Sin anuncio automático se puede seguir conectando escribiendo la IP.
        }
    }

    // -----------------------------------------------------------------
    // Peticiones
    // -----------------------------------------------------------------

    private fun <T> onMain(block: () -> T): T? {
        val task = FutureTask<T>(Callable { block() })
        main.post(task)
        return try { task.get(3, TimeUnit.SECONDS) } catch (e: Exception) { null }
    }

    private fun respond(socket: Socket, code: Int, json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error" }
        val out = socket.getOutputStream()
        out.write(
            ("HTTP/1.0 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        )
        out.write(body)
        out.flush()
    }

    private fun handle(app: Context, socket: Socket) {
        try {
            socket.soTimeout = 5_000
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val target = parts[1]
            var contentLength = 0
            var pinHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
                if (name == "x-pin") pinHeader = value
            }
            var body = ""
            if (contentLength > 0) {
                val buf = CharArray(minOf(contentLength, 200_000))
                var read = 0
                while (read < buf.size) {
                    val r = reader.read(buf, read, buf.size - read)
                    if (r < 0) break
                    read += r
                }
                body = String(buf, 0, read)
            }
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")

            if (path == "/ping") {
                respond(socket, 200, JSONObject().put("app", "socramtv").put("name", deviceName()))
                return
            }
            if (pinHeader != pin(app)) {
                respond(socket, 401, JSONObject().put("error", "pin"))
                return
            }
            when (path) {
                "/status" -> {
                    val playerStatus = player?.let { p -> onMain { p.status() } }
                    val json = JSONObject().put("name", deviceName())
                    if (playerStatus != null) json.put("player", JSONObject(playerStatus))
                    json.put("volume", volumeStatus(app))
                    respond(socket, 200, json)
                }
                "/cmd" -> {
                    val c = query.split("&").firstOrNull { it.startsWith("c=") }?.substringAfter("c=").orEmpty()
                    respond(socket, 200, JSONObject().put("ok", runCommand(app, c)))
                }
                "/text" -> {
                    val o = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    respond(socket, 200, JSONObject().put("ok", typeText(o.optString("text"), o.optBoolean("submit", false))))
                }
                "/open" -> {
                    val stream = streamFromJson(body)
                    if (stream == null) {
                        respond(socket, 200, JSONObject().put("ok", false).put("error", "stream"))
                    } else {
                        respond(socket, 200, JSONObject().put("ok", openStream(app, stream)))
                    }
                }
                else -> respond(socket, 404, JSONObject().put("error", "not_found"))
            }
        } catch (e: Exception) {
            // Petición rota o cliente que se fue: se ignora.
        } finally {
            try { socket.close() } catch (e: Exception) { }
        }
    }

    // -----------------------------------------------------------------
    // Volumen: se intenta el de la tele/aparato; si es fijo (HDMI-CEC, cajas sin volumen propio) o
    // no cambia nada, se sube/baja el volumen de la propia app (el del reproductor).
    // -----------------------------------------------------------------

    private fun toastMain(app: Context, text: String) {
        main.post { android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun volumeStatus(app: Context): JSONObject {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val sys = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val sysMuted = if (Build.VERSION.SDK_INT >= 23) audio.isStreamMute(AudioManager.STREAM_MUSIC) else sys == 0
        val gain = com.example.superplayer.player.PlaybackService.exo?.let { e -> onMain { e.volume } } ?: 1f
        val fixed = Build.VERSION.SDK_INT >= 21 && audio.isVolumeFixed
        val pct = if (fixed || gain < 1f) Math.round(gain * 100) else Math.round(sys * 100f / max)
        return JSONObject().put("pct", pct).put("muted", sysMuted || gain <= 0f).put("app", fixed || gain < 1f)
    }

    private fun changeVolume(app: Context, dir: Int): Boolean {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val exo = com.example.superplayer.player.PlaybackService.exo
        val fixed = Build.VERSION.SDK_INT >= 21 && audio.isVolumeFixed
        var worked = false
        if (!fixed) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            worked = after != before || (dir > 0 && before >= max) || (dir < 0 && before <= 0)
        }
        if (worked) {
            // El del aparato responde: se quita cualquier bajada anterior hecha solo en la app.
            if (exo != null) onMain { if (exo.volume < 1f) { exo.volume = 1f }; Unit }
            return true
        }
        if (exo == null) return false
        onMain {
            val v = (exo.volume + dir * 0.1f).coerceIn(0f, 1f)
            exo.volume = v
            toastMain(app, (if (v <= 0f) "🔇 " else "🔊 ") + Math.round(v * 100) + " %")
        }
        return true
    }

    private fun toggleMute(app: Context): Boolean {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val exo = com.example.superplayer.player.PlaybackService.exo
        // Silenciado desde la app: se devuelve el volumen.
        if (exo != null && onMain { exo.volume } == 0f) {
            onMain { exo.volume = if (savedAppVolume > 0f) savedAppVolume else 1f; Unit }
            toastMain(app, "🔊")
            return true
        }
        val fixed = Build.VERSION.SDK_INT >= 21 && audio.isVolumeFixed
        if (!fixed && Build.VERSION.SDK_INT >= 23) {
            val before = audio.isStreamMute(AudioManager.STREAM_MUSIC)
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC) != before) return true
        }
        if (exo == null) return false
        onMain {
            savedAppVolume = if (exo.volume > 0f) exo.volume else 1f
            exo.volume = 0f
            toastMain(app, "🔇")
        }
        return true
    }

    // -----------------------------------------------------------------
    // Teclas y texto para la pantalla que esté abierta en la tele
    // -----------------------------------------------------------------

    /**
     * La ventana que tiene ahora el foco de teclas: normalmente la pantalla, pero si hay un diálogo (la ficha de una
     * película, un menú…) abierto encima es esa ventana, que es la que debe recibir las flechas del mando del móvil.
     */
    private fun focusedRoot(a: Activity): View {
        try {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val global = cls.getMethod("getInstance").invoke(null)
            val field = cls.getDeclaredField("mViews")
            field.isAccessible = true
            val views = (field.get(global) as? List<*>)?.toList() ?: return a.window.decorView
            for (i in views.indices.reversed()) {
                val v = views[i] as? View ?: continue
                if (v.isAttachedToWindow && v.hasWindowFocus()) return v
            }
        } catch (e: Throwable) {
            // Sin esto solo se controla la pantalla, no los diálogos.
        }
        return a.window.decorView
    }

    private fun pressKey(code: Int): Boolean {
        return onMain {
            val a = resumed?.get() ?: return@onMain false
            val root = focusedRoot(a)
            val isActivityWindow = root === a.window.decorView
            val t = android.os.SystemClock.uptimeMillis()
            fun send(action: Int, time: Long): Boolean {
                val ev = android.view.KeyEvent(t, time, action, code, 0)
                return if (isActivityWindow) a.dispatchKeyEvent(ev) else root.dispatchKeyEvent(ev)
            }
            if (code == android.view.KeyEvent.KEYCODE_BACK) {
                if (isActivityWindow) {
                    (a as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed() ?: a.finish()
                } else {
                    send(android.view.KeyEvent.ACTION_DOWN, t)
                    send(android.view.KeyEvent.ACTION_UP, t + 40)
                }
                return@onMain true
            }
            val down = send(android.view.KeyEvent.ACTION_DOWN, t)
            val up = send(android.view.KeyEvent.ACTION_UP, t + 40)
            if (!down && !up) {
                // La pantalla no usa esa tecla por su cuenta (en Android, mover el foco con las flechas lo hace el sistema
                // y no la pantalla): se hace aquí a mano, dentro de la ventana que tiene el foco.
                val dir = when (code) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                    else -> 0
                }
                val cur = root.findFocus()
                if (dir != 0) {
                    if (cur == null) {
                        root.requestFocus(View.FOCUS_DOWN)
                    } else {
                        val next = cur.focusSearch(dir)
                        if (next != null && next !== cur) next.requestFocus(dir)
                    }
                } else if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER) {
                    if (cur == null) root.requestFocus(View.FOCUS_DOWN) else cur.performClick()
                } else if (code == android.view.KeyEvent.KEYCODE_MENU && isActivityWindow) {
                    a.openOptionsMenu()
                }
            }
            true
        } ?: false
    }

    /** Escribe [text] en el campo de texto que tenga el foco en la tele (buscadores, direcciones…); con [submit], lo envía. */
    private fun typeText(text: String, submit: Boolean): Boolean {
        return onMain {
            val a = resumed?.get() ?: return@onMain false
            val et = focusedRoot(a).findFocus() as? android.widget.EditText ?: return@onMain false
            et.setText(text)
            et.setSelection(et.text.length)
            if (submit) {
                val action = et.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
                et.onEditorAction(if (action == 0 || action == android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED)
                    android.view.inputmethod.EditorInfo.IME_ACTION_GO else action)
            }
            true
        } ?: false
    }

    private fun runCommand(app: Context, command: String): Boolean {
        when (command) {
            "volup" -> return changeVolume(app, 1)
            "voldown" -> return changeVolume(app, -1)
            "mute" -> return toggleMute(app)
            "key_up" -> return pressKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
            "key_down" -> return pressKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            "key_left" -> return pressKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            "key_right" -> return pressKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            "key_ok" -> return pressKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            "key_back" -> return pressKey(android.view.KeyEvent.KEYCODE_BACK)
            "key_menu" -> return pressKey(android.view.KeyEvent.KEYCODE_MENU)
            "home" -> return onMain {
                val i = Intent(app, com.example.superplayer.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                app.startActivity(i)
                true
            } ?: false
        }
        val p = player ?: return false
        return onMain {
            when (command) {
                "next" -> p.next()
                "prev" -> p.previous()
                "playpause" -> p.playPause()
                "back30" -> p.seek(-30_000L)
                "fwd30" -> p.seek(30_000L)
                "back5m" -> p.seek(-5 * 60_000L)
                "fwd5m" -> p.seek(5 * 60_000L)
                else -> return@onMain false
            }
            true
        } ?: false
    }

    private fun openStream(app: Context, stream: Stream): Boolean {
        val p = player
        if (p != null) {
            return onMain { p.open(stream); true } ?: false
        }
        // Sin reproductor abierto: se abre uno nuevo (funciona con la app visible en la tele).
        return onMain {
            PlayerActivity.pendingStream = stream
            PlayerActivity.pendingChannelList = emptyList()
            val intent = Intent(app, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            true
        } ?: false
    }
}
