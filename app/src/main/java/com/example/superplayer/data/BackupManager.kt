package com.example.superplayer.data

import android.content.Context
import com.example.superplayer.model.Stream
import com.example.superplayer.reminder.ReminderManager
import com.example.superplayer.model.streamFromJson
import com.example.superplayer.model.toJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Copia de seguridad en un archivo JSON (ver README, "Copia de seguridad"):
 * favoritos (con los datos completos de cada canal y su orden), las URLs de los 5
 * huecos de lista, el filtro y el orden de categorías de cada lista, el modo
 * claro/oscuro, los canales ocultos y renombrados, los recordatorios de la
 * guía, "Continuar viendo" y los ajustes del reproductor (audio en segundo
 * plano y formato de pantalla). No incluye las listas descargadas ni la guía: se vuelven a
 * bajar solas de sus URLs.
 *
 * Al importar, los favoritos se SUMAN a los que ya haya (nunca se borra
 * ninguno); las URLs, los filtros y el modo claro/oscuro se sustituyen por
 * los de la copia.
 */
object BackupManager {

    private const val FORMAT_VERSION = 1
    private const val APP_ID = "socram-tv-backup"

    data class ImportResult(
        val newFavorites: Int,
        val listUrls: Int,
        val filters: Int,
        val darkModeChanged: Boolean,
        val hiddenChannels: Int = 0,
        val renamedChannels: Int = 0,
        val reminders: Int = 0,
        val continueItems: Int = 0
    )

    fun export(context: Context): String {
        val root = JSONObject()
        root.put("app", APP_ID)
        root.put("version", FORMAT_VERSION)

        val favs = JSONArray()
        for (stream in FavoritesStore(context).getAllStreams()) {
            favs.put(JSONObject(stream.toJson()))
        }
        root.put("favorites", favs)

        val urls = JSONObject()
        for (slot in 1..5) {
            AppPrefs.getListUrl(context, slot)?.takeIf { it.isNotBlank() }?.let { urls.put(slot.toString(), it) }
        }
        root.put("listUrls", urls)

        val hidden = JSONObject()
        for ((key, names) in AppPrefs.getAllHiddenCategories(context)) {
            if (names.isNotEmpty()) hidden.put(key, JSONArray(names.toList()))
        }
        root.put("hiddenCategories", hidden)

        val orders = JSONObject()
        for ((key, names) in AppPrefs.getAllCategoryOrders(context)) {
            orders.put(key, JSONArray(names))
        }
        root.put("categoryOrder", orders)

        root.put("darkMode", AppPrefs.isDarkMode(context))
        root.put("backgroundAudio", AppPrefs.isBackgroundAudio(context))
        root.put("videoFormat", AppPrefs.getVideoFormat(context))

        // Canales ocultos y con nombre propio (ver ChannelOverrides).
        val hiddenCh = JSONObject()
        for ((id, name) in ChannelOverrides.getHidden(context)) hiddenCh.put(id, name)
        root.put("hiddenChannels", hiddenCh)
        val namesCh = JSONObject()
        for ((id, name) in ChannelOverrides.getNames(context)) namesCh.put(id, name)
        root.put("channelNames", namesCh)

        // Recordatorios de la guía (los ya pasados no se guardan).
        val now = System.currentTimeMillis()
        val reminders = JSONArray()
        for (r in ReminderManager.getAll(context)) {
            if (r.startMillis <= now) continue
            reminders.put(
                JSONObject().put("stream", r.streamJson).put("id", r.streamId)
                    .put("channel", r.channelName).put("title", r.title).put("start", r.startMillis)
            )
        }
        root.put("reminders", reminders)

        // Continuar viendo.
        val cont = JSONArray()
        for (e in ContinueWatching.getAll(context)) {
            cont.put(
                JSONObject().put("stream", JSONObject(e.stream.toJson()))
                    .put("pos", e.positionMs).put("dur", e.durationMs).put("at", e.updatedAt)
            )
        }
        root.put("continueWatching", cont)
        return root.toString(2)
    }

