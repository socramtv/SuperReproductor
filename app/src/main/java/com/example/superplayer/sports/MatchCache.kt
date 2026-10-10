package com.example.superplayer.sports

/**
 * Último juego de marcadores descargado (ver MatchFeed), en memoria, para
 * pintar el resultado y los escudos junto a los programas de fútbol de la
 * guía EPG. Se vuelve a pedir como mucho cada 45 segundos.
 */
object MatchCache {
    private const val MIN_AGE_MS = 45_000L
    private const val MAX_START_GAP_MS = 3 * 3_600_000L

    @Volatile private var matches: List<MatchFeed.Match> = emptyList()
    @Volatile private var fetchedAt = 0L
    @Volatile private var fetching = false

    fun hasLive(): Boolean = matches.any { it.state == "in" }

    /** Descarga en segundo plano si hace falta y llama a [onDone] (desde un hilo de fondo) al terminar, haya cambiado algo o no. */
    fun refresh(onDone: () -> Unit) {
        val now = System.currentTimeMillis()
        if (fetching) return
        if (now - fetchedAt < MIN_AGE_MS) {
            onDone()
            return
        }
        fetching = true
        Thread {
            try {
                val result = MatchFeed.fetchAll()
                // Si todo falló (sin conexión) se conserva lo que ya había.
                if (result.matches.isNotEmpty() || result.failedLeagues < MatchFeed.LEAGUES.size) {
                    matches = result.matches
                    fetchedAt = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                // Nunca debe romper la guía.
            } finally {
                fetching = false
            }
            onDone()
        }.start()
    }

    /** El partido al que corresponde un programa de la guía (los dos equipos en el título y la hora parecida), o null. */
    fun find(title: String, startMillis: Long): MatchFeed.Match? {
        for (m in matches) {
            if (!MatchFeed.teamInTitle(m.home, title) || !MatchFeed.teamInTitle(m.away, title)) continue
            if (m.startMillis > 0 && startMillis > 0 && Math.abs(m.startMillis - startMillis) > MAX_START_GAP_MS) continue
            return m
        }
        return null
    }

    /** "⚽ 1-0 · 23'" en juego, "⚽ 2-1 · final" si terminó, null si aún no ha empezado. */
    fun shortStatus(m: MatchFeed.Match): String? = when (m.state) {
        "in" -> "⚽ ${m.homeScore}-${m.awayScore}" + (if (m.clock.isNotBlank()) " · ${m.clock}" else "")
        "post" -> "⚽ ${m.homeScore}-${m.awayScore} · final"
        else -> null
    }
}
