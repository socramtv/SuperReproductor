package com.example.superplayer.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

/** Una tele (u otro aparato con la app) a la que se puede mandar órdenes. [pin] puede faltar hasta que se escriba. */
data class RemoteDevice(val name: String, val host: String, val port: Int, val pin: String?) {
    val key: String get() = "$host:$port"
}

/**
 * Lado del móvil-mando: guarda las teles conocidas, las busca en la red
 * (NSD) y les manda órdenes por HTTP (ver RemoteServer, que es el otro lado).
 */
object RemoteClient {
    private const val PREFS = "remote_devices"
    private const val SERVICE_TYPE = "_socramtv._tcp."

    // ---- Teles guardadas ----

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedDevices(context: Context): List<RemoteDevice> {
        val raw = prefs(context).getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                RemoteDevice(o.getString("name"), o.getString("host"), o.getInt("port"), o.optString("pin").ifEmpty { null })
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveDevice(context: Context, device: RemoteDevice) {
        val list = savedDevices(context).filter { it.key != device.key } + device
        val arr = JSONArray()
        for (d in list) arr.put(JSONObject().put("name", d.name).put("host", d.host).put("port", d.port).put("pin", d.pin ?: ""))
        prefs(context).edit().putString("list", arr.toString()).putString("last", device.key).apply()
    }

    fun forgetDevice(context: Context, device: RemoteDevice) {
        val arr = JSONArray()
        for (d in savedDevices(context).filter { it.key != device.key }) {
            arr.put(JSONObject().put("name", d.name).put("host", d.host).put("port", d.port).put("pin", d.pin ?: ""))
        }
        prefs(context).edit().putString("list", arr.toString()).apply()
    }

    /** La última tele usada, si sigue guardada. */
    fun lastDevice(context: Context): RemoteDevice? {
        val key = prefs(context).getString("last", null) ?: return null
        return savedDevices(context).firstOrNull { it.key == key }
    }

    // ---- Órdenes (bloquean: llamar desde un hilo de fondo) ----

    /** (código HTTP, cuerpo). Código -1 si no se pudo conectar. */
    fun call(device: RemoteDevice, path: String, body: String? = null): Pair<Int, String> {
        return try {
            val conn = URL("http://${device.host}:${device.port}$path").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 3_000
                conn.readTimeout = 4_000
                if (device.pin != null) conn.setRequestProperty("X-Pin", device.pin)
                if (body != null) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                code to text
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            -1 to ""
        }
    }

    // ---- Búsqueda en la red ----

    /**
     * Busca teles con la app abierta en la misma red. [onFound] se llama
     * desde un hilo del sistema por cada una que se encuentra y resuelve.
     * Hay que llamar a [stop] al terminar.
     */
    class Discovery(context: Context, private val onFound: (RemoteDevice) -> Unit) {
        private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var resolving = false
        private var discovery: NsdManager.DiscoveryListener? = null

        @Suppress("DEPRECATION")
        private fun resolveNext() {
            val next: NsdServiceInfo? = synchronized(queue) {
                if (resolving || queue.isEmpty()) {
                    null
                } else {
                    resolving = true
                    queue.poll()
                }
            }
            if (next == null) return
            try {
                nsd.resolveService(next, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        synchronized(queue) { resolving = false }
                        resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress
                        if (host != null) {
                            onFound(
                                RemoteDevice(
                                    serviceInfo.serviceName.removePrefix("SocramTV-"),
                                    host, serviceInfo.port, null
                                )
                            )
                        }
                        synchronized(queue) { resolving = false }
                        resolveNext()
                    }
                })
            } catch (e: Exception) {
                synchronized(queue) { resolving = false }
            }
        }

        fun start() {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    synchronized(queue) { queue.add(serviceInfo) }
                    resolveNext()
                }
            }
            discovery = listener
            try {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                discovery = null
            }
        }

        fun stop() {
            val listener = discovery ?: return
            discovery = null
            try { nsd.stopServiceDiscovery(listener) } catch (e: Exception) { }
        }
    }
}
