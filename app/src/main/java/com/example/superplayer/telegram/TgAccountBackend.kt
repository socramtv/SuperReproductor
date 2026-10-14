package com.example.superplayer.telegram

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class TgAuthState { STARTING, WAIT_PHONE, WAIT_CODE, WAIT_PASSWORD, READY, CLOSED }

/** [kind]: channel, group, bot o private. [userId]: el del otro usuario en chats privados y bots (0 si no aplica). */
data class TgChat(val id: Long, val title: String, val kind: String, val lastDate: Long, val userId: Long = 0L, val unread: Int = 0, val photoId: Int = 0, val live: Boolean = false)

/** Un tema de un supergrupo con temas (foro). */
data class TgTopic(val id: Long, val name: String, val lastDate: Long = 0L, val unread: Int = 0)

/** A dónde lleva un enlace interno de Telegram. [chat].id == 0 = invitación a un chat al que aún no estás unido (el título va en [chat].title). */
data class TgLinkTarget(val chat: TgChat, val messageId: Long = 0L, val startParam: String = "")

/**
 * Telegram con la cuenta del usuario (TDLib). Esta interfaz no depende de
 * TDLib: la implementación real (TdBackend) solo se compila si TDLib está
 * incluida en el proyecto (ver app/build.gradle.kts y el workflow
 * "Build TDLib"); si no, TelegramAccountFactory devuelve null.
 * Todas las llamadas de vuelta (callbacks) llegan desde hilos de fondo.
 */
interface TgAccountBackend {
    interface Listener {
        fun onState(state: TgAuthState)
        fun onError(message: String)
        fun onNewMessage(chatId: Long)
    }

    fun start(listener: Listener)
    fun sendPhone(phone: String)
    fun sendCode(code: String)
    fun sendPassword(password: String)
    fun logOut()
    fun loadChats(onDone: (List<TgChat>) -> Unit)
    /** Mensajes del chat, de más nuevos a más antiguos, a partir de [fromMessageId] (0 = los últimos). */
    fun loadHistory(chatId: Long, chatTitle: String, fromMessageId: Long, offset: Int = 0, onDone: (List<TgMessage>) -> Unit)
    /** Temas de un supergrupo con temas. */
    fun loadTopics(chatId: Long, onDone: (List<TgTopic>) -> Unit)
    /** Como [loadHistory], pero dentro de un tema. */
    fun loadTopicHistory(chatId: Long, chatTitle: String, topicId: Long, fromMessageId: Long, onDone: (List<TgMessage>) -> Unit)
    /** Resuelve un enlace interno (t.me/usuario, t.me/c/id/msg, t.me/+invitación…). Con [join] entra en el chat si es una invitación. */
    fun resolveLink(link: String, join: Boolean, onDone: (TgLinkTarget?) -> Unit)
    /** Descarga un archivo; [onProgress] (descargado, total) y [onDone] con la ruta local o null si falló. */
    fun download(fileId: Int, onProgress: (Long, Long) -> Unit, onDone: (String?) -> Unit)
    fun cancelDownload(fileId: Int)
    /** Busca mensajes: en un chat ([chatId] != 0) o en todos los chats (0). */
    fun searchMessages(query: String, chatId: Long, onDone: (List<TgMessage>) -> Unit)
    /** Envía un mensaje de texto al chat; [onDone] con true si salió bien. */
    fun sendText(chatId: Long, text: String, replyTo: Long = 0L, topicId: Long = 0L, onDone: (Boolean) -> Unit)
    /** Envía un archivo local como foto o documento, con texto opcional. */
    fun sendFile(chatId: Long, path: String, asPhoto: Boolean, caption: String, topicId: Long = 0L, onDone: (Boolean) -> Unit)
    fun deleteMessage(chatId: Long, messageId: Long, onDone: (Boolean) -> Unit)
    fun joinChat(chatId: Long, onDone: (Boolean) -> Unit)
    /** Busca chats, canales, grupos y bots por nombre: los tuyos y los públicos. */
    fun searchChats(query: String, onDone: (List<TgChat>) -> Unit)
    /**
     * Lee bytes de un archivo sin descargarlo entero (reproducción directa): espera, hasta unos
     * segundos, a que TDLib tenga el trozo que empieza en [pos]. Devuelve null si no llega.
     * Se llama desde hilos de fondo (el servidor local de streaming).
     */
    fun readBytes(fileId: Int, pos: Long, maxLen: Int, cancelled: () -> Boolean = { false }): ByteArray?
    /** Comandos (/start, /help...) que declara un bot: pares (comando sin barra, descripción). */
    /** Borra los archivos que Telegram ha ido descargando (vídeos, fotos…) menos los más recientes. */
    fun cleanCache()

