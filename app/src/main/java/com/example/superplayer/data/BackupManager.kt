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
 * plano y formato de pantalla), y en "extras" (BackupExtras) los ajustes e historial del
 * navegador, lo visto y «seguir viendo» de Telegram, su biblioteca, el radio-mix y las teles
 * guardadas. No incluye las listas descargadas ni la guía: se vuelven a
 * bajar solas de sus URLs.
 *
 * Al importar, los favoritos se SUMAN a los que ya haya (nunca se borra
 * ninguno); las URLs, los filtros y el modo claro/oscuro se sustituyen por
 * los de la copia.
 */
object BackupManager {

    /** Bloques de la copia que se pueden elegir al guardar y al importar. [keys] son las claves del JSON (y de "extras"). */
    enum class Section(val labelRes: Int, val keys: List<String>, val extraKeys: List<String> = emptyList()) {
        FAVORITES(com.example.superplayer.R.string.bk_favorites, listOf("favorites")),
        LISTS(com.example.superplayer.R.string.bk_lists, listOf("listUrls", "hiddenCategories", "categoryOrder")),
        CHANNELS(com.example.superplayer.R.string.bk_channels, listOf("hiddenChannels", "channelNames")),
        CONTINUE(com.example.superplayer.R.string.bk_continue, listOf("continueWatching")),
        ALERTS(com.example.superplayer.R.string.bk_alerts, listOf("reminders", "programAlerts", "goalAlerts")),
        TELEGRAM(com.example.superplayer.R.string.bk_telegram, listOf("telegram"), listOf("tgWatched", "tgResume", "tgIntros", "tgLibrary", "tgManual")),
        WEB(com.example.superplayer.R.string.bk_web, listOf("webBookmarks"), listOf("webSettings", "webHistory")),
        SETTINGS(com.example.superplayer.R.string.bk_settings, listOf("darkMode", "backgroundAudio", "videoFormat", "timeShiftMinutes")),
        DEVICES(com.example.superplayer.R.string.bk_devices, emptyList(), listOf("radioDelay", "tvDevices"))
    }

    /** Quita del JSON todo lo que no sea de las secciones elegidas. */
    private fun keepOnly(root: JSONObject, sections: Set<Section>) {
        val keep = HashSet<String>(); val keepExtra = HashSet<String>()
        for (sec in sections) { keep.addAll(sec.keys); keepExtra.addAll(sec.extraKeys) }
        for (k in root.keys().asSequence().toList()) {
            if (k == "app" || k == "version") continue
            if (k == "extras") continue
            if (k !in keep) root.remove(k)
        }
        root.optJSONObject("extras")?.let { ex ->
            for (k in ex.keys().asSequence().toList()) if (k !in keepExtra) ex.remove(k)
        }
    }

    /** Qué secciones trae un archivo de copia (para enseñar solo esas al importar). */
    fun sectionsIn(text: String): Set<Section> {
        val root = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("No es un archivo de copia válido") }
        if (root.optString("app") != APP_ID) throw IllegalArgumentException("Este archivo no es una copia de Socram TV+")
        val ex = root.optJSONObject("extras")
        return Section.values().filter { sec -> sec.keys.any { root.has(it) } || sec.extraKeys.any { ex?.has(it) == true } }.toSet()
    }

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
        val continueItems: Int = 0,
        /** Elementos nuevos de Telegram (vídeos favoritos, carpetas, chats), marcadores web y equipos de avisos de goles. */
        val otherItems: Int = 0
    )

    fun export(context: Context, sections: Set<Section> = Section.values().toSet()): String {
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

        // Avisos por programa y pausa en directo.
        root.put("programAlerts", JSONArray(com.example.superplayer.reminder.ProgramAlerts.getAll(context)))
        root.put("timeShiftMinutes", AppPrefs.getTimeShiftMinutes(context))

        // Telegram (favoritos, carpetas y chats favoritos), marcadores del navegador y avisos de goles.
        root.put("telegram", com.example.superplayer.telegram.TelegramAccountPrefs.exportBackup(context))
        root.put("webBookmarks", webBookmarks(context))
        root.put(
            "goalAlerts",
            JSONObject()
                .put("enabled", com.example.superplayer.sports.GoalAlerts.isEnabled(context))
                .put("teams", JSONArray(com.example.superplayer.sports.GoalAlerts.getTeams(context)))
        )
        root.put("extras", BackupExtras.export(context))
        keepOnly(root, sections)
        return root.toString(2)
    }

    /** Lanza IllegalArgumentException si el texto no es una copia de esta app. */
    fun restore(context: Context, text: String, sections: Set<Section> = Section.values().toSet()): ImportResult {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("No es un archivo de copia válido")
        }
        if (root.optString("app") != APP_ID) throw IllegalArgumentException("Este archivo no es una copia de Socram TV+")

        keepOnly(root, sections)

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

        root.optJSONArray("programAlerts")?.let { arr ->
            val list = ArrayList<String>()
            for (i in 0 until arr.length()) list.add(arr.optString(i))
            com.example.superplayer.reminder.ProgramAlerts.importAll(context, list)
        }
        if (root.has("timeShiftMinutes")) {
            AppPrefs.setTimeShiftMinutes(context, root.optInt("timeShiftMinutes", 0))
        }

        var other = 0
        root.optJSONObject("telegram")?.let { other += com.example.superplayer.telegram.TelegramAccountPrefs.importBackup(context, it) }
        root.optJSONArray("webBookmarks")?.let { other += mergeWebBookmarks(context, it) }
        root.optJSONObject("goalAlerts")?.let { g ->
            val mine = com.example.superplayer.sports.GoalAlerts.getTeams(context)
            val extra = (0 until (g.optJSONArray("teams")?.length() ?: 0)).map { g.optJSONArray("teams")!!.optString(it) }
                .filter { it.isNotBlank() && it !in mine }
            if (extra.isNotEmpty()) {
                com.example.superplayer.sports.GoalAlerts.setTeams(context, mine + extra)
                other += extra.size
            }
            if (g.optBoolean("enabled", false) && !com.example.superplayer.sports.GoalAlerts.isEnabled(context)) {
                com.example.superplayer.sports.GoalAlerts.setEnabled(context, true)
            }
        }

        root.optJSONObject("extras")?.let { other += BackupExtras.import(context, it) }

        return ImportResult(newFavorites, urlCount, filterCount, darkChanged, newHidden, newNames, newReminders, newContinue, other)
    }

    // ---- Marcadores del navegador (ver WebActivity) ----

    private fun webPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("web_browser", Context.MODE_PRIVATE)

    private fun webBookmarks(context: Context): JSONArray =
        try { JSONArray(webPrefs(context).getString("bookmarks", "[]")) } catch (e: Exception) { JSONArray() }

    private fun mergeWebBookmarks(context: Context, incoming: JSONArray): Int {
        val mine = webBookmarks(context)
        val seen = (0 until mine.length()).mapNotNull { mine.optJSONObject(it)?.optString("u") }.toMutableSet()
        var n = 0
        for (i in 0 until incoming.length()) {
            val o = incoming.optJSONObject(i) ?: continue
            val u = o.optString("u")
            if (u.isNotEmpty() && seen.add(u)) { mine.put(o); n++ }
        }
        if (n > 0) webPrefs(context).edit().putString("bookmarks", mine.toString()).apply()
        return n
    }
}
