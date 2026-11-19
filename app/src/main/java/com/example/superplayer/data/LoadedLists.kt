package com.example.superplayer.data

import android.content.Context
import com.example.superplayer.model.PlaylistData
import org.json.JSONArray
import org.json.JSONObject

/**
 * Listas externas cargadas (de archivo o de enlace) que no son las principales: se recuerdan todas
 * para poder volver a abrirlas cuando se quiera. De enlace se guarda también una copia para abrirlas sin red.
 */
object LoadedLists {
    data class Entry(val kind: String, val ref: String, val name: String, val count: Int, val time: Long)

    private const val PREFS = "loaded_lists"
    private const val KEY = "items"
    private const val MAX = 40

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<Entry> {
        val arr = try { JSONArray(prefs(c).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Entry(o.optString("kind"), o.optString("ref"), o.optString("name"), o.optInt("count"), o.optLong("time")))
        }
        return out
    }

    private fun save(c: Context, l: List<Entry>) {
        val arr = JSONArray()
        for (e in l) arr.put(JSONObject().put("kind", e.kind).put("ref", e.ref).put("name", e.name).put("count", e.count).put("time", e.time))
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    /** Añade (o sube al principio si ya estaba) una lista cargada. */
    fun add(c: Context, kind: String, ref: String, name: String, count: Int) {
        val l = all(c).filterNot { it.ref == ref }.toMutableList()
        l.add(0, Entry(kind, ref, name.ifBlank { ref }.take(80), count, System.currentTimeMillis()))
        save(c, l.take(MAX))
    }

    fun remove(c: Context, e: Entry) {
        save(c, all(c).filterNot { it.ref == e.ref })
        try { cacheFile(c, e.ref).delete() } catch (x: Exception) { }
    }

    fun nameForUrl(url: String): String {
        val u = try { java.net.URI(url) } catch (e: Exception) { return url }
        val last = (u.path ?: "").trimEnd('/').substringAfterLast('/')
        return (u.host ?: url).removePrefix("www.") + (if (last.isNotEmpty()) " · $last" else "")
    }

    private fun cacheFile(c: Context, ref: String): java.io.File {
        val dir = java.io.File(c.applicationContext.filesDir, "loaded_lists").apply { mkdirs() }
        return java.io.File(dir, Integer.toHexString(ref.hashCode()) + ".txt")
    }

    /** Descarga una lista de enlace (guardando copia); si no hay red, usa la última copia. */
    fun fetchUrl(c: Context, url: String): PlaylistData {
        return try {
            val raw = PlaylistRepository.downloadRaw(url)
            val data = PlaylistRepository.parseFrom(url, raw)
            try { cacheFile(c, url).writeText(raw) } catch (e: Exception) { }
            data
        } catch (e: Exception) {
            val f = cacheFile(c, url)
            if (!f.exists()) throw e
            PlaylistRepository.parseFrom(url, f.readText())
        }
    }
}