    fun loadBotCommands(userId: Long, onDone: (List<Pair<String, String>>) -> Unit)
    /** Pulsa un botón "cb" de un mensaje; [onDone] recibe el texto de respuesta y/o una URL, si el bot los da. */
    fun pressButton(chatId: Long, messageId: Long, data: String, onDone: (String?, String?) -> Unit)
    /** Un chat concreto por su id (para favoritos que no salen en la lista). */
    fun loadChat(chatId: Long, onDone: (TgChat?) -> Unit)
    /** Reenvía un mensaje a otro chat (y tema, si [topicId] != 0). */
    fun forwardMessage(fromChatId: Long, messageId: Long, toChatId: Long, topicId: Long, onDone: (Boolean) -> Unit)
    /** Enlace público/privado de un mensaje (t.me/...), o null. */
    fun messageLink(chatId: Long, messageId: Long, onDone: (String?) -> Unit)
    /** Marca el chat como leído. */
    fun markRead(chatId: Long, onDone: (Boolean) -> Unit)
    /** Sale de un canal o grupo. */
    fun leaveChat(chatId: Long, onDone: (Boolean) -> Unit)
    /** Cambia el texto de un mensaje tuyo. */
    fun editMessage(chatId: Long, messageId: Long, text: String, onDone: (Boolean) -> Unit)
}

object TelegramAccountPrefs {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("telegram_account", Context.MODE_PRIVATE)

    /**
     * Si rellenas estas dos constantes con TUS credenciales de my.telegram.org, la app no las
     * pedirá nunca (ojo: si el repositorio es público, quedarían visibles). Vacías = se piden en
     * la app una vez y se guardan en el móvil (se conservan al actualizar la app).
     */
    private val DEFAULT_API_ID: Int = com.example.superplayer.BuildConfig.TG_API_ID
    private val DEFAULT_API_HASH: String = com.example.superplayer.BuildConfig.TG_API_HASH

    fun apiId(context: Context): Int = DEFAULT_API_ID.takeIf { it != 0 && DEFAULT_API_HASH.isNotBlank() } ?: prefs(context).getInt("api_id", 0)
    fun apiHash(context: Context): String =
        if (DEFAULT_API_ID != 0 && DEFAULT_API_HASH.isNotBlank()) DEFAULT_API_HASH
        else prefs(context).getString("api_hash", "").orEmpty()

    fun setApi(context: Context, id: Int, hash: String) {
        prefs(context).edit().putInt("api_id", id).putString("api_hash", hash.trim()).apply()
    }

    fun hasApi(context: Context) = apiId(context) != 0 && apiHash(context).isNotBlank()

    /** Vídeos favoritos (la carpeta "Vídeos"): cada uno es el mensaje en JSON + miniatura ("th") y título de búsqueda ("q"). */
    fun videoFavorites(context: Context): List<JSONObject> {
        val arr = try { JSONArray(prefs(context).getString("fav_videos", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun isVideoFavorite(context: Context, chatId: Long, messageId: Long): Boolean =
        videoFavorites(context).any { it.optLong("c") == chatId && it.optLong("m") == messageId }

    /** Marca o quita un vídeo de favoritos; devuelve true si queda como favorito. */
    fun toggleVideoFavorite(context: Context, m: TgMessage, query: String, thumbId: Int): Boolean {
        val list = ArrayList(videoFavorites(context))
        val idx = list.indexOfFirst { it.optLong("c") == m.chatId && it.optLong("m") == m.messageId }
        val now: Boolean
        if (idx >= 0) {
            list.removeAt(idx)
            now = false
        } else {
            list.add(0, m.toJson().put("th", thumbId).put("q", query))
            now = true
        }
        val arr = JSONArray()
        for (o in list) arr.put(o)
        prefs(context).edit().putString("fav_videos", arr.toString()).apply()
        return now
    }

    // ---- Carpetas propias de vídeos (además de favoritos): cada carpeta guarda los mismos datos que un favorito ----

    private fun foldersJson(context: Context): JSONArray =
        try { JSONArray(prefs(context).getString("video_folders", "[]")) } catch (e: Exception) { JSONArray() }

    private fun saveFolders(context: Context, arr: JSONArray) {
        prefs(context).edit().putString("video_folders", arr.toString()).apply()
    }

    private fun findFolder(arr: JSONArray, name: String): JSONObject? =
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("n") == name }

    fun videoFolders(context: Context): List<String> {
        val arr = foldersJson(context)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("n") }.filter { it.isNotEmpty() }
    }

    /** Crea una carpeta; false si el nombre está vacío o ya existe. */
    fun createVideoFolder(context: Context, name: String): Boolean {
        val n = name.trim()
        val arr = foldersJson(context)
        if (n.isEmpty() || findFolder(arr, n) != null) return false
        arr.put(JSONObject().put("n", n).put("v", JSONArray()))
        saveFolders(context, arr)
        return true
    }

    fun renameVideoFolder(context: Context, old: String, name: String): Boolean {
        val n = name.trim()
        val arr = foldersJson(context)
        val f = findFolder(arr, old) ?: return false
        if (n.isEmpty() || (n != old && findFolder(arr, n) != null)) return false
        f.put("n", n)
        saveFolders(context, arr)
        return true
    }

    fun deleteVideoFolder(context: Context, name: String) {
        val arr = foldersJson(context)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("n") != name) out.put(o)
        }
        saveFolders(context, out)
    }

