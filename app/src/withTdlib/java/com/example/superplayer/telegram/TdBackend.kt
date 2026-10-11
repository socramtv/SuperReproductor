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
    private val botIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

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
            is TdApi.UpdateUser -> if (obj.user.type is TdApi.UserTypeBot) botIds.add(obj.user.id)
            is TdApi.UpdateNewMessage -> listener?.onNewMessage(obj.message.chatId)
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
            t is TdApi.ChatTypePrivate && botIds.contains(t.userId) -> "bot"
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
                    TgChat(id, chat.title, kindOf(chat), chat.lastMessage?.date?.toLong() ?: 0L, (chat.type as? TdApi.ChatTypePrivate)?.userId ?: 0L)
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
        val buttons = ArrayList<TgButton>()
        when (val rm = m.replyMarkup) {
            is TdApi.ReplyMarkupInlineKeyboard -> for (row in rm.rows) for (b in row) {
                when (val bt = b.type) {
                    is TdApi.InlineKeyboardButtonTypeCallback ->
                        buttons.add(TgButton(b.text, "cb", android.util.Base64.encodeToString(bt.data, android.util.Base64.NO_WRAP)))
                    is TdApi.InlineKeyboardButtonTypeUrl -> buttons.add(TgButton(b.text, "url", bt.url))
                    else -> {}
                }
            }
            is TdApi.ReplyMarkupShowKeyboard -> for (row in rm.rows) for (b in row) buttons.add(TgButton(b.text, "txt", b.text))
            else -> {}
        }
        val chatKind = chats[m.chatId]?.let { kindOf(it) } ?: "private"
        return TgMessage(
            m.chatId, chatTitle, chatKind, m.id, m.date.toLong(), text, kind,
            file?.id?.toString(), name?.ifEmpty { null }, mime?.ifEmpty { null },
            file?.let { if (it.size > 0) it.size else it.expectedSize } ?: 0L, links, buttons
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

    override fun searchMessages(query: String, chatId: Long, onDone: (List<TgMessage>) -> Unit) {
        val request: TdApi.Function<*>
        if (chatId != 0L) {
            val r = TdApi.SearchChatMessages()
            r.chatId = chatId
            r.query = query
            r.fromMessageId = 0L
            r.offset = 0
            r.limit = 100
            request = r
        } else {
            val r = TdApi.SearchMessages()
            r.chatList = TdApi.ChatListMain()
            r.query = query
            r.offset = ""
            r.limit = 100
            request = r
        }
        send(request) { result ->
            val found: Array<TdApi.Message> = when (result) {
                is TdApi.FoundMessages -> result.messages
                is TdApi.FoundChatMessages -> result.messages
                else -> emptyArray()
            }
            onDone(found.mapNotNull { toMessage(it, chats[it.chatId]?.title ?: "") })
        }
    }

    override fun sendText(chatId: Long, text: String, onDone: (Boolean) -> Unit) {
        val content = TdApi.InputMessageText()
        content.text = TdApi.FormattedText(text, arrayOf<TdApi.TextEntity>())
        val request = TdApi.SendMessage()
        request.chatId = chatId
        request.inputMessageContent = content
        send(request) { result -> onDone(result is TdApi.Message) }
    }

    override fun loadBotCommands(userId: Long, onDone: (List<Pair<String, String>>) -> Unit) {
        send(TdApi.GetUserFullInfo(userId)) { r ->
            if (r is TdApi.UserFullInfo) {
                val cmds = r.botInfo?.commands ?: emptyArray()
                onDone(cmds.map { it.command to it.description })
            } else {
                onDone(emptyList())
            }
        }
    }

    override fun pressButton(chatId: Long, messageId: Long, data: String, onDone: (String?, String?) -> Unit) {
        val bytes = android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
        send(TdApi.GetCallbackQueryAnswer(chatId, messageId, TdApi.CallbackQueryPayloadData(bytes))) { r ->
            if (r is TdApi.CallbackQueryAnswer) {
                onDone(r.text?.ifEmpty { null }, r.url?.ifEmpty { null })
            } else {
                onDone(null, null)
            }
        }
    }

    private fun getFileBlocking(fileId: Int): TdApi.File? {
        val c = client ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: TdApi.File? = null
        c.send(TdApi.GetFile(fileId)) { r ->
            if (r is TdApi.File) out = r
            latch.countDown()
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    override fun readBytes(fileId: Int, pos: Long, maxLen: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + 40_000
        var requestedAt = -1L
        while (System.currentTimeMillis() < deadline) {
            val f = getFileBlocking(fileId) ?: return null
            val l = f.local
            val total = if (f.size > 0) f.size else f.expectedSize
            if (l.path.isNotEmpty()) {
                val available: Long = when {
                    l.isDownloadingCompleted -> total - pos
                    pos >= l.downloadOffset && pos < l.downloadOffset + l.downloadedPrefixSize ->
                        l.downloadOffset + l.downloadedPrefixSize - pos
                    else -> 0L
                }
                if (available > 0) {
                    val n = minOf(available, maxLen.toLong()).toInt()
                    return try {
                        java.io.RandomAccessFile(l.path, "r").use { raf ->
                            raf.seek(pos)
                            val buf = ByteArray(n)
                            var read = 0
                            while (read < n) {
                                val r = raf.read(buf, read, n - read)
                                if (r < 0) break
                                read += r
                            }
                            if (read == n) buf else buf.copyOf(read)
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            // Hay que pedir a TDLib que descargue desde esta posición (si no lo está haciendo ya).
            if (requestedAt != pos || !l.isDownloadingActive) {
                requestedAt = pos
                send(TdApi.DownloadFile(fileId, 32, pos, STREAM_WINDOW, false))
            }
            try { Thread.sleep(120) } catch (e: InterruptedException) { return null }
        }
        return null
    }

    companion object {
        /** Cuánto pide a TDLib de una vez al reproducir sin descargar (así no baja el vídeo entero). */
        private const val STREAM_WINDOW = 32L * 1024 * 1024

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
