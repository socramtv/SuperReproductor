package com.example.superplayer.data

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lee y escribe el `acceso.json` en GitHub con la API de contenidos, usando un token personal que se guarda solo en
 * ESTE dispositivo (el del administrador). Lo usa la pantalla de registro (ui/AccessLogActivity).
 * Token recomendado: «fine-grained», solo para el repositorio del archivo, permiso Contents: Read and write.
 */
object AccessAdmin {
    private const val PREFS = "access_admin"
    private const val K_TOKEN = "token"

    class Remote(val owner: String, val repo: String, val branch: String, val path: String)
    class Loaded(val text: String, val sha: String)
    class Failure(val code: Int, message: String) : Exception(message)

    fun token(c: Context): String = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_TOKEN, "").orEmpty()

    fun setToken(c: Context, t: String) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_TOKEN, t.trim()).apply()
    }

    /** Saca propietario, repositorio, rama y ruta de una URL «raw» de GitHub. */
    fun parse(url: String): Remote? {
        return try {
            val u = URL(url)
            if (u.host != "raw.githubusercontent.com") return null
            val parts = u.path.trim('/').split('/')
            if (parts.size < 4) return null
            var i = 2
            // .../refs/heads/<rama>/<ruta>
            val branch: String
            if (parts[2] == "refs" && parts.size >= 6 && parts[3] == "heads") { branch = parts[4]; i = 5 } else { branch = parts[2]; i = 3 }
            Remote(parts[0], parts[1], branch, parts.subList(i, parts.size).joinToString("/"))
        } catch (e: Exception) {
            null
        }
    }

    private fun open(method: String, url: String, token: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "SocramTV")
        return conn
    }

    private fun fail(conn: HttpURLConnection): Failure {
        val body = try { (conn.errorStream ?: conn.inputStream).bufferedReader().readText() } catch (e: Exception) { "" }
        val msg = try { JSONObject(body).optString("message") } catch (e: Exception) { "" }
        return Failure(conn.responseCode, msg.ifBlank { "HTTP ${conn.responseCode}" })
    }

    /** Descarga el archivo actual (sin caché de «raw»). Bloquea: hilo de fondo. */
    fun fetch(c: Context): Loaded {
        val token = token(c)
        val r = parse(AccessControl.url(c)) ?: throw Failure(0, "URL no reconocida")
        val conn = open("GET", "https://api.github.com/repos/${r.owner}/${r.repo}/contents/${r.path}?ref=${r.branch}", token)
        try {
            if (conn.responseCode !in 200..299) throw fail(conn)
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val text = String(Base64.decode(o.getString("content").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
            return Loaded(text, o.getString("sha"))
        } finally {
            conn.disconnect()
        }
    }

    /** Sube el archivo nuevo. Devuelve el sha nuevo. Bloquea: hilo de fondo. */
    fun publish(c: Context, text: String, sha: String, message: String): String {
        val token = token(c)
        val r = parse(AccessControl.url(c)) ?: throw Failure(0, "URL no reconocida")
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("sha", sha)
            .put("branch", r.branch)
            .toString()
        val conn = open("PUT", "https://api.github.com/repos/${r.owner}/${r.repo}/contents/${r.path}", token)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw fail(conn)
            return JSONObject(conn.inputStream.bufferedReader().readText()).getJSONObject("content").getString("sha")
        } finally {
            conn.disconnect()
        }
    }
}
