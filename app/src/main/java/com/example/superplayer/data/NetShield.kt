package com.example.superplayer.data

import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * «Modo anti-bloqueo» del navegador: intermediario local (127.0.0.1) por el que pasa el navegador de la app.
 * Resuelve los nombres con DNS por HTTPS (no con el de la operadora) y, en las conexiones HTTPS, parte en dos
 * el primer mensaje de la conexión por la mitad del nombre del servidor, que es lo que muchas operadoras
 * miran para cortar («ERR_CONNECTION_RESET»). No descifra nada: el tráfico HTTPS sigue yendo cifrado de punta a punta.
 */
object NetShield {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var appCtx: Context? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "netshield").apply { isDaemon = true } }
    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    fun attach(c: Context) { appCtx = c.applicationContext; if (enabled(c)) { try { start(c) } catch (e: Exception) { }; applyWebProxy(c) } }

    private const val PREFS = "app_prefs"
    private const val KEY = "net_shield"

    fun enabled(c: Context): Boolean = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(c: Context, on: Boolean) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (on) try { start(c) } catch (e: Exception) { }
        applyWebProxy(c)
    }

    /** El navegador de la app (WebView) pasa por el intermediario mientras el modo esté activado. */
    fun applyWebProxy(c: Context) {
        try {
            if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) return
            val ex = androidx.core.content.ContextCompat.getMainExecutor(c)
            if (enabled(c)) {
                val cfg = androidx.webkit.ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:" + start(c))
                    .addBypassRule("localhost").addBypassRule("127.0.0.1")
                    .build()
                androidx.webkit.ProxyController.getInstance().setProxyOverride(cfg, ex) { }
            } else {
                androidx.webkit.ProxyController.getInstance().clearProxyOverride(ex) { }
            }
        } catch (e: Exception) { }
    }

    private fun proxy(c: Context) = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", start(c)))

    @Volatile private var okCached: Pair<Int, okhttp3.OkHttpClient>? = null

    /** Cliente OkHttp (reproductor) que pasa por el intermediario, o null si el modo está desactivado. */
    fun client(c: Context): okhttp3.OkHttpClient? {
        if (!enabled(c)) return null
        return try {
            val port = start(c)
            okCached?.let { if (it.first == port) return it.second }
            val cl = okhttp3.OkHttpClient.Builder()
                .proxy(proxy(c))
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true).followSslRedirects(true)
                .build()
            okCached = port to cl
            cl
        } catch (e: Exception) { null }
    }

    /** Conexión HTTP(S) simple (listas, resolutor de páginas) que usa el intermediario si está activado. */
    fun openConnection(url: String): java.net.HttpURLConnection {
        val c = appCtx
        val u = java.net.URL(url)
        return (if (c != null && enabled(c)) u.openConnection(proxy(c)) else u.openConnection()) as java.net.HttpURLConnection
    }

    /** Arranca el intermediario (si no lo estaba) y devuelve su puerto. */
    @Synchronized
    fun start(c: Context): Int {
        appCtx = c.applicationContext
        server?.let { if (!it.isClosed) return it.localPort }
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = ss
        Thread({
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                pool.execute { try { handle(s) } catch (e: Throwable) { try { s.close() } catch (x: Throwable) { } } }
            }
        }, "netshield-accept").apply { isDaemon = true }.start()
        return ss.localPort
    }

    private fun lookup(host: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        dnsCache[host]?.let { if (it.second > now) return it.first }
        val ctx = appCtx ?: return InetAddress.getAllByName(host).toList()
        val r = AppDns.resolve(ctx, host)
        dnsCache[host] = r to now + 120_000
        return r
    }


    private val good = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val logLines = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private fun note(t: String) {
        logLines.add(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + t)
        while (logLines.size > 60) logLines.poll()
    }

    fun logText(): String = logLines.toList().asReversed().joinToString("\n").ifEmpty { "(sin actividad todavía)" }

    /**
     * Modos de enviar el primer mensaje TLS: 0 = partido en dos paquetes TCP por la mitad del nombre; 1 = partido en dos
     * registros TLS (y dos paquetes); 2 = registro TLS de 1 byte + resto; 3 = sin trucos (solo DNS seguro).
     */
    private fun sendHello(out: OutputStream, hello: ByteArray, host: String, mode: Int) {
        val n = hello.size
        val recLen = ((hello[3].toInt() and 0xff) shl 8) or (hello[4].toInt() and 0xff)
        var sni = -1
        val hb = host.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..n - hb.size) {
            for (j in hb.indices) if (hello[i + j] != hb[j]) continue@outer
            sni = i + hb.size / 2
            break
        }
        fun pause() { try { Thread.sleep(40) } catch (e: Exception) { } }
        when (mode) {
            0 -> {
                val cut = if (sni in 1 until n) sni else minOf(n - 1, 40)
                out.write(hello, 0, cut); out.flush(); pause()
                out.write(hello, cut, n - cut); out.flush()
            }
            1, 2 -> {
                if (5 + recLen > n) { out.write(hello); out.flush(); return }
                val k = if (mode == 2) 1 else (if (sni > 6) sni - 5 else 20).coerceIn(1, recLen - 1)
                fun rec(from: Int, to: Int): ByteArray {
                    val len = to - from
                    val b = ByteArray(5 + len)
                    b[0] = hello[0]; b[1] = hello[1]; b[2] = hello[2]
                    b[3] = (len shr 8).toByte(); b[4] = len.toByte()
                    System.arraycopy(hello, 5 + from, b, 5, len)
                    return b
                }
                out.write(rec(0, k)); out.flush(); pause()
                out.write(rec(k, recLen)); out.flush()
                if (5 + recLen < n) { out.write(hello, 5 + recLen, n - 5 - recLen); out.flush() }
            }
            else -> { out.write(hello); out.flush() }
        }
    }

    private fun readHead(inp: InputStream): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16384) {
            val b = inp.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toByteArray()
        }
        return null
    }

    private val ipCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    private fun allIps(host: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        ipCache[host]?.let { if (it.second > now) return it.first }
        val ctx = appCtx
        val r = (if (ctx != null) AppDns.resolveAll(ctx, host) else InetAddress.getAllByName(host).toList())
            .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
        if (r.isNotEmpty()) ipCache[host] = r to now + 120_000
        return r
    }

    private fun connect(host: String, port: Int): Socket {
        var err: Exception? = null
        for (a in lookup(host).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(a, port), 8000)
                return s
            } catch (e: Exception) { err = e; try { s.close() } catch (x: Exception) { } }
        }
        throw err ?: java.io.IOException("sin direccion")
    }

    private fun handle(client: Socket) {
        client.tcpNoDelay = true
        val cin = client.getInputStream()
        val cout = client.getOutputStream()
        val head = readHead(cin) ?: run { client.close(); return }
        val text = String(head, Charsets.ISO_8859_1)
        val first = text.substringBefore("\r\n")
        val parts = first.split(' ')
        if (parts.size < 3) { client.close(); return }
        if (parts[0].equals("CONNECT", true)) {
            val host = parts[1].substringBeforeLast(':').trim('[', ']')
            val port = parts[1].substringAfterLast(':').toIntOrNull() ?: 443
            cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); cout.flush()
            val first0 = ByteArray(16384)
            val n = cin.read(first0)
            if (n <= 0) { client.close(); return }
            val hello = first0.copyOf(n)
            val isTls = hello[0].toInt() == 0x16 && n > 9
            if (!isTls) {
                val remote = try { connect(host, port) } catch (e: Exception) { client.close(); return }
                remote.getOutputStream().write(hello); remote.getOutputStream().flush()
                pipe(client, remote)
                return
            }
            // TLS: se prueban varias direcciones del servidor y varias formas de enviar el primer mensaje hasta que responda.
            val order = LinkedHashSet<Int>()
            good[host]?.let { order.add(it) }
            order.addAll(listOf(1, 0, 3))
            val ips = allIps(host)
            note("$host -> " + ips.take(6).joinToString(",") { it.hostAddress ?: "?" })
            var tries = 0
            for (ip in ips.take(6)) {
                for (st in order) {
                    if (tries++ > 12) break
                    val remote = Socket()
                    val ok = try {
                        remote.tcpNoDelay = true
                        remote.connect(InetSocketAddress(ip, port), 5000)
                        true
                    } catch (e: Exception) { false }
                    if (!ok) { note("$host ${ip.hostAddress}: sin conexion TCP"); try { remote.close() } catch (e: Exception) { }; break }
                    val resp = ByteArray(16384)
                    val r = try {
                        sendHello(remote.getOutputStream(), hello, host, st)
                        remote.soTimeout = 4000
                        remote.getInputStream().read(resp)
                    } catch (e: Exception) { -1 }
                    if (r > 0) {
                        remote.soTimeout = 0
                        good[host] = st
                        note("$host ${ip.hostAddress}: OK (modo $st)")
                        cout.write(resp, 0, r); cout.flush()
                        pipe(client, remote)
                        return
                    }
                    note("$host ${ip.hostAddress}: fallo (modo $st)")
                    try { remote.close() } catch (e: Exception) { }
                }
            }
            client.close()
        } else {
            // HTTP normal: se reenvía como petición de origen, una por conexión.
            val m = Regex("^(\\w+) (\\S+) (HTTP/\\d\\.\\d)$").find(first) ?: run { client.close(); return }
            val uri = try { java.net.URI(m.groupValues[2]) } catch (e: Exception) { client.close(); return }
            val host = uri.host ?: run { client.close(); return }
            val port = if (uri.port > 0) uri.port else 80
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (if (uri.rawQuery != null) "?" + uri.rawQuery else "")
            val hdrs = text.substringAfter("\r\n").split("\r\n").filter {
                it.isNotEmpty() && !it.startsWith("Proxy-Connection", true) && !it.startsWith("Connection", true)
            }
            val req = (m.groupValues[1] + " " + path + " " + m.groupValues[3] + "\r\n" + hdrs.joinToString("\r\n") + "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
            val remote = try { connect(host, port) } catch (e: Exception) {
                cout.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); client.close(); return
            }
            remote.getOutputStream().write(req); remote.getOutputStream().flush()
            pipe(client, remote)
        }
    }

    private fun pipe(a: Socket, b: Socket) {
        fun copy(from: Socket, to: Socket) {
            try {
                val i = from.getInputStream()
                val o = to.getOutputStream()
                val buf = ByteArray(16384)
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() }
            } catch (e: Throwable) { }
            try { a.close() } catch (e: Throwable) { }
            try { b.close() } catch (e: Throwable) { }
        }
        pool.execute { copy(b, a) }
        copy(a, b)
    }
}
