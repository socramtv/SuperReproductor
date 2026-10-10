package com.example.superplayer.sports

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Marcadores y eventos de fútbol en directo, de la API pública (sin clave) de
 * ESPN: una petición por competición. Es una API NO oficial: puede cambiar
 * o dejar de funcionar sin aviso; por eso todo el parseo es tolerante (un
 * campo que falte se ignora) y los errores nunca rompen nada, solo dejan de
 * llegar avisos.
 */
object MatchFeed {

    /** (código en la API, nombre que se enseña). */
    val LEAGUES = listOf(
        "esp.1" to "LaLiga",
        "esp.2" to "LaLiga Hypermotion",
        "esp.copa_del_rey" to "Copa del Rey",
        "uefa.champions" to "Champions League",
        "uefa.europa" to "Europa League",
        "uefa.europa.conf" to "Conference League",
        "eng.1" to "Premier League",
        "ita.1" to "Serie A",
        "ger.1" to "Bundesliga",
        "fra.1" to "Ligue 1"
    )

    enum class Kind { GOAL, RED_CARD, OTHER }

    data class Detail(
        val key: String,
        val kind: Kind,
        val minute: String,
        val team: String,
        val player: String,
        val ownGoal: Boolean,
        val penalty: Boolean
    )

    data class Match(
        val id: String,
        val league: String,
        val home: String,
        val away: String,
        val homeScore: Int,
        val awayScore: Int,
        /** "pre" (sin empezar), "in" (en juego) o "post" (terminado). */
        val state: String,
        val clock: String,
        val startMillis: Long,
        val details: List<Detail>,
        val homeLogo: String? = null,
        val awayLogo: String? = null
    )

    data class Result(val matches: List<Match>, val failedLeagues: Int)

    /** Pide todas las competiciones a la vez (hilos). Bloquea hasta terminar: llamar desde un hilo de fondo. */
    fun fetchAll(): Result {
        val pool = Executors.newFixedThreadPool(5)
        val futures = LEAGUES.map { (slug, name) ->
            pool.submit(java.util.concurrent.Callable<List<Match>?> {
                try {
                    fetchLeague(slug, name)
                } catch (e: Exception) {
                    null
                }
            })
        }
        val all = ArrayList<Match>()
        var failed = 0
        for (f in futures) {
            val list = try { f.get() } catch (e: Exception) { null }
            if (list == null) failed++ else all.addAll(list)
        }
        pool.shutdown()
        return Result(all, failed)
    }

    private fun fetchLeague(slug: String, leagueName: String): List<Match> {
        val url = URL("https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/scoreboard")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().readText()
            return parse(text, leagueName)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(text: String, leagueName: String): List<Match> {
        val root = JSONObject(text)
        val events = root.optJSONArray("events") ?: return emptyList()
        val result = ArrayList<Match>()
        for (i in 0 until events.length()) {
            try {
                parseEvent(events.getJSONObject(i), leagueName)?.let { result.add(it) }
            } catch (e: Exception) {
                // Un partido con datos raros se salta; el resto sigue.
            }
        }
        return result
    }

    private fun parseEvent(event: JSONObject, leagueName: String): Match? {
        val comp = event.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val competitors = comp.optJSONArray("competitors") ?: return null
        var home: JSONObject? = null
        var away: JSONObject? = null
        for (i in 0 until competitors.length()) {
            val c = competitors.getJSONObject(i)
            if (c.optString("homeAway") == "home") home = c else if (c.optString("homeAway") == "away") away = c
        }
        if (home == null || away == null) return null
        val homeName = home.optJSONObject("team")?.optString("displayName").orEmpty()
        val awayName = away.optJSONObject("team")?.optString("displayName").orEmpty()
        if (homeName.isBlank() || awayName.isBlank()) return null
        val status = event.optJSONObject("status") ?: comp.optJSONObject("status")
        val state = status?.optJSONObject("type")?.optString("state").orEmpty().ifBlank { "pre" }
        val clock = status?.optString("displayClock").orEmpty()

        val teamNameById = HashMap<String, String>()
        home.optJSONObject("team")?.let { teamNameById[it.optString("id")] = homeName }
        away.optJSONObject("team")?.let { teamNameById[it.optString("id")] = awayName }

        val details = ArrayList<Detail>()
        val arr: JSONArray? = comp.optJSONArray("details")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val kind = when {
                    d.optBoolean("redCard", false) -> Kind.RED_CARD
                    d.optBoolean("scoringPlay", false) -> Kind.GOAL
                    else -> Kind.OTHER
                }
                if (kind == Kind.OTHER) continue
                val minute = d.optJSONObject("clock")?.optString("displayValue").orEmpty()
                val teamId = d.optJSONObject("team")?.optString("id").orEmpty()
                val player = d.optJSONArray("athletesInvolved")?.optJSONObject(0)?.optString("displayName").orEmpty()
                details.add(
                    Detail(
                        key = "$kind|$minute|$teamId|$player",
                        kind = kind,
                        minute = minute,
                        team = teamNameById[teamId].orEmpty(),
                        player = player,
                        ownGoal = d.optBoolean("ownGoal", false),
                        penalty = d.optBoolean("penaltyKick", false)
                    )
                )
            }
        }
        return Match(
            id = event.optString("id").ifBlank { "$homeName-$awayName" },
            league = leagueName,
            home = homeName,
            away = awayName,
            homeScore = home.optString("score").toIntOrNull() ?: 0,
            awayScore = away.optString("score").toIntOrNull() ?: 0,
            state = state,
            clock = clock,
            startMillis = parseDate(event.optString("date")),
            details = details,
            homeLogo = home.optJSONObject("team")?.optString("logo")?.takeIf { it.startsWith("http") },
            awayLogo = away.optJSONObject("team")?.optString("logo")?.takeIf { it.startsWith("http") }
        )
    }

    private val STOP_WORDS = setOf("de", "del", "la", "el", "fc", "cf", "cd", "ud", "sd", "rcd", "club", "afc", "sc", "ac")

    private fun words(text: String): List<String> =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }

    /** true si todas las palabras significativas del equipo ("Atlético de Madrid" -> atletico, madrid) aparecen en [title] (el de un programa de la guía). */
    fun teamInTitle(team: String, title: String): Boolean {
        val significant = words(team).filter { it !in STOP_WORDS }
        if (significant.isEmpty()) return false
        val titleWords = words(title).toSet()
        val titleJoined = titleWords.joinToString(" ")
        return significant.all { it in titleWords || titleJoined.contains(it) }
    }

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                return fmt.parse(raw)?.time ?: 0L
            } catch (e: Exception) {
                // prueba el siguiente formato
            }
        }
        return 0L
    }
}