    fun folderVideos(context: Context, name: String): List<JSONObject> {
        val v = findFolder(foldersJson(context), name)?.optJSONArray("v") ?: return emptyList()
        return (0 until v.length()).mapNotNull { v.optJSONObject(it) }
    }

    fun isInFolder(context: Context, name: String, chatId: Long, messageId: Long): Boolean =
        folderVideos(context, name).any { it.optLong("c") == chatId && it.optLong("m") == messageId }

    /** Mete o saca un vídeo de una carpeta; devuelve true si queda dentro. */
    fun toggleInFolder(context: Context, name: String, m: TgMessage, query: String, thumbId: Int): Boolean {
        val arr = foldersJson(context)
        val f = findFolder(arr, name) ?: return false
        val old = f.optJSONArray("v") ?: JSONArray()
        val list = (0 until old.length()).mapNotNull { old.optJSONObject(it) }.toMutableList()
        val idx = list.indexOfFirst { it.optLong("c") == m.chatId && it.optLong("m") == m.messageId }
        val now: Boolean
        if (idx >= 0) {
            list.removeAt(idx)
            now = false
        } else {
            list.add(0, m.toJson().put("th", thumbId).put("q", query))
            now = true
        }
        val v = JSONArray()
        for (o in list) v.put(o)
        f.put("v", v)
        saveFolders(context, arr)
        return now
    }

    fun favoriteChats(context: Context): Set<Long> =
        prefs(context).getStringSet("fav_chats", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    /** Marca o quita un chat de favoritos; devuelve true si queda como favorito. Con [chat] se guarda su ficha para que no se pierda de la lista. */
    fun toggleFavorite(context: Context, chatId: Long, chat: TgChat? = null): Boolean {
        val set = HashSet(prefs(context).getStringSet("fav_chats", emptySet())!!)
        val key = chatId.toString()
        val nowFav = if (set.contains(key)) { set.remove(key); false } else { set.add(key); true }
        prefs(context).edit().putStringSet("fav_chats", set).apply()
        if (nowFav && chat != null) saveChatInfo(context, chat)
        return nowFav
    }

    /** Ficha guardada (nombre, tipo, foto) de los chats favoritos, para mostrarlos aunque Telegram no los traiga en la lista. */
    fun saveChatInfo(context: Context, chat: TgChat) {
        val o = try { JSONObject(prefs(context).getString("fav_chat_info", "{}")) } catch (e: Exception) { JSONObject() }
        o.put(chat.id.toString(), JSONObject().put("t", chat.title).put("k", chat.kind).put("u", chat.userId).put("p", chat.photoId).put("d", chat.lastDate))
        prefs(context).edit().putString("fav_chat_info", o.toString()).apply()
    }

    fun favoriteChatInfo(context: Context): Map<Long, TgChat> {
        val o = try { JSONObject(prefs(context).getString("fav_chat_info", "{}")) } catch (e: Exception) { JSONObject() }
        val out = HashMap<Long, TgChat>()
        for (key in o.keys()) {
            val id = key.toLongOrNull() ?: continue
            val c = o.optJSONObject(key) ?: continue
            out[id] = TgChat(id, c.optString("t"), c.optString("k", "private"), c.optLong("d"), c.optLong("u"), 0, c.optInt("p"))
        }
        return out
    }
}
