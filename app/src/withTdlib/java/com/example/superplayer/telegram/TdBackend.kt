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
    private val userNames = ConcurrentHashMap<Long, String>()
    private val forumIds: MutableSet<Long> = ConcurrentHashMap.newKeySet<Long>()

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
            is TdApi.UpdateUser -> {
                if (obj.user.type is TdApi.UserTypeBot) botIds.add(obj.user.id)
                userNames[obj.user.id] = (obj.user.firstName + " " + obj.user.lastName).trim()
            }
            is TdApi.UpdateSupergroup -> {
                if (obj.supergroup.isForum) forumIds.add(obj.supergroup.id) else forumIds.remove(obj.supergroup.id)
            }
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

    override fun cleanCache() {
        try {
            // Subidas temporales copiadas por la app.
            File(app.cacheDir, "tg_upload").deleteRecursively()
        } catch (e: Exception) {
            // no es imprescindible
        }
        // size/ttl/count a 0 = borrar todo lo que no sea reciente; immunityDelay protege lo descargado hace
        // poco (p. ej. un vídeo que se está viendo). Por reflexión por si cambian los campos entre versiones.
        val request = TdApi.OptimizeStorage()
        fun set(name: String, value: Any) {
            try {
                request.javaClass.getField(name).set(request, value)
            } catch (e: Exception) {
                // campo inexistente en esta versión
            }
        }
        set("size", 0L)
        set("ttl", 0)
        set("count", 0)
        set("immunityDelay", 900)
        set("returnDeletedFileStatistics", false)
        set("chatLimit", 0)
        send(request) { }
    }

    // -----------------------------------------------------------------
    // Temas de supergrupos (foros), enlaces internos. Se usa reflexión en lo que cambia entre
    // versiones de TDLib (nombres de peticiones y campos), así compila con cualquiera.
    // -----------------------------------------------------------------

    private fun field(obj: Any?, vararg names: String): Any? {
        if (obj == null) return null
        for (n in names) {
            try {
                return obj.javaClass.getField(n).get(obj)
            } catch (e: Exception) {
                // siguiente nombre
            }
        }
        return null
    }

    private fun newRequest(name: String, vararg fields: Pair<String, Any>): TdApi.Function<*>? {
        return try {
            val cls = Class.forName("org.drinkless.tdlib.TdApi\$" + name)
            val o = cls.getDeclaredConstructor().newInstance()
            for ((f, v) in fields) {
                try {
                    cls.getField(f).set(o, v)
                } catch (e: Exception) {
                    // campo que no existe en esta versión
                }
            }
            o as? TdApi.Function<*>
        } catch (e: Throwable) {
            null
        }
    }

    /** Como send(), pero sin avisar de errores (se prueba una petición y, si falla, otra). */
    private fun sendQuiet(function: TdApi.Function<*>, onResult: (TdApi.Object) -> Unit) {
        val c = client
        if (c == null) {
            onResult(TdApi.Error(0, "sin cliente"))
            return
        }
        c.send(function) { result -> onResult(result) }
    }

    private fun applyTopic(request: TdApi.SendMessage, topicId: Long) {
        if (topicId == 0L) return
        try {
            request.javaClass.getField("messageThreadId").set(request, topicId)
            return
        } catch (e: Exception) {
            // versión nueva: el tema va en "topicId"
        }
        try {
            val t = Class.forName("org.drinkless.tdlib.TdApi\$MessageTopicForum").getDeclaredConstructor().newInstance()
            t.javaClass.getField("forumTopicId").set(t, topicId.toInt())
            request.javaClass.getField("topicId").set(request, t)
        } catch (e: Exception) {
            // sin soporte de temas en esta versión: sale en el tema General
        }
    }

    override fun loadTopics(chatId: Long, onDone: (List<TgTopic>) -> Unit) {
        val request = newRequest(
            "GetForumTopics",
            "chatId" to chatId, "query" to "", "offsetDate" to 0,
            "offsetMessageId" to 0L, "offsetMessageThreadId" to 0L, "limit" to 100
        )
        if (request == null) {
            onDone(emptyList())
            return
        }
        sendQuiet(request) { result ->
            val topics = field(result, "topics") as? Array<*>
            if (topics == null) {
                onDone(emptyList())
            } else {
                onDone(topics.mapNotNull { t ->
                    val info = field(t, "info")
                    val id = (field(info, "forumTopicId", "messageThreadId") as? Number)?.toLong() ?: return@mapNotNull null
                    val name = field(info, "name") as? String ?: return@mapNotNull null
                    val date = ((field(t, "lastMessage") as? TdApi.Message)?.date ?: 0).toLong()
                    val unread = (field(t, "unreadCount") as? Number)?.toInt() ?: 0
                    TgTopic(id, name, date, unread)
                })
            }
        }
    }

    override fun loadTopicHistory(chatId: Long, chatTitle: String, topicId: Long, fromMessageId: Long, onDone: (List<TgMessage>) -> Unit) {
        fun finish(result: TdApi.Object): Boolean {
            if (result is TdApi.Messages) {
                onDone(result.messages.mapNotNull { toMessage(it, chatTitle) })
                return true
            }
            return false
        }
        val first = newRequest(
            "GetForumTopicHistory",
            "chatId" to chatId, "forumTopicId" to topicId.toInt(), "fromMessageId" to fromMessageId, "offset" to 0, "limit" to 50
        )
        val second = newRequest(
            "GetMessageThreadHistory",
            "chatId" to chatId, "messageId" to topicId, "fromMessageId" to fromMessageId, "offset" to 0, "limit" to 50
        )
        fun fallback() {
            if (second == null) {
                onDone(emptyList())
            } else {
                sendQuiet(second) { r -> if (!finish(r)) onDone(emptyList()) }
            }
        }
        if (first == null) fallback() else sendQuiet(first) { r -> if (!finish(r)) fallback() }
    }

    private fun tgChatOf(chat: TdApi.Chat): TgChat {
        chats[chat.id] = chat
        return TgChat(
            chat.id, chat.title, kindOf(chat), chat.lastMessage?.date?.toLong() ?: 0L,
            (chat.type as? TdApi.ChatTypePrivate)?.userId ?: 0L, chat.unreadCount, chat.photo?.small?.id ?: 0
        )
    }

    override fun resolveLink(link: String, join: Boolean, onDone: (TgLinkTarget?) -> Unit) {
        var clean = link.trim()
        var query = ""
        val qi = clean.indexOf('?')
        if (qi >= 0) {
            query = clean.substring(qi + 1)
            clean = clean.substring(0, qi)
        }
        fun param(name: String): String =
            query.split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=').orEmpty()
        var path = clean.substringAfter("://", clean).substringBefore('#')
        for (host in listOf("t.me/", "telegram.me/", "telegram.dog/")) {
            if (path.startsWith(host)) path = path.removePrefix(host)
        }
        path = path.removePrefix("s/").trim('/')
        // tg://resolve?domain=usuario&start=x  ·  tg://join?invite=hash
        if (clean.startsWith("tg://")) {
            val domain = param("domain")
            val invite = param("invite")
            path = when {
                invite.isNotEmpty() -> "+$invite"
                domain.isNotEmpty() -> domain
                else -> ""
            }
        }
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            onDone(null)
            return
        }
        fun withChat(chat: TdApi.Chat, messageId: Long) {
            onDone(TgLinkTarget(tgChatOf(chat), messageId, param("start")))
        }
        val first = parts[0]
        when {
            // Chat privado/supergrupo por número: t.me/c/<id>/<mensaje>
            first == "c" && parts.size >= 2 && parts[1].toLongOrNull() != null -> {
                val chatId = ("-100" + parts[1]).toLongOrNull()
                val msg = parts.drop(2).lastOrNull()?.toLongOrNull() ?: 0L
                if (chatId == null) {
                    onDone(null)
                } else {
                    sendQuiet(TdApi.GetChat(chatId)) { r ->
                        if (r is TdApi.Chat) withChat(r, msg shl 20) else onDone(null)
                    }
                }
            }
            // Invitaciones: t.me/+hash · t.me/joinchat/hash
            first.startsWith("+") || first == "joinchat" -> {
                val hash = if (first == "joinchat") parts.getOrNull(1).orEmpty() else first.removePrefix("+")
                val inviteLink = "https://t.me/+$hash"
                sendQuiet(TdApi.CheckChatInviteLink(inviteLink)) { r ->
                    if (r is TdApi.ChatInviteLinkInfo) {
                        if (r.chatId != 0L) {
                            sendQuiet(TdApi.GetChat(r.chatId)) { c -> if (c is TdApi.Chat) withChat(c, 0L) else onDone(null) }
                        } else if (join) {
                            sendQuiet(TdApi.JoinChatByInviteLink(inviteLink)) { c -> if (c is TdApi.Chat) withChat(c, 0L) else onDone(null) }
                        } else {
                            onDone(TgLinkTarget(TgChat(0L, r.title, "invite", 0L)))
                        }
                    } else {
                        onDone(null)
                    }
                }
            }
            // Usuario, canal, grupo o bot públicos: t.me/nombre[/mensaje]
            else -> {
                val msg = parts.drop(1).lastOrNull()?.toLongOrNull() ?: 0L
                sendQuiet(TdApi.SearchPublicChat(first)) { r ->
                    if (r is TdApi.Chat) withChat(r, msg shl 20) else onDone(null)
                }
            }
        }
    }

    override fun logOut() {
        send(TdApi.LogOut())
    }

    private fun kindOf(chat: TdApi.Chat): String {
        val t = chat.type
        return when {
            t is TdApi.ChatTypePrivate && botIds.contains(t.userId) -> "bot"
            t is TdApi.ChatTypeSupergroup && t.isChannel -> "channel"
            t is TdApi.ChatTypeSupergroup && forumIds.contains(t.supergroupId) -> "forum"
            t is TdApi.ChatTypeSupergroup || t is TdApi.ChatTypeBasicGroup -> "group"
            else -> "private"
        }
    }

    override fun loadChats(onDone: (List<TgChat>) -> Unit) {
        send(TdApi.GetChats(TdApi.ChatListMain(), 200)) { result ->
            if (result is TdApi.Chats) {
                val list = result.chatIds.toList().mapNotNull { id ->
                    val chat = chats[id] ?: return@mapNotNull null
                    TgChat(id, chat.title, kindOf(chat), chat.lastMessage?.date?.toLong() ?: 0L, (chat.type as? TdApi.ChatTypePrivate)?.userId ?: 0L, chat.unreadCount, chat.photo?.small?.id ?: 0)
                }
                onDone(list)
            } else {
                onDone(emptyList())
            }
        }
    }

    private val linkRegex = Regex("(?:https?://|tg://)[^\\s<>\"']+|(?<![\\w/@.])t\\.me/[^\\s<>\"']+")

    override fun loadHistory(chatId: Long, chatTitle: String, fromMessageId: Long, offset: Int, onDone: (List<TgMessage>) -> Unit) {
        send(TdApi.GetChatHistory(chatId, fromMessageId, offset, 50, false)) { result ->
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
        var fmt: TdApi.FormattedText? = null
        var thumb: TdApi.File? = null
        when (val c = m.content) {
            is TdApi.MessageText -> { fmt = c.text; text = c.text.text }
            is TdApi.MessageVideo -> {
                kind = "video"; thumb = c.video.thumbnail?.file; file = c.video.video; name = c.video.fileName; mime = c.video.mimeType; fmt = c.caption; text = c.caption.text
            }
            is TdApi.MessageAnimation -> {
                kind = "animation"; thumb = c.animation.thumbnail?.file; file = c.animation.animation; name = c.animation.fileName; mime = c.animation.mimeType; fmt = c.caption; text = c.caption.text
            }
            is TdApi.MessageAudio -> {
                kind = "audio"; file = c.audio.audio; name = c.audio.fileName; mime = c.audio.mimeType; fmt = c.caption; text = c.caption.text
            }
            is TdApi.MessageVoiceNote -> {
                kind = "voice"; file = c.voiceNote.voice; mime = c.voiceNote.mimeType; fmt = c.caption; text = c.caption.text
            }
            is TdApi.MessageDocument -> {
                kind = "document"; thumb = c.document.thumbnail?.file; file = c.document.document; name = c.document.fileName; mime = c.document.mimeType; fmt = c.caption; text = c.caption.text
            }
            is TdApi.MessagePhoto -> {
                kind = "photo"; file = c.photo.sizes.lastOrNull()?.photo;
                thumb = (c.photo.sizes.firstOrNull { it.width >= 90 } ?: c.photo.sizes.firstOrNull())?.photo; name = null; mime = "image/jpeg"; fmt = c.caption; text = c.caption.text
            }
            is TdApi.MessageSticker -> { kind = "other"; text = "🏷 Sticker " + c.sticker.emoji }
            is TdApi.MessagePoll -> { kind = "other"; text = "📊 Encuesta" }
            is TdApi.MessageLocation -> { kind = "other"; text = "📍 Ubicación" }
            is TdApi.MessageContact -> { kind = "other"; text = "👤 Contacto" }
            else -> { kind = "other"; text = "(mensaje no compatible con esta app)" }
        }
        val found = ArrayList<String>()
        linkRegex.findAll(text).forEach { found.add(it.value.trimEnd('.', ',', ')', ']', ';', '!')) }
        fmt?.entities?.forEach { e ->
            val t = e.type
            if (t is TdApi.TextEntityTypeTextUrl) found.add(t.url)
        }
        val links = found.map { if (it.startsWith("t.me/")) "https://$it" else it }.distinct()
        if (kind == "none" && text.isEmpty()) return null
        val senderName = when (val sid = m.senderId) {
            is TdApi.MessageSenderUser -> userNames[sid.userId] ?: ""
            is TdApi.MessageSenderChat -> chats[sid.chatId]?.title ?: ""
            else -> ""
        }
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
            file?.let { if (it.size > 0) it.size else it.expectedSize } ?: 0L, links, buttons,
            m.isOutgoing, senderName, thumb?.id ?: 0
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

    override fun sendText(chatId: Long, text: String, replyTo: Long, topicId: Long, onDone: (Boolean) -> Unit) {
        val content = TdApi.InputMessageText()
        content.text = TdApi.FormattedText(text, arrayOf<TdApi.TextEntity>())
        val request = TdApi.SendMessage()
        request.chatId = chatId
        request.inputMessageContent = content
        applyTopic(request, topicId)
        if (replyTo != 0L) {
            val r = TdApi.InputMessageReplyToMessage()
            r.messageId = replyTo
            request.replyTo = r
        }
        send(request) { result -> onDone(result is TdApi.Message) }
    }

    override fun sendFile(chatId: Long, path: String, asPhoto: Boolean, caption: String, topicId: Long, onDone: (Boolean) -> Unit) {
        val file = TdApi.InputFileLocal(path)
        val cap = TdApi.FormattedText(caption, arrayOf<TdApi.TextEntity>())
        val content: TdApi.InputMessageContent
        if (asPhoto) {
            val c = TdApi.InputMessagePhoto()
            assignInput(c, "photo", file)
            c.caption = cap
            content = c
        } else {
            val c = TdApi.InputMessageDocument()
            assignInput(c, "document", file)
            c.caption = cap
            content = c
        }
        val request = TdApi.SendMessage()
        request.chatId = chatId
        request.inputMessageContent = content
        applyTopic(request, topicId)
        send(request) { result -> onDone(result is TdApi.Message) }
    }

    override fun deleteMessage(chatId: Long, messageId: Long, onDone: (Boolean) -> Unit) {
        send(TdApi.DeleteMessages(chatId, longArrayOf(messageId), true)) { result -> onDone(result is TdApi.Ok) }
    }

    override fun joinChat(chatId: Long, onDone: (Boolean) -> Unit) {
        send(TdApi.JoinChat(chatId)) { result -> onDone(result is TdApi.Ok) }
    }

    /**
     * Versiones recientes de TDLib envuelven el archivo (InputPhoto / InputDocument) en vez de
     * aceptar un InputFile directo; se detecta el tipo del campo en ejecución para valer con ambas.
     */
    private fun assignInput(target: Any, fieldName: String, file: TdApi.InputFile) {
        try {
            val field = target.javaClass.getField(fieldName)
            val type = field.type
            if (type.isAssignableFrom(file.javaClass)) {
                field.set(target, file)
                return
            }
            val wrapper = type.getDeclaredConstructor().newInstance()
            val inner = type.fields.firstOrNull { it.type.isAssignableFrom(file.javaClass) }
            inner?.set(wrapper, file)
            field.set(target, wrapper)
        } catch (e: Exception) {
            listener?.onError(e.message ?: "adjunto")
        }
    }

    /** Rellena el filtro de tipo de chat (si esta versión de TDLib lo pide) con el que admite todos los tipos. */
    private fun fillTypeFilter(request: Any) {
        try {
            val field = request.javaClass.getField("typeFilter")
            val base = field.type
            val candidates = TdApi::class.java.declaredClasses.filter {
                base.isAssignableFrom(it) && it != base && !java.lang.reflect.Modifier.isAbstract(it.modifiers)
            }
            val pick = candidates.firstOrNull { it.simpleName.contains("All") } ?: candidates.firstOrNull()
            if (pick != null) field.set(request, pick.getDeclaredConstructor().newInstance())
        } catch (e: Exception) {
            // si no existe el campo, la petición ya vale tal cual
        }
    }

    override fun searchChats(query: String, onDone: (List<TgChat>) -> Unit) {
        val ids = java.util.LinkedHashSet<Long>()
        val remaining = java.util.concurrent.atomic.AtomicInteger(2)
        fun finish() {
            if (remaining.decrementAndGet() != 0) return
            val missing = ids.filter { chats[it] == null }
            val finalize = {
                onDone(ids.mapNotNull { id ->
                    val chat = chats[id] ?: return@mapNotNull null
                    TgChat(id, chat.title, kindOf(chat), chat.lastMessage?.date?.toLong() ?: 0L,
                        (chat.type as? TdApi.ChatTypePrivate)?.userId ?: 0L, chat.unreadCount, chat.photo?.small?.id ?: 0)
                })
            }
            if (missing.isEmpty()) {
                finalize()
            } else {
                val pending = java.util.concurrent.atomic.AtomicInteger(missing.size)
                for (id in missing) {
                    send(TdApi.GetChat(id)) { r ->
                        if (r is TdApi.Chat) chats[id] = r
                        if (pending.decrementAndGet() == 0) finalize()
                    }
                }
            }
        }
        val searchOwn = TdApi.SearchChats()
        searchOwn.query = query
        searchOwn.limit = 30
        fillTypeFilter(searchOwn)
        send(searchOwn) { r ->
            if (r is TdApi.Chats) synchronized(ids) { r.chatIds.forEach { ids.add(it) } }
            finish()
        }
        val searchPublic = TdApi.SearchPublicChats()
        searchPublic.query = query
        fillTypeFilter(searchPublic)
        send(searchPublic) { r ->
            if (r is TdApi.Chats) synchronized(ids) { r.chatIds.forEach { ids.add(it) } }
            finish()
        }
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

    override fun readBytes(fileId: Int, pos: Long, maxLen: Int, cancelled: () -> Boolean): ByteArray? {
        val deadline = System.currentTimeMillis() + 40_000
        var requestedAt = -1L
        while (System.currentTimeMillis() < deadline && !cancelled()) {
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
