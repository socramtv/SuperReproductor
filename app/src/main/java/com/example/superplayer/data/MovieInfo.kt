package com.example.superplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Carátula, sinopsis, año y género de una película (o serie) a partir de su
 * título, para el apartado Cine. Se busca en servicios públicos que no piden
 * clave: la búsqueda de películas de iTunes (en español de España) y, si no
 * aparece, TVMaze (series). Lo encontrado -y también lo "no encontrado"- se
 * guarda en el móvil para no volver a preguntar.
 *
 * Las APIs son de terceros y no se han podido probar desde el entorno de
 * desarrollo; el parseo es tolerante y, si algo falla, la pantalla Cine usa
 * el logo que traiga la propia lista.
 */
object MovieInfo {
    private const val PREFS_NAME = "movie_info"
    private const val MISSING_RETRY_MS = 7L * 24 * 3_600_000L

    data class Info(
        val title: String,
        val year: String?,
        val genre: String?,
        val overview: String?,
        val posterUrl: String?
    )

    private val pool = Executors.newFixedThreadPool(3)
    private val waiting = HashMap<String, MutableList<(Info?) -> Unit>>()
    private val memory = HashMap<String, Info?>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -----------------------------------------------------------------
    // Título limpio
    // -----------------------------------------------------------------

    private val QUALITY_TAGS = Regex(
        "\\b(4K|UHD|FHD|HD|SD|HDR|HDR10|MULTI|VOSE|VOS|SUB|SUBS|LAT|CAST|ESP|DUAL|1080p|720p|480p|2160p|x264|x265|HEVC|WEB-?DL|BLURAY|BDRIP)\\b",
        RegexOption.IGNORE_CASE
    )

    /** (título limpio, año si lo trae): quita prefijos de idioma, etiquetas de calidad, corchetes y el año. */
    fun cleanTitle(raw: String): Pair<String, String?> {
        var text = raw.trim()
        var year: String? = null
        Regex("[\\(\\[]\\s*((?:19|20)\\d{2})\\s*[\\)\\]]").find(text)?.let { year = it.groupValues[1] }
        text = text.replace(Regex("\\[[^\\]]*\\]"), " ").replace(Regex("\\([^)]*\\)"), " ")
        text = text.replace(Regex("^\\s*\\|?[A-Za-z]{2,3}\\|?\\s*[-:|]\\s+"), "")
        if (year == null) {
            Regex("\\s((?:19|20)\\d{2})\\s*$").find(text)?.let {
                year = it.groupValues[1]
                text = text.removeRange(it.range)
            }
        }
        text = QUALITY_TAGS.replace(text, " ")
        text = text.replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', '_', '.', ':', '|')
        return (if (text.isEmpty()) raw.trim() else text) to year
    }

