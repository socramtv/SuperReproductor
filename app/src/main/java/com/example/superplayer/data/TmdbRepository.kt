package com.example.superplayer.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.superplayer.BuildConfig
import com.example.superplayer.model.Stream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Información extra de películas (póster, año, nota, sinopsis) sacada de
 * TMDb (themoviedb.org), para las listas que traen películas. Ver el
 * apartado "Información de películas (TMDb)" del README.
 *
 * - Detección ([isMovieCandidate]): una lista no declara "soy de
 *   películas", así que se adivina por canal: la URL apunta a un archivo
 *   de vídeo (.mp4/.mkv/...) o a una ruta tipo "/movie/", o bien la
 *   categoría se llama "Películas"/"Movies"/"Cine"... y la URL no es un
 *   directo (.m3u8/.mpd). Los canales de TV normales no se tocan.
 * - Se busca por el nombre ya limpio (sin calidad, idioma, corchetes...) y,
 *   si el nombre trae año, también por año.
 * - Resultados (también los "no encontrado") se guardan en disco, así que
 *   cada película se consulta a TMDb una sola vez.
 * - Si la clave está vacía, o falla la red, la app sigue igual que antes,
 *   solo sin ese dato extra (los errores se ignoran a propósito).
 */
object TmdbRepository {

    data class MovieInfo(
        val title: String,
        val year: String?,
        val rating: Double?,
        val overview: String?,
        val posterUrl: String?
    )

