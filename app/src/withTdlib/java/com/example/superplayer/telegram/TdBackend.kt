package com.example.superplayer.telegram

import android.content.Context
import android.os.Build
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementación con TDLib (API oficial de Telegram para cuentas de usuario).
 * Un único cliente para todo el proceso.
 */
class TdBackend private constructor(context: Context) : TgAccountBackend {

    private val app = context.applicationContext
    private var client: Client? = null
    private var listener: TgAccountBackend.Listener? = null
    private var state = TgAuthState.STARTING
    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()

    private class Download(val onProgress: (Long, Long) -> Unit, val onDone: (String?) -> Unit)
    private val downloads = ConcurrentHashMap<Int, Download>()

    @Synchronized
    override fun start(listener: TgAccountBackend.Listener) {
        this.listener = listener
        if (client == null) {
            try {
                Client.execute(TdApi.SetLogVerbosityLevel(1))
            } catch (e: Throwable) {
                // No es imprescindible.
            }
            client = Client.create({ obj -> onUpdate(obj) }, null, null)
        } else {
            listener.onState(state)
        }
    }

    private fun setState(newState: TgAuthState) {
        state = newState
        listener?.onState(newState)
    }

    private fun send(function: TdApi.Function<*>, onResult: ((TdApi.Object) -> Unit)? = null) {
        val c = client ?: return
        c.send(function) { result ->
            if (result is TdApi.Error) {
                listener?.onError(result.message)
            }
            onResult?.invoke(result)
        }
    }

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> handleAuth(obj.authorizationState)
            is TdApi.UpdateNewChat -> chats[obj.chat.id] = obj.chat
            is TdApi.UpdateFile -> {
                val file = obj.file
                val d = downloads[file.id] ?: return
                val total = if (file.size > 0) file.size else file.expectedSize
                d.onProgress(file.local.downloadedSize, total)
                if (file.local.isDownloadingCompleted) {
                    downloads.remove(file.id)
                    d.onDone(file.local.path)
                }
            }
            else -> {}
        }
    }

    private fun handleAuth(auth: TdApi.AuthorizationState) {
        when (auth) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val p = TdApi.SetTdlibParameters()
                p.useTestDc = false
                p.databaseDirectory = File(app.filesDir, "tdlib").absolutePath
                p.filesDirectory = File(app.getExternalFilesDir(null) ?: app.filesDir, "tdlib_files").absolutePath
                p.databaseEncryptionKey = ByteArray(0)
                p.useFileDatabase = true
                p.useChatInfoDatabase = true
                p.useMessageDatabase = true
                p.useSecretChats = false
                p.apiId = TelegramAccountPrefs.apiId(app)
                p.apiHash = TelegramAccountPrefs.apiHash(app)
                p.systemLanguageCode = "es"
                p.deviceModel = Build.MODEL
                p.systemVersion = Build.VERSION.RELEASE
                p.applicationVersion = "1.0"
                send(p)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> setState(TgAuthState.WAIT_PHONE)
            is TdApi.AuthorizationStateWaitCode -> setState(TgAuthState.WAIT_CODE)
            is TdApi.AuthorizationStateWaitPassword -> setState(TgAuthState.WAIT_PASSWORD)
            is TdApi.AuthorizationStateReady -> setState(TgAuthState.READY)
            is TdApi.AuthorizationStateLoggingOut,
            is TdApi.AuthorizationStateClosing,
            is TdApi.AuthorizationStateClosed -> {
                if (auth is TdApi.AuthorizationStateClosed) {
                    client = null
                    chats.clear()
                }
                setState(TgAuthState.CLOSED)
            }
            else -> listener?.onError("Paso de inicio de sesión no soportado: " + auth.javaClass.simpleName)
        }
    }

    override fun sendPhone(phone: String) {
        send(TdApi.SetAuthenticationPhoneNumber(phone.trim(), null))
    }

    override fun sendCode(code: String) {
        send(TdApi.CheckAuthenticationCode(code.trim()))
    }

    override fun sendPassword(password: String) {
        send(TdApi.CheckAuthenticationPassword(password))
    }

    override fun logOut() {
        send(TdApi.LogOut())
    }

    private fun kindOf(chat: TdApi.Chat): String {
        val t = chat.type
        return when {
            t is TdApi.ChatTypeSupergroup && t.isChannel -> "channel"
            t is TdApi.ChatTypeSupergroup || t is TdApi.ChatTypeBasicGroup -> "group"
            else -> "private"
        }
    }

    override fun loadChats(onDone: (List<TgChat>) -> Unit) {
        send(TdApi.GetChats(TdApi.ChatListMain(), 200)) { result ->
            if (result is TdApi.Chats) {
                val list = result.chatIds.toList().mapNotNull { id ->
                    val chat = chats[id] ?: return@mapNotNull null
                    TgChat(id, chat.title, kindOf(chat), chat.lastMessage?.date?.toLong() ?: 0L)
                }
                onDone(list)
            } else {
                onDone(emptyList())
            }
        }
    }

    private val linkRegex = Regex("https?://[^\\s<>\"']+")

    override fun loadHistory(chatId: Long, chatTitle: String, fromMessageId: Long, onDone: (List<TgMessage>) -> Unit) {
        send(TdApi.GetChatHistory(chatId, fromMessageId, 0, 50, false)) { result ->
            if (result is TdApi.Messages) {
                onDone(result.messages.mapNotNull { toMessage(it, chatTitle) })
            } else {
                onDone(emptyList())
            }
        }
    }

    private fun toMessage(m: TdApi.Message, chatTitle: String): TgMessage? {
        var text = ""
        var kind = "none"
        var file: TdApi.File? = null
        var name: String? = null
        var mime: String? = null
        when (val c = m.content) {
            is TdApi.MessageText -> text = c.text.text
            is TdApi.MessageVideo -> {
                kind = "video"; file = c.video.video; name = c.video.fileName; mime = c.video.mimeType; text = c.caption.text
            }
            is TdApi.MessageAnimation -> {
                kind = "animation"; file = c.animation.animation; name = c.animation.fileName; mime = c.animation.mimeType; text = c.caption.text
            }
            is TdApi.MessageAudio -> {
                kind = "audio"; file = c.audio.audio; name = c.audio.fileName; mime = c.audio.mimeType; text = c.caption.text
            }
            is TdApi.MessageVoiceNote -> {
                kind = "voice"; file = c.voiceNote.voice; mime = c.voiceNote.mimeType; text = c.caption.text
            }
            is TdApi.MessageDocument -> {
                kind = "document"; file = c.document.document; name = c.document.fileName; mime = c.document.mimeType; text = c.caption.text
            }
            else -> return null
        }
        val links = linkRegex.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', ';', '!') }.distinct().toList()
        if (kind == "none" && text.isEmpty()) return null
        val chatKind = chats[m.chatId]?.let { kindOf(it) } ?: "private"
        return TgMessage(
            m.chatId, chatTitle, chatKind, m.id, m.date.toLong(), text, kind,
            file?.id?.toString(), name?.ifEmpty { null }, mime?.ifEmpty { null },
            file?.let { if (it.size > 0) it.size else it.expectedSize } ?: 0L, links
        )
    }

    override fun download(fileId: Int, onProgress: (Long, Long) -> Unit, onDone: (String?) -> Unit) {
        downloads[fileId] = Download(onProgress, onDone)
        send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { result ->
            if (result is TdApi.Error) {
                downloads.remove(fileId)
                onDone(null)
            } else if (result is TdApi.File && result.local.isDownloadingCompleted) {
                downloads.remove(fileId)
                onDone(result.local.path)
            }
        }
    }

    override fun cancelDownload(fileId: Int) {
        downloads.remove(fileId)
        send(TdApi.CancelDownloadFile(fileId, false))
    }

    companion object {
        @Volatile
        private var instance: TdBackend? = null

        fun get(context: Context): TdBackend? {
            return try {
                instance ?: synchronized(this) {
                    instance ?: TdBackend(context).also { instance = it }
                }
            } catch (e: Throwable) {
                null
            }
        }
    }
}