    private fun keyOf(cleanTitle: String, year: String?): String =
        Normalizer.normalize(cleanTitle.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim() + (if (year != null) "|$year" else "")

    // -----------------------------------------------------------------
    // Caché
    // -----------------------------------------------------------------

    /** Lo que ya se sabe de [rawTitle] sin red: Info si se encontró, null si no se sabe (o no existe: ver [isKnownMissing]). */
    fun peek(context: Context, rawTitle: String): Info? {
        val (title, year) = cleanTitle(rawTitle)
        val key = keyOf(title, year)
        synchronized(memory) {
            if (memory.containsKey(key)) return memory[key]
        }
        val raw = prefs(context).getString(key, null) ?: return null
        val info = parseStored(raw)
        synchronized(memory) { memory[key] = info }
        return info
    }

    /** true si ya se buscó hace poco y no se encontró nada (para no insistir). */
    fun isKnownMissing(context: Context, rawTitle: String): Boolean {
        val (title, year) = cleanTitle(rawTitle)
        val key = keyOf(title, year)
        synchronized(memory) {
            if (memory.containsKey(key) && memory[key] == null) return true
        }
        val raw = prefs(context).getString(key, null) ?: return false
        return try {
            val o = JSONObject(raw)
            o.has("none") && System.currentTimeMillis() - o.optLong("none") < MISSING_RETRY_MS
        } catch (e: Exception) {
            false
        }
    }

    private fun parseStored(raw: String): Info? = try {
        val o = JSONObject(raw)
        if (o.has("none")) null
        else Info(
            o.optString("t"),
            o.optString("y").ifEmpty { null },
            o.optString("g").ifEmpty { null },
            o.optString("o").ifEmpty { null },
            o.optString("p").ifEmpty { null }
        )
    } catch (e: Exception) {
        null
    }

    private fun store(context: Context, key: String, info: Info?) {
        synchronized(memory) { memory[key] = info }
        val json = if (info == null) JSONObject().put("none", System.currentTimeMillis())
        else JSONObject().put("t", info.title).put("y", info.year ?: "").put("g", info.genre ?: "")
            .put("o", info.overview ?: "").put("p", info.posterUrl ?: "")
        prefs(context).edit().putString(key, json.toString()).apply()
    }

    // -----------------------------------------------------------------
    // Petición
    // -----------------------------------------------------------------

    /**
     * Busca [rawTitle] en segundo plano (hasta 3 a la vez, sin repetir las
     * que ya van en camino) y llama a [onDone] -desde un hilo de fondo- con
     * el resultado, o null si no se encontró.
     */
    fun request(context: Context, rawTitle: String, onDone: (Info?) -> Unit) {
        val appContext = context.applicationContext
        val (title, year) = cleanTitle(rawTitle)
        val key = keyOf(title, year)
        synchronized(waiting) {
            val list = waiting[key]
            if (list != null) {
                list.add(onDone)
                return
            }
            waiting[key] = mutableListOf(onDone)
        }
        pool.execute {
            val info = try { search(title, year) } catch (e: Exception) { null }
            store(appContext, key, info)
            val callbacks = synchronized(waiting) { waiting.remove(key) } ?: emptyList()
            for (cb in callbacks) {
                try { cb(info) } catch (e: Exception) { }
            }
        }
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun norm(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun search(title: String, year: String?): Info? {
        return searchMovie(title, year) ?: searchShow(title)
    }

    private fun searchMovie(title: String, year: String?): Info? {
        val term = URLEncoder.encode(title, "UTF-8")
        val body = httpGet(
            "https://itunes.apple.com/search?term=$term&media=movie&entity=movie&country=ES&lang=es_es&limit=8"
        ) ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        val wanted = norm(title)
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val name = norm(r.optString("trackName"))
            if (name.isEmpty()) continue
            var score = 0
            if (name == wanted) score += 10 else if (name.contains(wanted) || wanted.contains(name)) score += 4 else continue
            if (year != null && r.optString("releaseDate").startsWith(year)) score += 3
            if (score > bestScore) {
                bestScore = score
                best = r
            }
        }
        val r = best ?: return null
        val poster = r.optString("artworkUrl100").ifEmpty { null }?.replace("100x100bb", "600x600bb")
        val overview = r.optString("longDescription").ifEmpty { r.optString("shortDescription") }.ifEmpty { null }
        return Info(
            r.optString("trackName"),
            r.optString("releaseDate").take(4).ifEmpty { null },
            r.optString("primaryGenreName").ifEmpty { null },
            overview,
            poster
        )
    }

    private fun searchShow(title: String): Info? {
        val term = URLEncoder.encode(title, "UTF-8")
        val body = httpGet("https://api.tvmaze.com/singlesearch/shows?q=$term") ?: return null
        val o = JSONObject(body)
        val name = o.optString("name")
        if (name.isEmpty()) return null
        val wanted = norm(title)
        val found = norm(name)
        if (!(found == wanted || found.contains(wanted) || wanted.contains(found))) return null
        val image = o.optJSONObject("image")
        val poster = image?.optString("original")?.ifEmpty { null } ?: image?.optString("medium")?.ifEmpty { null }
        val summary = o.optString("summary").ifEmpty { null }?.let {
            android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
        }
        val genres = o.optJSONArray("genres")
        val genre = if (genres != null && genres.length() > 0) (0 until genres.length()).joinToString(", ") { genres.optString(it) } else null
        return Info(name, o.optString("premiered").take(4).ifEmpty { null }, genre, summary, poster)
    }
}
