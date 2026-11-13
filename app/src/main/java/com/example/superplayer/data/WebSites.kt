package com.example.superplayer.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * «Webs recomendadas» del navegador: una lista que publica el administrador y que aparece en la app de todos.
 * Vive en `webs.json` junto al `acceso.json` (mismo repositorio de GitHub). Los usuarios solo LEEN la dirección «raw»
 * (sin cuenta ni token); el administrador la edita desde la app con el mismo token personal que ya usa el registro de
 * accesos (ver AccessAdmin), que solo se guarda en su móvil.
 *
 * Formato: {"version":1,"sites":[{"name":"…","url":"https://…","folder":"…(opcional)"}]}
 */
object WebSites {
    class Site(val name: String, val url: String, val folder: String = "")
    class Loaded(val sites: List<Site>, val sha: String?)

    private const val PREFS = "web_sites"
    private const val K_CACHE = "cache"
    private const val FILE = "webs.json"

    fun parse(text: String): List<Site> = try {
        val a = JSONObject(text).optJSONArray("sites") ?: JSONArray()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("url").trim()
            val n = o.optString("name").trim()
            if (u.isEmpty() || n.isEmpty()) null else Site(n, u, o.optString("folder").trim())
        }
    } catch (e: Exception) { emptyList() }

    fun toJson(list: List<Site>): String {
        val a = JSONArray()
        for (s in list) a.put(JSONObject().put("name", s.name).put("url", s.url).apply { if (s.folder.isNotEmpty()) put("folder", s.folder) })
        return JSONObject().put("version", 1).put("sites", a).toString(2)
    }

    private fun remote(c: Context): AccessAdmin.Remote? {
        val r = AccessAdmin.parse(AccessControl.url(c)) ?: return null
        val dir = r.path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        return AccessAdmin.Remote(r.owner, r.repo, r.branch, dir + FILE)
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Última lista descargada (o vacía). */
    fun cached(c: Context): List<Site> = parse(prefs(c).getString(K_CACHE, "") ?: "")

    /** Descarga la lista pública en segundo plano y la guarda. [done] se llama en el hilo de fondo con la lista (o null si falló). */
    fun refresh(c: Context, done: (List<Site>?) -> Unit) {
        Thread {
            val r = remote(c)
            if (r == null) { done(null); return@Thread }
            var res: List<Site>? = null
            try {
                val url = "https://raw.githubusercontent.com/${r.owner}/${r.repo}/refs/heads/${r.branch}/${r.path}?t=${System.currentTimeMillis() / 60000}"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", "SocramTV")
                try {
                    if (conn.responseCode in 200..299) {
                        val text = conn.inputStream.bufferedReader().readText()
                        prefs(c).edit().putString(K_CACHE, text).apply()
                        res = parse(text)
                    } else if (conn.responseCode == 404) {
                        prefs(c).edit().putString(K_CACHE, "").apply() // todavía no se ha publicado nada
                        res = emptyList()
                    }
                } finally { conn.disconnect() }
            } catch (e: Exception) { }
            done(res)
        }.start()
    }

    // ---- Administrador (hilo de fondo) ----

    private fun api(c: Context, method: String, r: AccessAdmin.Remote): HttpURLConnection {
        val conn = URL("https://api.github.com/repos/${r.owner}/${r.repo}/contents/${r.path}" + if (method == "GET") "?ref=${r.branch}" else "")
            .openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer ${AccessAdmin.token(c)}")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "SocramTV")
        return conn
    }

    private fun failure(conn: HttpURLConnection): AccessAdmin.Failure {
        val body = try { (conn.errorStream ?: conn.inputStream).bufferedReader().readText() } catch (e: Exception) { "" }
        val msg = try { JSONObject(body).optString("message") } catch (e: Exception) { "" }
        return AccessAdmin.Failure(conn.responseCode, msg.ifBlank { "HTTP ${conn.responseCode}" })
    }

    /** Lee la lista y el sha actuales por la API (sin caché). Si el archivo no existe aún, devuelve lista vacía y sha null. */
    fun fetchAdmin(c: Context): Loaded {
        val r = remote(c) ?: throw AccessAdmin.Failure(0, "URL de acceso no reconocida")
        val conn = api(c, "GET", r)
        try {
            if (conn.responseCode == 404) return Loaded(emptyList(), null)
            if (conn.responseCode !in 200..299) throw failure(conn)
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val text = String(Base64.decode(o.getString("content").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
            return Loaded(parse(text), o.getString("sha"))
        } finally { conn.disconnect() }
    }

    /** Publica la lista (crea el archivo si no existe). Devuelve el sha nuevo y actualiza la copia local. */
    fun publish(c: Context, list: List<Site>, sha: String?): String {
        val r = remote(c) ?: throw AccessAdmin.Failure(0, "URL de acceso no reconocida")
        val text = toJson(list)
        val body = JSONObject()
            .put("message", "Webs recomendadas")
            .put("content", Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("branch", r.branch)
            .apply { if (sha != null) put("sha", sha) }
            .toString()
        val conn = api(c, "PUT", r)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw failure(conn)
            prefs(c).edit().putString(K_CACHE, text).apply()
            return JSONObject(conn.inputStream.bufferedReader().readText()).getJSONObject("content").getString("sha")
        } finally { conn.disconnect() }
    }
}
