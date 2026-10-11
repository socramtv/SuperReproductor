package com.example.superplayer.telegram

import android.content.Context

enum class TgAuthState { STARTING, WAIT_PHONE, WAIT_CODE, WAIT_PASSWORD, READY, CLOSED }

/** [kind]: channel, group, bot o private. [userId]: el del otro usuario en chats privados y bots (0 si no aplica). */
data class TgChat(val id: Long, val title: String, val kind: String, val lastDate: Long, val userId: Long = 0L, val unread: Int = 0)

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
    fun loadHistory(chatId: Long, chatTitle: String, fromMessageId: Long, onDone: (List<TgMessage>) -> Unit)
    /** Descarga un archivo; [onProgress] (descargado, total) y [onDone] con la ruta local o null si falló. */
    fun download(fileId: Int, onProgress: (Long, Long) -> Unit, onDone: (String?) -> Unit)
    fun cancelDownload(fileId: Int)
    /** Busca mensajes: en un chat ([chatId] != 0) o en todos los chats (0). */
    fun searchMessages(query: String, chatId: Long, onDone: (List<TgMessage>) -> Unit)
    /** Envía un mensaje de texto al chat; [onDone] con true si salió bien. */
    fun sendText(chatId: Long, text: String, replyTo: Long = 0L, onDone: (Boolean) -> Unit)
    /** Envía un archivo local como foto o documento, con texto opcional. */
    fun sendFile(chatId: Long, path: String, asPhoto: Boolean, caption: String, onDone: (Boolean) -> Unit)
    fun deleteMessage(chatId: Long, messageId: Long, onDone: (Boolean) -> Unit)
    fun joinChat(chatId: Long, onDone: (Boolean) -> Unit)
    /** Busca chats, canales, grupos y bots por nombre: los tuyos y los públicos. */
    fun searchChats(query: String, onDone: (List<TgChat>) -> Unit)
    /**
     * Lee bytes de un archivo sin descargarlo entero (reproducción directa): espera, hasta unos
     * segundos, a que TDLib tenga el trozo que empieza en [pos]. Devuelve null si no llega.
     * Se llama desde hilos de fondo (el servidor local de streaming).
     */
    fun readBytes(fileId: Int, pos: Long, maxLen: Int): ByteArray?
    /** Comandos (/start, /help...) que declara un bot: pares (comando sin barra, descripción). */
    fun loadBotCommands(userId: Long, onDone: (List<Pair<String, String>>) -> Unit)
    /** Pulsa un botón "cb" de un mensaje; [onDone] recibe el texto de respuesta y/o una URL, si el bot los da. */
    fun pressButton(chatId: Long, messageId: Long, data: String, onDone: (String?, String?) -> Unit)
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

    fun favoriteChats(context: Context): Set<Long> =
        prefs(context).getStringSet("fav_chats", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    /** Marca o quita un chat de favoritos; devuelve true si queda como favorito. */
    fun toggleFavorite(context: Context, chatId: Long): Boolean {
        val set = HashSet(prefs(context).getStringSet("fav_chats", emptySet())!!)
        val key = chatId.toString()
        val nowFav = if (set.contains(key)) { set.remove(key); false } else { set.add(key); true }
        prefs(context).edit().putStringSet("fav_chats", set).apply()
        return nowFav
    }
}
