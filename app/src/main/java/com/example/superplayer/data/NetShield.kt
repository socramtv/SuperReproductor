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
                pool.execute { try { handle(s) } catch (e: Exception) { try { s.close() } catch (x: Exception) { } } }
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
            val remote = try { connect(host, port) } catch (e: Exception) {
                cout.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); client.close(); return
            }
            cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); cout.flush()
            // Primer mensaje del cliente (el «ClientHello» de TLS): se parte por la mitad del nombre del servidor.
            val first0 = ByteArray(16384)
            val n = cin.read(first0)
            if (n <= 0) { client.close(); remote.close(); return }
            val out = remote.getOutputStream()
            var cut = -1
            if (port == 443 && n > 6 && first0[0].toInt() == 0x16) {
                val hb = host.toByteArray(Charsets.ISO_8859_1)
                outer@ for (i in 0..n - hb.size) {
                    for (j in hb.indices) if (first0[i + j] != hb[j]) continue@outer
                    cut = i + hb.size / 2
                    break
                }
                if (cut < 0) cut = minOf(n - 1, 40)
            }
            if (cut in 1 until n) {
                out.write(first0, 0, cut); out.flush()
                try { Thread.sleep(40) } catch (e: Exception) { }
                out.write(first0, cut, n - cut); out.flush()
            } else { out.write(first0, 0, n); out.flush() }
            pipe(client, remote)
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
        fun copy(i: InputStream, o: OutputStream) {
            try {
                val buf = ByteArray(16384)
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() }
            } catch (e: Exception) { }
            try { a.close() } catch (e: Exception) { }
            try { b.close() } catch (e: Exception) { }
        }
        pool.execute { copy(b.getInputStream(), a.getOutputStream()) }
        copy(a.getInputStream(), b.getOutputStream())
    }
}
