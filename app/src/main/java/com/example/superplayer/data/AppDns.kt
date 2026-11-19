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
        QUAD9("Quad9 9.9.9.9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
        MULLVAD("Mullvad (sin anuncios)", "https://dns.mullvad.net/dns-query", listOf("194.242.2.2")),
        CUSTOM("Personalizado (tu URL DoH)", null, emptyList())
    }

    /** Nombre de host para el «DNS privado» de Android (Ajustes), según el proveedor. */
    fun privateDnsHost(p: Provider): String? = when (p) {
        Provider.CLOUDFLARE -> "one.one.one.one"
        Provider.GOOGLE -> "dns.google"
        Provider.ADGUARD -> "dns.adguard-dns.com"
        Provider.QUAD9 -> "dns.quad9.net"
        Provider.MULLVAD -> "dns.mullvad.net"
        else -> null
    }

    @Volatile private var ctx: Context? = null
    fun attach(c: Context) { ctx = c.applicationContext }
    /** Cliente con el DNS elegido (para descargas de listas), o null si es el del sistema. */
    fun clientOrNull(): OkHttpClient? = ctx?.let { try { client(it) } catch (e: Exception) { null } }

    fun customUrl(c: Context): String = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("dns_custom_url", "") ?: ""
    fun setCustomUrl(c: Context, u: String) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("dns_custom_url", u.trim()).apply()
        cached = null
    }

    /** Milisegundos que tarda un DNS en resolver [host] (-1 si falla). Para el botón «Probar». */
    fun probe(c: Context, p: Provider, host: String): Long {
        val t0 = System.currentTimeMillis()
        return try {
            if (p == Provider.SYSTEM) { InetAddress.getAllByName(host); System.currentTimeMillis() - t0 }
            else {
                val url = if (p == Provider.CUSTOM) customUrl(c) else p.url
                if (url.isNullOrBlank()) return -1
                val b = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
                val bd = DnsOverHttps.Builder().client(b).url(url.toHttpUrl())
                if (p.bootstrap.isNotEmpty()) bd.bootstrapDnsHosts(p.bootstrap.map { InetAddress.getByName(it) })
                val r = bd.build().lookup(host)
                if (r.isEmpty()) -1 else System.currentTimeMillis() - t0
            }
        } catch (e: Exception) { -1 }
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
        ctx = context.applicationContext
        val p = current(context)
        val url = (if (p == Provider.CUSTOM) customUrl(context).takeIf { it.isNotBlank() } else p.url) ?: return null
        cached?.let { if (it.first == p && !(p == Provider.CUSTOM)) return it.second }
        val bootstrap = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        val doh = DnsOverHttps.Builder()
            .client(bootstrap)
            .url(url.toHttpUrl())
            .apply { if (p.bootstrap.isNotEmpty()) bootstrapDnsHosts(p.bootstrap.map { InetAddress.getByName(it) }) }
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
