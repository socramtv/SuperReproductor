package com.example.superplayer.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Perfiles de usuario: cada perfil tiene sus propios favoritos (y su
 * orden), canales ocultos y renombrados, "Continuar viendo" y filtros/orden
 * de categorías. Las listas (URLs), el modo claro/oscuro, los recordatorios
 * de la guía, los avisos de goles y los avisos por programa son comunes a
 * todos (los avisos saltan en segundo plano y no distinguen perfil).
 *
 * El perfil "Principal" usa los ficheros de siempre, así que quien ya tenía
 * la app no pierde nada; los demás usan los mismos ficheros con el id del
 * perfil detrás (favorites__p123...). Todo el que guarda datos por perfil
 * obtiene sus SharedPreferences con [prefs], que siempre mira el perfil
 * activo en ese momento.
 */
object Profiles {
    private const val PREFS = "profiles"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"
    const val DEFAULT_ID = "default"
    private const val DEFAULT_NAME = "Principal"

    /** Ficheros de preferencias que son de cada perfil. */
    private val PER_PROFILE_BASES = listOf("favorites", "channel_overrides", "continue_watching", "app_prefs")

    data class Profile(val id: String, val name: String)

    private fun meta(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Profile> {
        val result = ArrayList<Profile>()
        val raw = meta(context).getString(KEY_LIST, null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    result.add(Profile(o.getString("id"), o.optString("name", DEFAULT_NAME)))
                }
            } catch (e: Exception) {
                result.clear()
            }
        }
        if (result.none { it.id == DEFAULT_ID }) result.add(0, Profile(DEFAULT_ID, DEFAULT_NAME))
        return result
    }

    private fun save(context: Context, list: List<Profile>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("id", p.id).put("name", p.name))
        meta(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun activeId(context: Context): String {
        val id = meta(context).getString(KEY_ACTIVE, DEFAULT_ID) ?: DEFAULT_ID
        return if (id == DEFAULT_ID || all(context).any { it.id == id }) id else DEFAULT_ID
    }

    fun active(context: Context): Profile {
        val id = activeId(context)
        return all(context).firstOrNull { it.id == id } ?: Profile(DEFAULT_ID, DEFAULT_NAME)
    }

    fun isDefaultActive(context: Context): Boolean = activeId(context) == DEFAULT_ID

    fun setActive(context: Context, id: String) {
        meta(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun add(context: Context, name: String): Profile {
        val profile = Profile("p" + System.currentTimeMillis(), name.trim().ifEmpty { "Perfil" })
        save(context, all(context) + profile)
        return profile
    }

    fun rename(context: Context, id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        save(context, all(context).map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Borra un perfil y todos sus datos. No se puede borrar el Principal ni el que está activo. */
    fun delete(context: Context, id: String): Boolean {
        if (id == DEFAULT_ID || id == activeId(context)) return false
        save(context, all(context).filter { it.id != id })
        for (base in PER_PROFILE_BASES) {
            try {
                context.applicationContext.deleteSharedPreferences("${base}__$id")
            } catch (e: Exception) {
                // Si no se puede borrar el fichero, queda huérfano sin molestar.
            }
        }
        return true
    }

    fun prefsName(context: Context, base: String): String {
        val id = activeId(context)
        return if (id == DEFAULT_ID) base else "${base}__$id"
    }

    /** Las preferencias de [base] del perfil activo. */
    fun prefs(context: Context, base: String): SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName(context, base), Context.MODE_PRIVATE)
}
