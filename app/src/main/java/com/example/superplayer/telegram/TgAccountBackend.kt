package com.example.superplayer.telegram

import android.content.Context

enum class TgAuthState { STARTING, WAIT_PHONE, WAIT_CODE, WAIT_PASSWORD, READY, CLOSED }

data class TgChat(val id: Long, val title: String, val kind: String, val lastDate: Long)

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
}

object TelegramAccountPrefs {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("telegram_account", Context.MODE_PRIVATE)

    fun apiId(context: Context): Int = prefs(context).getInt("api_id", 0)
    fun apiHash(context: Context): String = prefs(context).getString("api_hash", "").orEmpty()

    fun setApi(context: Context, id: Int, hash: String) {
        prefs(context).edit().putInt("api_id", id).putString("api_hash", hash.trim()).apply()
    }

    fun hasApi(context: Context) = apiId(context) != 0 && apiHash(context).isNotBlank()
}
