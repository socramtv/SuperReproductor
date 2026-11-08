package com.example.superplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resto de datos que entran en la copia de seguridad (ver BackupManager): ajustes e historial del
 * navegador, lo visto y «seguir viendo» de Telegram, la biblioteca de Telegram (fuentes, ocultos,
 * lista de deseos, marcadores manuales), la intro de cada serie, el retardo del radio-mix y las
 * teles guardadas. Al importar todo se SUMA (nunca se borra nada); los ajustes se sustituyen.
 */
internal object BackupExtras {

    private fun sp(c: Context, name: String) = c.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun arr(p: android.content.SharedPreferences, key: String): JSONArray =
        try { JSONArray(p.getString(key, "[]")) } catch (e: Exception) { JSONArray() }

    fun export(c: Context): JSONObject {
        val o = JSONObject()

        // Navegador: ajustes e historial (los marcadores van aparte).
        val web = sp(c, "web_browser")
        val ws = JSONObject()
        for (k in listOf("adblock", "cursor", "ua", "ua_custom", "home", "video_alert")) {
            if (web.contains(k)) ws.put(k, web.all[k])
        }
        o.put("webSettings", ws)
        o.put("webHistory", arr(web, "history"))

        // Telegram: vistos, seguir viendo, intros.
        o.put("tgWatched", JSONArray((sp(c, "tg_watched").getStringSet("seen", emptySet()) ?: emptySet()).toList()))
        val res = sp(c, "tg_resume")
        o.put("tgResume", arr(res, "entries"))
        val intros = JSONObject()
        for ((k, v) in res.all) if (k.startsWith("introsec:") && v is Int) intros.put(k, v)
        o.put("tgIntros", intros)

        // Biblioteca de Telegram.
        val lib = sp(c, "tg_library")
        o.put(
            "tgLibrary",
            JSONObject()
                .put("sources", JSONArray((lib.getStringSet("sources", emptySet()) ?: emptySet()).toList()))
                .put("hidden", JSONArray((lib.getStringSet("hidden", emptySet()) ?: emptySet()).toList()))
                .put("wishlist", arr(lib, "wishlist"))
        )
        try { o.put("tgManual", JSONObject(sp(c, "tg_manual").getString("map", "{}") ?: "{}")) } catch (e: Exception) { }

        // Radio-mix y teles guardadas.
        o.put("radioDelay", sp(c, "radio_mix").getInt("delay", 0))
        o.put("tvDevices", arr(sp(c, "remote_devices"), "list"))
        return o
    }

    /** Devuelve cuántos elementos nuevos entraron. */
    fun import(c: Context, o: JSONObject): Int {
        var added = 0

        // Navegador
        o.optJSONObject("webSettings")?.let { ws ->
            val e = sp(c, "web_browser").edit()
            for (k in ws.keys()) {
                when (val v = ws.get(k)) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is String -> e.putString(k, v)
                }
            }
            e.apply()
        }
        o.optJSONArray("webHistory")?.let { inc ->
            val p = sp(c, "web_browser")
            val mine = arr(p, "history")
            val seen = (0 until mine.length()).mapNotNull { mine.optJSONObject(it)?.optString("u") }.toMutableSet()
            val all = ArrayList<JSONObject>()
            for (i in 0 until mine.length()) mine.optJSONObject(i)?.let { all.add(it) }
            var n = 0
            for (i in 0 until inc.length()) {
                val x = inc.optJSONObject(i) ?: continue
                if (x.optString("u").isNotEmpty() && seen.add(x.optString("u"))) { all.add(x); n++ }
            }
            if (n > 0) {
                val out = JSONArray()
                for (x in all.sortedByDescending { it.optLong("at") }.take(300)) out.put(x)
                p.edit().putString("history", out.toString()).apply()
                added += n
            }
        }

