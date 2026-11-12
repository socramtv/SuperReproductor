package com.example.superplayer.data

import android.content.Context
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * DNS de la app para los streams: «Sistema» (por defecto, el del móvil/router) o un DNS público por HTTPS
 * (Cloudflare, Google, AdGuard, Quad9). Sirve cuando el DNS de la operadora no resuelve o bloquea un servidor
 * de vídeo. Afecta al reproductor; el navegador de la app y las descargas de listas usan el DNS del sistema.
 */
object AppDns {
    enum class Provider(val label: String, val url: String?, val bootstrap: List<String>) {
        SYSTEM("Sistema (por defecto)", null, emptyList()),
        CLOUDFLARE("Cloudflare 1.1.1.1", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
        GOOGLE("Google 8.8.8.8", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
        ADGUARD("AdGuard (sin anuncios)", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15")),
        QUAD9("Quad9 9.9.9.9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112"))
    }

    private const val PREFS = "app_prefs"
    private const val KEY = "dns_provider"
    @Volatile private var cached: Pair<Provider, OkHttpClient>? = null

    fun current(context: Context): Provider {
        val n = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return Provider.values().firstOrNull { it.name == n } ?: Provider.SYSTEM
    }

    fun set(context: Context, p: Provider) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, p.name).apply()
        cached = null
    }

    /** Cliente OkHttp con el DNS elegido, o null si se usa el del sistema. */
    fun client(context: Context): OkHttpClient? {
        val p = current(context)
        val url = p.url ?: return null
        cached?.let { if (it.first == p) return it.second }
        val bootstrap = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        val doh = DnsOverHttps.Builder()
            .client(bootstrap)
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(p.bootstrap.map { InetAddress.getByName(it) })
            .build()
        // Si el DNS elegido falla (sin red hacia él), se cae al del sistema en vez de quedarse sin canal.
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                try { doh.lookup(hostname) } catch (e: Exception) { Dns.SYSTEM.lookup(hostname) }
        }
        val c = OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        cached = p to c
        return c
    }
}
