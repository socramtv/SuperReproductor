package com.example.superplayer.reminder

import android.content.Context
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.model.Stream
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

/**
 * Avisos por programa o serie: en vez de poner un recordatorio a un
 * programa concreto, se guarda una palabra o título ("El Hormiguero",
 * "Champions", "Real Madrid") y cada vez que la guía EPG trae un programa
 * que lo contiene, se le pone solo un recordatorio normal (ver
 * ReminderManager: aviso 5 minutos antes, al tocarlo abre el canal).
 *
 * La comparación no distingue mayúsculas ni tildes. [scan] se ejecuta cada
 * vez que se carga la guía (ver MainActivity) y al añadir un aviso nuevo;
 * como los recordatorios son alarmas guardadas, basta con abrir la app de
 * vez en cuando para que se vayan programando los de los próximos días.
 * Los avisos por programa son comunes a todos los perfiles.
 */
object ProgramAlerts {
    private const val PREFS_NAME = "program_alerts"
    private const val KEY_LIST = "keywords"
    private const val HORIZON_MS = 7L * 24 * 3_600_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(context: Context, list: List<String>) {
        prefs(context).edit().putString(KEY_LIST, JSONArray(list).toString()).apply()
    }

    /** Añade un aviso; false si estaba vacío o ya existía. */
    fun add(context: Context, keyword: String): Boolean {
        val clean = keyword.trim()
        if (clean.isEmpty()) return false
        val list = getAll(context)
        if (list.any { normalize(it) == normalize(clean) }) return false
        write(context, list + clean)
        return true
    }

    fun remove(context: Context, keyword: String) {
        write(context, getAll(context).filter { it != keyword })
    }

    /** Copia de seguridad: suma estos avisos a los que ya hay. Devuelve cuántos eran nuevos. */
    fun importAll(context: Context, incoming: List<String>): Int {
        var added = 0
        for (k in incoming) if (add(context, k)) added++
        return added
    }

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .trim()

    fun matches(keyword: String, title: String): Boolean {
        val k = normalize(keyword)
        return k.isNotEmpty() && normalize(title).contains(k)
    }

    /**
     * Busca en la guía (próximos 7 días) los programas que encajan con algún
     * aviso y les pone recordatorio si no lo tienen. Un mismo programa a la
     * misma hora en varios canales solo avisa una vez (el primero de
     * [streams]). Devuelve cuántos recordatorios nuevos ha creado. Pensado
     * para llamarse desde un hilo de fondo.
     */
    fun scan(context: Context, streams: List<Stream>): Int {
        val keywords = getAll(context)
        if (keywords.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val horizon = now + HORIZON_MS
        val taken = HashSet<String>()
        for (r in ReminderManager.getAll(context)) taken.add(normalize(r.title) + "|" + r.startMillis)
        var added = 0
        for (stream in streams) {
            val tvgId = stream.tvgId
            if (tvgId.isNullOrBlank()) continue
            for (entry in EpgRepository.allEntries(tvgId)) {
                if (entry.startMillis <= now || entry.startMillis > horizon) continue
                if (keywords.none { matches(it, entry.title) }) continue
                val key = normalize(entry.title) + "|" + entry.startMillis
                if (!taken.add(key)) continue
                if (ReminderManager.add(context, stream, entry)) added++
            }
        }
        return added
    }
}
