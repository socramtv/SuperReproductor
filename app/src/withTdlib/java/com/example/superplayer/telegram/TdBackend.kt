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
        watchStartup(0)
    }

    /**
     * Si tras unos segundos seguimos en "conectando", se le pregunta a TDLib en qué paso está
     * y se actúa según su respuesta (por si se perdió la primera actualización de inicio).
     */
    private fun watchStartup(attempt: Int) {
        if (attempt >= 4) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (state == TgAuthState.STARTING) {
                val c = synchronized(this) { client }
                c?.send(TdApi.GetAuthorizationState()) { r ->
                    if (r is TdApi.AuthorizationState) handleAuth(r)
                }
                watchStartup(attempt + 1)
            }
        }, 5000)
    }

    private fun setState(newState: TgAuthState) {
        state = newState
        listener?.onState(newState)
    }

    private fun send(function: TdApi.Function<*>, onResult: ((TdApi.Object) -> Unit)? = null) {
        // Se espera a que start() termine de asignar el cliente: TDLib puede mandar la primera
        // actualización (WaitTdlibParameters) antes de que Client.create() haya devuelto, y si
        // aquí el cliente aún fuera nulo se perdería SetTdlibParameters y se quedaría "conectando" para siempre.
        val c = synchronized(this) { client } ?: return
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
            else -> onOtherUpdate(obj)
        }
    }

    /** Actualizaciones que cambian de nombre según la versión de TDLib (directos/chats de vídeo): se leen sin nombrar la clase. */
    @Volatile private var folders: List<TgFolder> = emptyList()

    private fun onOtherUpdate(obj: TdApi.Object) {
        val n = obj.javaClass.simpleName
        if (n == "UpdateChatFolders") {
            try {
                val arr = field(obj, "chatFolders") as? Array<*> ?: return
                folders = arr.mapNotNull { f ->
                    if (f == null) return@mapNotNull null
                    val id = (field(f, "id") as? Number)?.toInt() ?: return@mapNotNull null
                    val nm = field(f, "name", "title")
                    val name = when (nm) {
                        is String -> nm
                        null -> ""
                        else -> (field(field(nm, "text"), "text") as? String) ?: (field(nm, "text") as? String) ?: ""
                    }
                    val shared = (field(f, "isShareable") as? Boolean ?: false) || (field(f, "hasMyInviteLinks") as? Boolean ?: false)
                    TgFolder(id, name.ifBlank { "Carpeta $id" }, shared)
                }
            } catch (e: Exception) { }
            return
        }
        if (n == "UpdateChatVideoChat" || n == "UpdateChatVoiceChat") {
            try {
                val chatId = field(obj, "chatId") as? Long ?: return
                val chat = chats[chatId] ?: return
                val value = field(obj, "videoChat", "voiceChat") ?: return
                for (name in listOf("videoChat", "voiceChat")) {
                    try {
                        chat.javaClass.getField(name).set(chat, value)
                        break
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) { }
        }
    }

    /** true si el chat tiene ahora un directo / chat de vídeo con gente. */
    private fun isLive(chat: TdApi.Chat): Boolean {
        val vc = field(chat, "videoChat", "voiceChat") ?: return false
        val id = (field(vc, "groupCallId") as? Number)?.toInt() ?: 0
        val has = field(vc, "hasParticipants") as? Boolean ?: false
        return id != 0 && has
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

    private fun applyTopic(request: Any, topicId: Long) {
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
            (chat.type as? TdApi.ChatTypePrivate)?.userId ?: 0L, chat.unreadCount, chat.photo?.small?.id ?: 0, isLive(chat)
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
        val c = client
        if (c == null) {
            onDone(emptyList())
            return
        }
        // Telegram solo manda los chats que se le piden: hay que ir cargando la lista (principal y archivados) hasta el final.
        val all = java.util.LinkedHashMap<Long, TgChat>()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            onDone(all.values.toList())
        }
        fun snapshot(id: Long) {
            val chat = chats[id] ?: return
            if (!all.containsKey(id)) all[id] = tgChatOf(chat)
        }
        fun fetch(list: TdApi.ChatList, next: () -> Unit) {
            c.send(TdApi.GetChats(list, 1000)) { r ->
                if (r is TdApi.Chats) synchronized(all) { r.chatIds.forEach { snapshot(it) } }
                next()
            }
        }
        fun loadAll(list: TdApi.ChatList, round: Int, next: () -> Unit) {
            if (round >= 30) {
                fetch(list, next)
                return
            }
            c.send(TdApi.LoadChats(list, 100)) { r ->
                if (r is TdApi.Ok) loadAll(list, round + 1, next) else fetch(list, next)
            }
        }
        // Si Telegram tarda demasiado, se enseña lo que ya haya (sin dejar la pantalla en blanco).
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!finished.get()) {
                synchronized(all) { chats.keys.toList().forEach { snapshot(it) } }
                finish()
            }
        }, 25_000)
        loadAll(TdApi.ChatListMain(), 0) {
            loadAll(TdApi.ChatListArchive(), 0) { finish() }
        }
    }

    override fun loadChat(chatId: Long, onDone: (TgChat?) -> Unit) {
        val known = chats[chatId]
        if (known != null) {
            onDone(tgChatOf(known))
            return
        }
        sendQuiet(TdApi.GetChat(chatId)) { r -> onDone(if (r is TdApi.Chat) tgChatOf(r) else null) }
    }

    override fun forwardMessage(fromChatId: Long, messageId: Long, toChatId: Long, topicId: Long, onDone: (Boolean) -> Unit) {
        val req = newRequest(
            "ForwardMessages",
            "chatId" to toChatId, "fromChatId" to fromChatId, "messageIds" to longArrayOf(messageId),
            "sendCopy" to false, "removeCaption" to false
        )
        if (req == null) {
            onDone(false)
            return
        }
        applyTopic(req, topicId)
        send(req) { r -> onDone(r is TdApi.Messages) }
    }

    override fun messageLink(chatId: Long, messageId: Long, onDone: (String?) -> Unit) {
        val req = newRequest(
            "GetMessageLink",
            "chatId" to chatId, "messageId" to messageId, "mediaTimestamp" to 0, "forAlbum" to false, "inMessageThread" to false
        )
        if (req == null) {
            onDone(null)
            return
        }
        sendQuiet(req) { r -> onDone((field(r, "link") as? String)?.ifEmpty { null }) }
    }

    override fun markRead(chatId: Long, onDone: (Boolean) -> Unit) {
        val lastId = chats[chatId]?.lastMessage?.id ?: 0L
        if (lastId == 0L) {
            onDone(false)
            return
        }
        val req = newRequest("ViewMessages", "chatId" to chatId, "messageIds" to longArrayOf(lastId), "forceRead" to true)
        if (req == null) {
            onDone(false)
            return
        }
        sendQuiet(req) { r -> onDone(r is TdApi.Ok) }
    }

    override fun leaveChat(chatId: Long, onDone: (Boolean) -> Unit) {
        send(TdApi.LeaveChat(chatId)) { r -> onDone(r is TdApi.Ok) }
    }

    override fun editMessage(chatId: Long, messageId: Long, text: String, onDone: (Boolean) -> Unit) {
        val content = TdApi.InputMessageText()
        content.text = TdApi.FormattedText(text, arrayOf<TdApi.TextEntity>())
        val req = newRequest("EditMessageText", "chatId" to chatId, "messageId" to messageId, "inputMessageContent" to content)
        if (req == null) {
            onDone(false)
            return
        }
        send(req) { r -> onDone(r is TdApi.Message) }
    }

    private val linkRegex = Regex("(?:https?://|tg://)[^\\s<>\"']+|(?<![\\w/@.])t\\.me/[^\\s<>\"']+")

    override fun loadHistory(chatId: Long, chatTitle: String, fromMessageId: Long, offset: Int, onDone: (List<TgMessage>) -> Unit) {
        // La primera petición a un chat sin caché suele devolver muy pocos mensajes (o ninguno): se repite un poco.
        val acc = ArrayList<TdApi.Message>()
        fun attempt(n: Int, from: Long, off: Int) {
            send(TdApi.GetChatHistory(chatId, from, off, 50, false)) { result ->
                var gotAny = false
                if (result is TdApi.Messages) {
                    for (m in result.messages) {
                        gotAny = true
                        if (acc.none { it.id == m.id }) acc.add(m)
                    }
                }
                val retry = acc.size < 20 && n < 3 && (gotAny || (acc.isEmpty() && n < 2 && fromMessageId == 0L))
                if (retry) {
                    val next = acc.lastOrNull()?.id ?: from
                    val nextOff = if (acc.isEmpty()) off else 0
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ attempt(n + 1, next, nextOff) }, 400)
                } else {
                    onDone(acc.mapNotNull { toMessage(it, chatTitle) })
                }
            }
        }
        attempt(0, fromMessageId, offset)
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
        val linkSpans = ArrayList<TgLinkSpan>()
        fmt?.entities?.forEach { e ->
            val t = e.type
            if (t is TdApi.TextEntityTypeTextUrl && e.length > 0) linkSpans.add(TgLinkSpan(e.offset, e.offset + e.length, t.url))
        }
        if (kind == "none" && text.isEmpty()) return null
        val senderName = when (val sid = m.senderId) {
            is TdApi.MessageSenderUser -> userNames[sid.userId] ?: ""
            is TdApi.MessageSenderChat -> chats[sid.chatId]?.title ?: ""
            else -> ""
        }
        val buttons = ArrayList<TgButton>()
        when (val rm = m.replyMarkup) {
            is TdApi.ReplyMarkupInlineKeyboard -> rm.rows.forEachIndexed { ri, row -> for (b in row) {
                when (val bt = b.type) {
                    is TdApi.InlineKeyboardButtonTypeCallback ->
                        buttons.add(TgButton(b.text, "cb", android.util.Base64.encodeToString(bt.data, android.util.Base64.NO_WRAP), ri))
                    is TdApi.InlineKeyboardButtonTypeUrl -> buttons.add(TgButton(b.text, "url", bt.url, ri))
                    else -> buttons.add(TgButton(b.text, "na", "", ri))
                }
            } }
            is TdApi.ReplyMarkupShowKeyboard -> rm.rows.forEachIndexed { ri, row -> for (b in row) buttons.add(TgButton(b.text, "txt", b.text, ri)) }
            else -> {}
        }
        val chatKind = chats[m.chatId]?.let { kindOf(it) } ?: "private"
        return TgMessage(
            m.chatId, chatTitle, chatKind, m.id, m.date.toLong(), text, kind,
            file?.id?.toString(), name?.ifEmpty { null }, mime?.ifEmpty { null },
            file?.let { if (it.size > 0) it.size else it.expectedSize } ?: 0L, links, buttons,
            m.isOutgoing, senderName, thumb?.id ?: 0, linkSpans
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
        val remaining = java.util.concurrent.atomic.AtomicInteger(3)
        fun finish() {
            if (remaining.decrementAndGet() != 0) return
            val missing = ids.filter { chats[it] == null }
            val finalize = {
                onDone(ids.mapNotNull { id -> chats[id]?.let { tgChatOf(it) } })
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
        val onServer = newRequest("SearchChatsOnServer", "query" to query, "limit" to 30)
        if (onServer == null) {
            finish()
        } else {
            sendQuiet(onServer) { r ->
                if (r is TdApi.Chats) synchronized(ids) { r.chatIds.forEach { ids.add(it) } }
                finish()
            }
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

    /** Última petición de descarga por archivo: [inicio, fin, instante]. Evita reiniciar la descarga de TDLib en cada trozo. */
    private val lastRequest = java.util.concurrent.ConcurrentHashMap<Int, LongArray>()

    override fun readBytes(fileId: Int, pos: Long, maxLen: Int, cancelled: () -> Boolean): ByteArray? {
        val deadline = System.currentTimeMillis() + 40_000
        var last: TdApi.File? = null
        while (System.currentTimeMillis() < deadline && !cancelled()) {
            val f = getFileBlocking(fileId)
            if (f == null) {
                // TDLib ocupado: no cortar la conexión, reintentar.
                try { Thread.sleep(200) } catch (e: InterruptedException) { return null }
                continue
            }
            last = f
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
                    val data = try {
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
                    if (data != null && data.isNotEmpty()) return data
                }
            }
            // Pedir a TDLib que descargue desde aquí solo si no hay ya una petición viva que cubra esta posición.
            val now = System.currentTimeMillis()
            val req = lastRequest[fileId]
            val covered = req != null && pos >= req[0] && pos < req[1]
            val stale = req == null || now - req[2] > 2500
            val need = !covered || (!l.isDownloadingActive && !l.isDownloadingCompleted && stale)
            if (need) {
                lastRequest[fileId] = longArrayOf(pos, pos + STREAM_WINDOW, now)
                send(TdApi.DownloadFile(fileId, 32, pos, STREAM_WINDOW, false))
            }
            try { Thread.sleep(120) } catch (e: InterruptedException) { return null }
        }
        if (!cancelled()) {
            val l = last?.local
            TgStreamServer.reportProblem(
                "pos=$pos tam=${last?.size} off=${l?.downloadOffset} pref=${l?.downloadedPrefixSize} act=${l?.isDownloadingActive} ok=${l?.canBeDownloaded}"
            )
        }
        return null
    }

    // -----------------------------------------------------------------
    // Directos (chats de vídeo). Todo por reflexión: los nombres cambian entre versiones de TDLib.
    // -----------------------------------------------------------------

    private fun newObj(name: String, vararg fields: Pair<String, Any?>): Any? {
        return try {
            val cls = Class.forName("org.drinkless.tdlib.TdApi\$" + name)
            val o = cls.getDeclaredConstructor().newInstance()
            for ((f, v) in fields) {
                try {
                    cls.getField(f).set(o, v)
                } catch (e: Exception) { }
            }
            o
        } catch (e: Throwable) {
            null
        }
    }

    private fun groupCallIdOf(chatId: Long): Int {
        val chat = chats[chatId] ?: return 0
        val vc = field(chat, "videoChat", "voiceChat") ?: return 0
        return (field(vc, "groupCallId") as? Number)?.toInt() ?: 0
    }

    /** Prueba varias peticiones (según versión de TDLib) hasta que una dé resultado que no sea error. */
    private fun tryRequests(requests: List<Pair<String, TdApi.Function<*>?>>, onDone: (TdApi.Object?, String) -> Unit) {
        val log = StringBuilder()
        fun step(i: Int) {
            if (i >= requests.size) {
                onDone(null, log.toString())
                return
            }
            val (name, req) = requests[i]
            if (req == null) {
                log.append(name).append(": no existe en esta versión\n")
                step(i + 1)
                return
            }
            sendQuiet(req) { r ->
                if (r is TdApi.Error) {
                    log.append(name).append(": ").append(r.code).append(' ').append(r.message).append('\n')
                    step(i + 1)
                } else {
                    onDone(r, log.append(name).append(": ok\n").toString())
                }
            }
        }
        step(0)
    }

    override fun liveJoin(chatId: Long, audioSource: Int, payload: String, onDone: (String?, String) -> Unit) {
        val gc = groupCallIdOf(chatId)
        if (gc == 0) {
            onDone(null, "este chat no tiene directo activo")
            return
        }
        val vc = field(chats[chatId], "videoChat", "voiceChat")
        fun go(participant: Any?) {
            val params = newObj(
                "GroupCallJoinParameters",
                "audioSourceId" to audioSource, "payload" to payload, "isMuted" to true, "isMyVideoEnabled" to false
            )
            val reqs = listOf(
                "JoinVideoChat" to newRequest(
                    "JoinVideoChat",
                    "groupCallId" to gc, "participantId" to (participant ?: return onDone(null, "sin participante")),
                    "joinParameters" to (params ?: return onDone(null, "sin GroupCallJoinParameters")), "inviteHash" to ""
                ),
                "JoinGroupCall" to newRequest(
                    "JoinGroupCall",
                    "groupCallId" to gc, "participantId" to participant, "audioSourceId" to audioSource,
                    "payload" to payload, "isMuted" to true, "isMyVideoEnabled" to false, "inviteHash" to ""
                )
            )
            tryRequests(reqs) { r, log ->
                val text = field(r, "text") as? String
                onDone(text, log + (if (r != null && text == null) "respuesta sin texto: ${r.javaClass.simpleName}\n" else ""))
            }
        }
        val existing = field(vc, "defaultParticipantId")
        if (existing != null) {
            go(existing)
        } else {
            sendQuiet(TdApi.GetMe()) { me ->
                val uid = (field(me, "id") as? Number)?.toLong() ?: 0L
                go(newObj("MessageSenderUser", "userId" to uid))
            }
        }
    }

    override fun liveStreams(chatId: Long, onDone: (LongArray?, String) -> Unit) {
        val gc = groupCallIdOf(chatId)
        val reqs = listOf(
            "GetVideoChatStreams" to newRequest("GetVideoChatStreams", "groupCallId" to gc),
            "GetGroupCallStreams" to newRequest("GetGroupCallStreams", "groupCallId" to gc)
        )
        tryRequests(reqs) { r, log ->
            val arr = field(r, "streams") as? Array<*>
            val first = arr?.firstOrNull()
            if (first == null) {
                onDone(null, log + "sin streams")
            } else {
                val t = (field(first, "timeOffset") as? Number)?.toLong() ?: 0L
                val sc = (field(first, "scale") as? Number)?.toLong() ?: 0L
                val ch = (field(first, "channelId") as? Number)?.toLong() ?: 0L
                onDone(longArrayOf(t, sc, ch), log)
            }
        }
    }

    override fun liveSegment(chatId: Long, timeOffset: Long, scale: Int, channelId: Int, quality: Int, onDone: (ByteArray?, String) -> Unit) {
        val gc = groupCallIdOf(chatId)
        val q = newObj(
            when (quality) {
                2 -> "GroupCallVideoQualityMedium"
                3 -> "GroupCallVideoQualityFull"
                else -> "GroupCallVideoQualityThumbnail"
            }
        )
        val reqs = listOf(
            "GetVideoChatStreamSegment" to newRequest(
                "GetVideoChatStreamSegment",
                "groupCallId" to gc, "timeOffset" to timeOffset, "scale" to scale, "channelId" to channelId, "videoQuality" to (q ?: return onDone(null, "sin calidad"))
            ),
            "GetGroupCallStreamSegment" to newRequest(
                "GetGroupCallStreamSegment",
                "groupCallId" to gc, "timeOffset" to timeOffset, "scale" to scale, "channelId" to channelId, "videoQuality" to q
            )
        )
        tryRequests(reqs) { r, log ->
            val data = field(r, "data") as? ByteArray
            onDone(data, if (data == null) log else "")
        }
    }

    override fun loadFolders(onDone: (List<TgFolder>) -> Unit) {
        // Telegram manda las carpetas al iniciar sesión: si aún no han llegado, se espera un poco.
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var tries = 0
        lateinit var check: Runnable
        check = Runnable {
            if (folders.isNotEmpty() || tries >= 8) onDone(folders) else { tries++; h.postDelayed(check, 600) }
        }
        check.run()
    }

    override fun loadFolderChats(folderId: Int, onDone: (List<TgChat>) -> Unit) {
        val c = client
        if (c == null) {
            onDone(emptyList())
            return
        }
        val list = TdApi.ChatListFolder(folderId)
        val all = java.util.LinkedHashMap<Long, TgChat>()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() {
            if (finished.compareAndSet(false, true)) onDone(synchronized(all) { all.values.toList() })
        }
        fun fetch() {
            c.send(TdApi.GetChats(list, 1000)) { r ->
                if (r is TdApi.Chats) synchronized(all) { r.chatIds.forEach { id -> chats[id]?.let { all[id] = tgChatOf(it) } } }
                finish()
            }
        }
        fun loadAll(round: Int) {
            if (round >= 20) {
                fetch()
                return
            }
            c.send(TdApi.LoadChats(list, 100)) { r -> if (r is TdApi.Ok) loadAll(round + 1) else fetch() }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (!finished.get()) { fetch() } }, 20_000)
        loadAll(0)
    }

    override fun liveLeave(chatId: Long) {
        val gc = groupCallIdOf(chatId)
        if (gc == 0) return
        val reqs = listOf(
            "LeaveVideoChat" to newRequest("LeaveVideoChat", "groupCallId" to gc),
            "LeaveGroupCall" to newRequest("LeaveGroupCall", "groupCallId" to gc)
        )
        tryRequests(reqs) { _, _ -> }
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