    private const val PREFS = "tmdb_cache"
    private const val POSTER_BASE = "https://image.tmdb.org/t/p/w185"
    private const val NOT_FOUND = "-"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private val memory = ConcurrentHashMap<String, MovieInfo>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    val enabled: Boolean get() = BuildConfig.TMDB_API_KEY.isNotBlank()

    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "wmv", "flv", "mpg", "mpeg")
    private val liveExtensions = setOf("m3u8", "mpd", "ts")
    private val categoryWords = listOf("pelicula", "película", "movie", "film", "cine", "vod", "estreno")

    fun isMovieCandidate(stream: Stream): Boolean {
        if (!enabled) return false
        val path = stream.url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        val ext = path.substringAfterLast('.', "")
        if (ext in videoExtensions) return true
        if (path.contains("/movie/") || path.contains("/movies/")) return true
        val cat = stream.category.lowercase(Locale.ROOT)
        return ext !in liveExtensions && categoryWords.any { cat.contains(it) }
    }

    /** Dato ya conocido (memoria/disco) sin ir a la red; null si no hay o aún no se ha consultado. */
    fun cached(context: Context, stream: Stream): MovieInfo? {
        val key = cleanTitle(stream.name).key
        memory[key]?.let { return it }
        if (key in missing) return null
        val raw = prefs(context).getString(key, null) ?: return null
        if (raw == NOT_FOUND) {
            missing.add(key)
            return null
        }
        return try {
            val info = fromJson(JSONObject(raw))
            memory[key] = info
            info
        } catch (e: Exception) {
            null
        }
    }

    /** True si ya se sabe (con o sin resultado) y no hace falta llamar a [request]. */
    fun isResolved(context: Context, stream: Stream): Boolean {
        val key = cleanTitle(stream.name).key
        if (memory.containsKey(key) || missing.contains(key)) return true
        return prefs(context).contains(key)
    }

    /** Consulta TMDb en segundo plano (una sola vez por película) y avisa en el hilo principal al terminar. */
    fun request(context: Context, stream: Stream, onDone: () -> Unit) {
        if (!enabled || isResolved(context, stream)) return
        val cleaned = cleanTitle(stream.name)
        if (cleaned.title.isBlank() || !inFlight.add(cleaned.key)) return
        val appContext = context.applicationContext
        executor.execute {
            try {
                val info = search(cleaned)
                if (info != null) {
                    memory[cleaned.key] = info
                    prefs(appContext).edit().putString(cleaned.key, toJson(info).toString()).apply()
                } else {
                    missing.add(cleaned.key)
                    prefs(appContext).edit().putString(cleaned.key, NOT_FOUND).apply()
                }
                mainHandler.post(onDone)
            } catch (e: Exception) {
                // Sin red / límite / formato inesperado: sin dato por ahora;
                // no se guarda nada para poder reintentar en otra ocasión.
            } finally {
                inFlight.remove(cleaned.key)
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun search(cleaned: Cleaned): MovieInfo? {
        val query = URLEncoder.encode(cleaned.title, "UTF-8")
        val yearPart = cleaned.year?.let { "&year=$it" }.orEmpty()
        val url = "https://api.themoviedb.org/3/search/movie?api_key=${BuildConfig.TMDB_API_KEY}" +
            "&language=es-ES&include_adult=false&query=$query$yearPart"
        val results = JSONObject(httpGet(url)).optJSONArray("results")
        if (results == null || results.length() == 0) {
            // Con año erróneo en el nombre del archivo no sale nada: un
            // segundo intento sin año antes de darlo por no encontrado.
            if (cleaned.year != null) return search(cleaned.copy(year = null))
            return null
        }
        val first = results.getJSONObject(0)
        val release = first.optString("release_date").takeIf { it.length >= 4 }?.substring(0, 4)
        val poster = first.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
        val overview = first.optString("overview").takeIf { it.isNotBlank() }
        return MovieInfo(
            title = first.optString("title").ifBlank { cleaned.title },
            year = release,
            rating = if (first.has("vote_average") && first.optDouble("vote_count", 0.0) > 0) first.optDouble("vote_average") else null,
            overview = overview,
            posterUrl = poster?.let { POSTER_BASE + it }
        )
    }

    private fun httpGet(urlString: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) throw IllegalStateException("TMDb HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun toJson(i: MovieInfo) = JSONObject().apply {
        put("title", i.title)
        i.year?.let { put("year", it) }
        i.rating?.let { put("rating", it) }
        i.overview?.let { put("overview", it) }
        i.posterUrl?.let { put("poster", it) }
    }

    private fun fromJson(o: JSONObject) = MovieInfo(
        title = o.optString("title"),
        year = o.optString("year").takeIf { it.isNotBlank() },
        rating = if (o.has("rating")) o.optDouble("rating") else null,
        overview = o.optString("overview").takeIf { it.isNotBlank() },
        posterUrl = o.optString("poster").takeIf { it.isNotBlank() }
    )

    // ---- Limpieza del nombre -------------------------------------------

    data class Cleaned(val title: String, val year: String?) {
        val key: String get() = title.lowercase(Locale.ROOT) + "|" + (year ?: "")
    }

    private val bracketed = Regex("""\[[^\]]*\]|\{[^}]*\}""")
    private val yearInParens = Regex("""\(((?:19|20)\d{2})\)""")
    private val yearAtEnd = Regex("""[\s.\-_]((?:19|20)\d{2})\s*$""")
    private val parens = Regex("""\([^)]*\)""")
    private val junkWords = Regex(
        """(?i)\s+(2160p|1080p|1080i|720p|480p|4k|uhd|hdr|hd|fullhd|bluray|brrip|bdrip|webrip|web-dl|webdl|dvdrip|hdrip|hdtv|x264|x265|h264|h265|hevc|aac|ac3|dts|latino|castellano|espa[nñ]ol|spanish|english|vose|vos|dual|multi|subtitulada|subtitulado|sub|extended|remastered)\b.*$"""
    )
    private val ext = Regex("""(?i)\.(mp4|mkv|avi|mov|m4v|webm|wmv|flv|mpg|mpeg)$""")

    fun cleanTitle(raw: String): Cleaned {
        var s = raw.trim().replace(ext, "")
        var year: String? = yearInParens.find(s)?.groupValues?.get(1)
        s = s.replace(bracketed, " ")
        s = s.replace(yearInParens, " ")
        s = s.replace(parens, " ")
        s = s.replace('.', ' ').replace('_', ' ')
        if (year == null) {
            year = yearAtEnd.find(s)?.groupValues?.get(1)
            if (year != null) s = s.replace(yearAtEnd, " ")
        }
        s = s.replace(junkWords, " ")
        if (year == null) {
            year = yearAtEnd.find(s)?.groupValues?.get(1)
            if (year != null) s = s.replace(yearAtEnd, " ")
        }
        s = s.replace(Regex("""\s+"""), " ").trim(' ', '-', '–', '|', ':')
        return Cleaned(s, year)
    }
}
