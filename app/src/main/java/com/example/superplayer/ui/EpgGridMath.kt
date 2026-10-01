package com.example.superplayer.ui

import com.example.superplayer.data.EpgRepository
import java.util.Calendar

/**
 * Los cálculos puros (sin nada de Android de por medio) de la vista de
 * parrilla EPG (ver EpgGridActivity): qué celdas pintar en una fila para una
 * franja horaria dada, dónde cae cada una en píxeles, y por dónde se puede
 * mover esa franja sin salirse de los datos ya descargados. Separado de la
 * Activity para poder probarlo con el compilador real sin depender de nada
 * de UI (RecyclerView, HorizontalScrollView...).
 */
object EpgGridMath {

    /** 6dp por minuto de ancho: una franja de 3 horas ocupa 1080dp. */
    const val PX_PER_MINUTE_DP = 6f

    /** Franja horaria visible a la vez (ver prevWindowButton/nextWindowButton en EpgGridActivity). */
    const val WINDOW_HOURS = 3
    const val WINDOW_MILLIS = WINDOW_HOURS * 3_600_000L

    /** Una celda ya resuelta de una fila: con programa (entry != null) o un hueco sin datos de guía. */
    data class Cell(val entry: EpgRepository.EpgEntry?, val startMillis: Long, val stopMillis: Long, val isNow: Boolean)

    /**
     * Las celdas de un canal para la franja [windowStart, windowEnd):
     * recorre los tramos de guía que haya ahí (ya recortados a la ventana
     * por EpgRepository.entriesInRange) y rellena cualquier hueco -antes del
     * primero, entre dos consecutivos, o después del último- con una celda
     * sin datos, para que la fila quede siempre cubierta de principio a fin
     * sin huecos ni solapes, tenga guía ese canal o no.
     */
    fun buildCells(tvgId: String?, windowStart: Long, windowEnd: Long, nowMillis: Long): List<Cell> {
        val entries = EpgRepository.entriesInRange(tvgId, windowStart, windowEnd).sortedBy { it.startMillis }
        val cells = mutableListOf<Cell>()
        var cursor = windowStart
        for (entry in entries) {
            if (entry.startMillis > cursor) {
                cells.add(Cell(null, cursor, entry.startMillis, false))
            }
            val isNow = nowMillis >= entry.startMillis && nowMillis < entry.stopMillis
            cells.add(Cell(entry, entry.startMillis, entry.stopMillis, isNow))
            cursor = entry.stopMillis
        }
        if (cursor < windowEnd) {
            cells.add(Cell(null, cursor, windowEnd, false))
        }
        return cells
    }

    /**
     * Posición horizontal en píxeles de un instante, relativa al inicio de
     * la franja visible. SIEMPRE hay que usar esta función para los dos
     * bordes de una celda (su inicio Y su fin) en vez de calcular un ancho
     * suelto a partir de la duración en minutos: así el borde derecho de una
     * celda y el izquierdo de la siguiente -que son el mismo instante- caen
     * exactamente en el mismo píxel, sin que un redondeo independiente en
     * cada una vaya abriendo una rendija o un solape de celda en celda a lo
     * largo de una fila.
     */
    fun xForTime(millis: Long, windowStartMillis: Long, density: Float): Int {
        val pxPerMs = (PX_PER_MINUTE_DP * density) / 60_000.0
        return Math.round((millis - windowStartMillis) * pxPerMs).toInt()
    }

    /** Redondea hacia abajo a la hora en punto, en la zona horaria del dispositivo. */
    fun roundDownToHour(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * true si [query] aparece (sin mirar mayúsculas/minúsculas) en el
     * nombre del canal o en el título de cualquier tramo de [entries]. En
     * blanco, [query] vale para todos (sin filtro). [entries] debe ser TODA
     * la guía ya descargada de ese canal (ver EpgRepository.allEntries), no
     * solo la recortada a la franja horaria visible: así un canal con una
     * coincidencia dentro de tres horas sigue apareciendo en el buscador de
     * la parrilla aunque la ventana actual todavía no la enseñe.
     */
    fun matchesSearch(channelName: String, entries: List<EpgRepository.EpgEntry>, query: String): Boolean {
        if (query.isBlank()) return true
        if (channelName.contains(query, ignoreCase = true)) return true
        return entries.any { it.title.contains(query, ignoreCase = true) }
    }

    /**
     * Recorta un inicio de franja candidato para que la ventana
     * [start, start+WINDOW_MILLIS) no se salga nunca del rango de datos
     * cargado (dataStart/dataEnd, ver EpgRepository.dataRange): no deja ir
     * más atrás de la hora en punto del primer dato, ni más adelante de lo
     * necesario para que la última franja termine justo en el último dato
     * (o, si toda la guía cargada dura menos que una franja entera, se
     * queda fija al principio en vez de dejar una ventana "negativa").
     */
    fun clampWindowStart(candidate: Long, dataStart: Long, dataEnd: Long): Long {
        val minStart = roundDownToHour(dataStart)
        val maxStart = maxOf(minStart, dataEnd - WINDOW_MILLIS)
        return candidate.coerceIn(minStart, maxStart)
    }
}