    /** Lanza IllegalArgumentException si el texto no es una copia de esta app. */
    fun restore(context: Context, text: String): ImportResult {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("No es un archivo de copia válido")
        }
        if (root.optString("app") != APP_ID) throw IllegalArgumentException("Este archivo no es una copia de Socram TV+")

        val streams = ArrayList<Stream>()
        val favs = root.optJSONArray("favorites")
        if (favs != null) {
            for (i in 0 until favs.length()) {
                streamFromJson(favs.getJSONObject(i).toString())?.let { streams.add(it) }
            }
        }
        val newFavorites = FavoritesStore(context).addAll(streams)

        var urlCount = 0
        val urls = root.optJSONObject("listUrls")
        if (urls != null) {
            for (slot in 1..5) {
                val url = urls.optString(slot.toString())
                if (url.isNotBlank()) {
                    AppPrefs.saveListUrl(context, slot, url)
                    urlCount++
                }
            }
        }

        var filterCount = 0
        val hidden = root.optJSONObject("hiddenCategories")
        if (hidden != null) {
            val keys = hidden.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = hidden.optJSONArray(key) ?: continue
                val names = HashSet<String>()
                for (i in 0 until arr.length()) names.add(arr.getString(i))
                AppPrefs.setHiddenCategories(context, key, names)
                filterCount++
            }
        }

        val orders = root.optJSONObject("categoryOrder")
        if (orders != null) {
            val keys = orders.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = orders.optJSONArray(key) ?: continue
                AppPrefs.setCategoryOrder(context, key, List(arr.length()) { arr.getString(it) })
            }
        }

        var darkChanged = false
        if (root.has("darkMode")) {
            val dark = root.optBoolean("darkMode", true)
            darkChanged = dark != AppPrefs.isDarkMode(context)
            if (darkChanged) AppPrefs.setDarkMode(context, dark)
        }

        if (root.has("backgroundAudio")) AppPrefs.setBackgroundAudio(context, root.optBoolean("backgroundAudio", true))
        if (root.has("videoFormat")) AppPrefs.setVideoFormat(context, root.optInt("videoFormat", 0))

        val hiddenMap = LinkedHashMap<String, String>()
        root.optJSONObject("hiddenChannels")?.let { o ->
            val keys = o.keys()
            while (keys.hasNext()) { val k = keys.next(); hiddenMap[k] = o.optString(k) }
        }
        val namesMap = LinkedHashMap<String, String>()
        root.optJSONObject("channelNames")?.let { o ->
            val keys = o.keys()
            while (keys.hasNext()) { val k = keys.next(); namesMap[k] = o.optString(k) }
        }
        val (newHidden, newNames) = ChannelOverrides.merge(context, hiddenMap, namesMap)

        var newReminders = 0
        root.optJSONArray("reminders")?.let { arr ->
            val list = ArrayList<ReminderManager.Reminder>()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    list.add(
                        ReminderManager.Reminder(
                            o.getString("stream"), o.getString("id"), o.getString("channel"),
                            o.getString("title"), o.getLong("start")
                        )
                    )
                } catch (e: Exception) {
                    // Entrada dañada: se salta.
                }
            }
            newReminders = ReminderManager.importAll(context, list)
        }

        var newContinue = 0
        root.optJSONArray("continueWatching")?.let { arr ->
            val list = ArrayList<ContinueWatching.Entry>()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    val stream = streamFromJson(o.getJSONObject("stream").toString()) ?: continue
                    list.add(ContinueWatching.Entry(stream, o.getLong("pos"), o.getLong("dur"), o.optLong("at", 0L)))
                } catch (e: Exception) {
                    // Entrada dañada: se salta.
                }
            }
            newContinue = ContinueWatching.importAll(context, list)
        }

        return ImportResult(newFavorites, urlCount, filterCount, darkChanged, newHidden, newNames, newReminders, newContinue)
    }
}
