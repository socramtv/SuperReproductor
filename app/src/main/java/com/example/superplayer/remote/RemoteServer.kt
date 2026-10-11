package com.example.superplayer.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
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
        /** JSON con lo que se está viendo. */
        fun status(): String
    }

    @Volatile var player: PlayerCommands? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null
    private var appContext: Context? = null
    private var registration: NsdManager.RegistrationListener? = null

    @Volatile var port: Int = 0
        private set

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
                    respond(socket, 200, json)
                }
                "/cmd" -> {
                    val c = query.split("&").firstOrNull { it.startsWith("c=") }?.substringAfter("c=").orEmpty()
                    respond(socket, 200, JSONObject().put("ok", runCommand(app, c)))
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

    private fun runCommand(app: Context, command: String): Boolean {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (command) {
            "volup" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                return true
            }
            "voldown" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return true
            }
            "mute" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                return true
            }
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