        // Vistos de Telegram
        o.optJSONArray("tgWatched")?.let { inc ->
            val p = sp(c, "tg_watched")
            val cur = HashSet(p.getStringSet("seen", emptySet()) ?: emptySet())
            val before = cur.size
            for (i in 0 until inc.length()) inc.optString(i).takeIf { it.isNotEmpty() }?.let { cur.add(it) }
            if (cur.size > before) {
                p.edit().putStringSet("seen", cur).apply()
                added += cur.size - before
            }
        }

        // Seguir viendo de Telegram (gana la entrada más reciente de cada clave)
        o.optJSONArray("tgResume")?.let { inc ->
            val p = sp(c, "tg_resume")
            val byKey = LinkedHashMap<String, JSONObject>()
            val mine = arr(p, "entries")
            for (i in 0 until mine.length()) mine.optJSONObject(i)?.let { byKey[it.optString("key")] = it }
            var n = 0
            for (i in 0 until inc.length()) {
                val x = inc.optJSONObject(i) ?: continue
                val k = x.optString("key")
                val ex = byKey[k]
                if (ex == null) n++
                if (ex == null || x.optLong("at") > ex.optLong("at")) byKey[k] = x
            }
            val out = JSONArray()
            for (x in byKey.values.sortedByDescending { it.optLong("at") }.take(30)) out.put(x)
            p.edit().putString("entries", out.toString()).apply()
            added += n
        }
        o.optJSONObject("tgIntros")?.let { intros ->
            val e = sp(c, "tg_resume").edit()
            for (k in intros.keys()) e.putInt(k, intros.optInt(k))
            e.apply()
        }

        // Biblioteca de Telegram
        o.optJSONObject("tgLibrary")?.let { l ->
            val p = sp(c, "tg_library")
            val e = p.edit()
            fun union(key: String) {
                val cur = HashSet(p.getStringSet(key, emptySet()) ?: emptySet())
                val before = cur.size
                val inc = l.optJSONArray(key)
                if (inc != null) for (i in 0 until inc.length()) inc.optString(i).takeIf { it.isNotEmpty() }?.let { cur.add(it) }
                if (cur.size > before) { e.putStringSet(key, cur); added += cur.size - before }
            }
            union("sources"); union("hidden")
            val wish = arr(p, "wishlist")
            val have = (0 until wish.length()).map { wish.optString(it) }.toMutableList()
            l.optJSONArray("wishlist")?.let { inc ->
                for (i in 0 until inc.length()) {
                    val s = inc.optString(i)
                    if (s.isNotEmpty() && s !in have) { have.add(s); added++ }
                }
            }
            e.putString("wishlist", JSONArray(have).toString())
            e.apply()
        }
        o.optJSONObject("tgManual")?.let { inc ->
            val p = sp(c, "tg_manual")
            val mine = try { JSONObject(p.getString("map", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
            var n = 0
            for (k in inc.keys()) if (!mine.has(k)) { mine.put(k, inc.optString(k)); n++ }
            if (n > 0) { p.edit().putString("map", mine.toString()).apply(); added += n }
        }

        // Radio-mix
        if (o.has("radioDelay")) sp(c, "radio_mix").edit().putInt("delay", o.optInt("radioDelay").coerceIn(0, 90)).apply()

        // Teles guardadas
        o.optJSONArray("tvDevices")?.let { inc ->
            val p = sp(c, "remote_devices")
            val mine = arr(p, "list")
            val seen = (0 until mine.length()).mapNotNull { mine.optJSONObject(it)?.let { d -> d.optString("host") + ":" + d.optInt("port") } }.toMutableSet()
            var n = 0
            for (i in 0 until inc.length()) {
                val d = inc.optJSONObject(i) ?: continue
                if (seen.add(d.optString("host") + ":" + d.optInt("port"))) { mine.put(d); n++ }
            }
            if (n > 0) { p.edit().putString("list", mine.toString()).apply(); added += n }
        }
        return added
    }
}
