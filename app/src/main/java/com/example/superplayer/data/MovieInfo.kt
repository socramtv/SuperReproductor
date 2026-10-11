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
        val posterUrl: String?,
        val originalTitle: String? = null,
        val tagline: String? = null,
        val runtimeMin: Int = 0,
        val rating: Double = 0.0,
        val votes: Int = 0,
        val director: String? = null,
        val cast: List<String> = emptyList(),
        val trailerKey: String? = null,
        val backdropUrl: String? = null,
        val certification: String? = null,
        val isTv: Boolean = false,
        /** "tmdb", "itunes" o "tvmaze". */
        val source: String = "itunes",
        /** true si se buscó con la clave de TMDb puesta (aunque no se encontrara ahí). */
        val tmdbChecked: Boolean = false
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
        val raw = prefs(context).getString(key, null) ?: return false
        return try {
            val o = JSONObject(raw)
            o.has("none") && System.currentTimeMillis() - o.optLong("none") < MISSING_RETRY_MS &&
                (o.optBoolean("tt", false) || AppPrefs.getTmdbKey(context).isBlank())
        } catch (e: Exception) {
            false
        }
    }

    private fun parseStored(raw: String): Info? = try {
        val o = JSONObject(raw)
        if (o.has("none")) null
        else {
            val castArr = o.optJSONArray("ca")
            Info(
                o.optString("t"),
                o.optString("y").ifEmpty { null },
                o.optString("g").ifEmpty { null },
                o.optString("o").ifEmpty { null },
                o.optString("p").ifEmpty { null },
                o.optString("ot").ifEmpty { null },
                o.optString("tg").ifEmpty { null },
                o.optInt("rt", 0),
                o.optDouble("ra", 0.0),
                o.optInt("vc", 0),
                o.optString("di").ifEmpty { null },
                if (castArr == null) emptyList() else (0 until castArr.length()).map { castArr.optString(it) },
                o.optString("tr").ifEmpty { null },
                o.optString("bd").ifEmpty { null },
                o.optString("ce").ifEmpty { null },
                o.optBoolean("tv", false),
                o.optString("src", "itunes"),
                o.optBoolean("tt", false)
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun store(context: Context, key: String, info: Info?, tmdbChecked: Boolean) {
        synchronized(memory) { memory[key] = info }
        val json = if (info == null) {
            JSONObject().put("none", System.currentTimeMillis()).put("tt", tmdbChecked)
        } else {
            JSONObject().put("t", info.title).put("y", info.year ?: "").put("g", info.genre ?: "")
                .put("o", info.overview ?: "").put("p", info.posterUrl ?: "")
                .put("ot", info.originalTitle ?: "").put("tg", info.tagline ?: "")
                .put("rt", info.runtimeMin).put("ra", info.rating).put("vc", info.votes)
                .put("di", info.director ?: "").put("ca", JSONArray(info.cast))
                .put("tr", info.trailerKey ?: "").put("bd", info.backdropUrl ?: "")
                .put("ce", info.certification ?: "").put("tv", info.isTv)
                .put("src", info.source).put("tt", info.tmdbChecked)
        }
        prefs(context).edit().putString(key, json.toString()).apply()
    }

    /**
     * ¿Hay que (volver a) pedir los datos de este título? Sí si no se conoce y
     * no se buscó hace poco, o si ya hay clave de TMDb y lo guardado salió de
     * otra fuente y nunca se probó con ella.
     */
    fun needsFetch(context: Context, rawTitle: String): Boolean {
        val info = peek(context, rawTitle)
        if (info == null) return !isKnownMissing(context, rawTitle)
        return AppPrefs.getTmdbKey(context).isNotBlank() && !info.tmdbChecked
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
        val tmdbKey = AppPrefs.getTmdbKey(appContext)
        synchronized(waiting) {
            val list = waiting[key]
            if (list != null) {
                list.add(onDone)
                return
            }
            waiting[key] = mutableListOf(onDone)
        }
        pool.execute {
            val info = try { search(title, year, tmdbKey) } catch (e: Exception) { null }
            store(appContext, key, info, tmdbKey.isNotBlank())
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

    private fun search(title: String, year: String?, tmdbKey: String): Info? {
        val checked = tmdbKey.isNotBlank()
        var info: Info? = null
        if (checked) info = try { searchTmdb(tmdbKey, title, year) } catch (e: Exception) { null }
        if (info == null) info = try { searchMovie(title, year) } catch (e: Exception) { null }
        if (info == null) info = try { searchShow(title) } catch (e: Exception) { null }
        return info?.copy(tmdbChecked = checked)
    }

    // ---- TMDb (con la clave del usuario) ----

    private const val TMDB = "https://api.themoviedb.org/3"
    private const val TMDB_IMG = "https://image.tmdb.org/t/p/"

    /** ¿Es válida esta clave? (200 = sí, 401 = no, otro = no se pudo comprobar). Bloquea: hilo de fondo. */
    fun checkTmdbKey(key: String): Int {
        return try {
            val conn = URL("$TMDB/configuration?api_key=${URLEncoder.encode(key.trim(), "UTF-8")}").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun searchTmdb(key: String, title: String, year: String?): Info? {
        val q = URLEncoder.encode(title, "UTF-8")
        val wanted = norm(title)
        for (kind in listOf("movie", "tv")) {
            val yearParam = when {
                year == null -> ""
                kind == "movie" -> "&year=$year"
                else -> "&first_air_date_year=$year"
            }
            val body = httpGet("$TMDB/search/$kind?api_key=$key&language=es-ES&query=$q$yearParam") ?: continue
            val results = JSONObject(body).optJSONArray("results") ?: continue
            var bestId = 0
            var bestScore = -1.0
            for (i in 0 until minOf(results.length(), 10)) {
                val r = results.optJSONObject(i) ?: continue
                val names = if (kind == "movie") listOf(r.optString("title"), r.optString("original_title"))
                else listOf(r.optString("name"), r.optString("original_name"))
                var match = 0
                for (n in names) {
                    val nn = norm(n)
                    if (nn.isEmpty()) continue
                    val m = if (nn == wanted) 10 else if (nn.contains(wanted) || wanted.contains(nn)) 4 else 0
                    if (m > match) match = m
                }
                if (match == 0) continue
                val score = match + minOf(r.optDouble("popularity", 0.0), 100.0) / 100.0
                if (score > bestScore) {
                    bestScore = score
                    bestId = r.optInt("id")
                }
            }
            if (bestId == 0) continue
            val info = tmdbDetails(key, kind, bestId)
            if (info != null) return info
        }
        return null
    }

    private fun tmdbDetails(key: String, kind: String, id: Int): Info? {
        val isMovie = kind == "movie"
        val extra = if (isMovie) "credits,videos,release_dates" else "credits,videos,content_ratings"
        val body = httpGet(
            "$TMDB/$kind/$id?api_key=$key&language=es-ES&append_to_response=$extra&include_video_language=es,en,null"
        ) ?: return null
        val o = JSONObject(body)

        var overview = o.optString("overview")
        var tagline = o.optString("tagline")
        if (overview.isBlank()) {
            // Sin sinopsis en español: se completa con la inglesa.
            httpGet("$TMDB/$kind/$id?api_key=$key&language=en-US")?.let {
                val en = JSONObject(it)
                overview = en.optString("overview")
                if (tagline.isBlank()) tagline = en.optString("tagline")
            }
        }
        val title = o.optString(if (isMovie) "title" else "name")
        if (title.isBlank()) return null
        val date = o.optString(if (isMovie) "release_date" else "first_air_date")

        val genresArr = o.optJSONArray("genres")
        val genres = if (genresArr == null) null
        else (0 until genresArr.length()).mapNotNull { genresArr.optJSONObject(it)?.optString("name")?.ifEmpty { null } }
            .joinToString(", ").ifEmpty { null }

        val runtime = if (isMovie) o.optInt("runtime", 0)
        else o.optJSONArray("episode_run_time")?.let { if (it.length() > 0) it.optInt(0, 0) else 0 } ?: 0

        val credits = o.optJSONObject("credits")
        var director: String? = null
        if (isMovie) {
            val crew = credits?.optJSONArray("crew")
            if (crew != null) {
                val directors = (0 until crew.length()).mapNotNull {
                    val c = crew.optJSONObject(it)
                    if (c != null && c.optString("job") == "Director") c.optString("name").ifEmpty { null } else null
                }
                director = directors.take(2).joinToString(", ").ifEmpty { null }
            }
        } else {
            val creators = o.optJSONArray("created_by")
            if (creators != null) {
                director = (0 until creators.length()).mapNotNull { creators.optJSONObject(it)?.optString("name")?.ifEmpty { null } }
                    .take(2).joinToString(", ").ifEmpty { null }
            }
        }
        val castArr = credits?.optJSONArray("cast")
        val cast = if (castArr == null) emptyList()
        else (0 until minOf(castArr.length(), 8)).mapNotNull { castArr.optJSONObject(it)?.optString("name")?.ifEmpty { null } }

        // Tráiler de YouTube: primero en español, luego en inglés o sin idioma; "Trailer" antes que "Teaser".
        var trailer: String? = null
        val videos = o.optJSONObject("videos")?.optJSONArray("results")
        if (videos != null) {
            var bestRank = Int.MAX_VALUE
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                if (v.optString("site") != "YouTube") continue
                val vKey = v.optString("key").ifEmpty { null } ?: continue
                val typeRank = when (v.optString("type")) { "Trailer" -> 0; "Teaser" -> 1; else -> continue }
                val langRank = when (v.optString("iso_639_1")) { "es" -> 0; "en" -> 1; else -> 2 }
                val rank = langRank * 2 + typeRank
                if (rank < bestRank) {
                    bestRank = rank
                    trailer = vKey
                }
            }
        }

        // Clasificación por edad en España.
        var certification: String? = null
        if (isMovie) {
            val countries = o.optJSONObject("release_dates")?.optJSONArray("results")
            if (countries != null) {
                for (i in 0 until countries.length()) {
                    val c = countries.optJSONObject(i) ?: continue
                    if (c.optString("iso_3166_1") != "ES") continue
                    val dates = c.optJSONArray("release_dates") ?: continue
                    for (j in 0 until dates.length()) {
                        val cert = dates.optJSONObject(j)?.optString("certification").orEmpty()
                        if (cert.isNotBlank()) {
                            certification = cert
                            break
                        }
                    }
                }
            }
        } else {
            val countries = o.optJSONObject("content_ratings")?.optJSONArray("results")
            if (countries != null) {
                for (i in 0 until countries.length()) {
                    val c = countries.optJSONObject(i) ?: continue
                    if (c.optString("iso_3166_1") == "ES" && c.optString("rating").isNotBlank()) {
                        certification = c.optString("rating")
                    }
                }
            }
        }

        val poster = o.optString("poster_path").ifEmpty { null }?.let { TMDB_IMG + "w500" + it }
        val backdrop = o.optString("backdrop_path").ifEmpty { null }?.let { TMDB_IMG + "w780" + it }
        return Info(
            title = title,
            year = date.take(4).ifEmpty { null },
            genre = genres,
            overview = overview.ifBlank { null },
            posterUrl = poster,
            originalTitle = o.optString(if (isMovie) "original_title" else "original_name").ifEmpty { null }
                ?.takeIf { it != title },
            tagline = tagline.ifBlank { null },
            runtimeMin = runtime,
            rating = o.optDouble("vote_average", 0.0),
            votes = o.optInt("vote_count", 0),
            director = director,
            cast = cast,
            trailerKey = trailer,
            backdropUrl = backdrop,
            certification = certification,
            isTv = !isMovie,
            source = "tmdb",
            tmdbChecked = true
        )
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
