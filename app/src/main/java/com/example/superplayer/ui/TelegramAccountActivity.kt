package com.example.superplayer.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import coil.load
import coil.transform.CircleCropTransformation
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.superplayer.R
import android.widget.TextView
import com.example.superplayer.data.MovieInfo
import com.example.superplayer.data.PlaylistRepository
import com.example.superplayer.databinding.ActivityTelegramBinding
import com.example.superplayer.databinding.ItemTelegramBinding
import com.example.superplayer.databinding.ItemTgBubbleBinding
import com.example.superplayer.databinding.ItemTgLoadmoreBinding
import com.example.superplayer.databinding.ItemTgMediaBinding
import com.example.superplayer.model.Stream
import com.example.superplayer.player.PlayerActivity
import com.example.superplayer.telegram.TelegramAccountFactory
import com.example.superplayer.telegram.TelegramAccountPrefs
import com.example.superplayer.telegram.TgAccountBackend
import com.example.superplayer.telegram.TgAuthState
import com.example.superplayer.telegram.TgStreamServer
import com.example.superplayer.telegram.TgChat
import com.example.superplayer.telegram.TgMessage
import com.example.superplayer.telegram.TgTopic
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Telegram con tu cuenta (TDLib): inicio de sesión con número, código y
 * contraseña de dos pasos si la tienes; luego tus canales y grupos con sus
 * vídeos, audios, listas y enlaces. Los archivos se descargan al móvil antes
 * de reproducirlos.
 */
class TelegramAccountActivity : AppCompatActivity() {

    private class Row(
        val title: String,
        val subtitle: String,
        val star: Boolean? = null,
        val onStar: (() -> Unit)? = null,
        val onLong: (() -> Unit)? = null,
        val thumbFileId: Int = 0,
        val circle: Boolean = false,
        val onClick: () -> Unit
    )

    /** Una miniatura de la galería: foto, vídeo, lista o enlace de Telegram. */
    private class MediaItem(
        val key: String = "",
        val caption: String,
        val meta: String = "",
        val thumbFileId: Int = 0,
        val iconRes: Int = 0,
        val badge: String = "",
        val link: String? = null,
        val loadMore: Boolean = false,
        val messageId: Long = 0L,
        /** Título limpio para buscar la película en TMDb (vacío = no es una película). */
        val query: String = "",
        /** Todas las versiones del mismo vídeo (mp4, mkv…); vacío si no es un vídeo. */
        val options: List<TgMessage> = emptyList(),
        /** true si la miniatura es una imagen del chat (esa gana sobre la carátula de TMDb). */
        val pairedImage: Boolean = false,
        /** Fondo de la miniatura cuando no hay imagen (listas y enlaces). */
        val tileRes: Int = 0,
        val onLong: (() -> Unit)? = null,
        val onClick: () -> Unit
    )

    /** Un paso atrás al saltar por un enlace a otro chat: dónde estabas. */
    private class NavEntry(val chat: TgChat, val topic: TgTopic?, val anchor: Long)

    private val navStack = ArrayList<NavEntry>()

    private lateinit var binding: ActivityTelegramBinding
    private lateinit var adapter: RowAdapter
    private lateinit var chatAdapter: ChatAdapter
    private var kbHidden = false
    private lateinit var backCallback: OnBackPressedCallback
    private var backend: TgAccountBackend? = null
    private var state = TgAuthState.STARTING
    private var chats: List<TgChat> = emptyList()
    private var currentChat: TgChat? = null
    private val messages = ArrayList<TgMessage>()
    private var lastBatchFull = false
    private var downloadDialog: AlertDialog? = null
    private var searchResults: List<TgMessage>? = null
    private var searchQuery = ""
    private var searching = false
    /** Búsqueda global: 0 = por nombre (chats, canales, bots), 1 = en mensajes. */
    private var searchMode = 0
    private var searchChatResults: List<TgChat> = emptyList()
    private var replyTo: TgMessage? = null
    private var scrollToEnd = true
    private var searchItem: MenuItem? = null
    private var suppressExit = false
    private var pendingScroll = -1
    private var currentTopic: TgTopic? = null
    private var showingTopics = false
    private var initialFrom = 0L
    private var jumpTarget = 0L
    private val thumbPaths = HashMap<Int, String>()
    private val thumbRequested = HashSet<Int>()
    private lateinit var mediaAdapter: MediaAdapter
    /** true = galería de fotos, vídeos, listas y enlaces (sin mensajes de texto); false = todos los mensajes. */
    private var mediaMode = true
    private var usingGrid = false
    private var autoLoads = 0
    private val linkChats = HashMap<String, TgChat>()
    private val linkRequested = HashSet<String>()
    private var searchView: SearchView? = null
    private var menuRef: Menu? = null
    private val searchDebounce = Runnable { runSearchNow() }
    private val attachLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendAttachment(uri)
    }
    /** Pestaña de la lista de chats: 0 favoritos, 1 todos, 2 canales, 3 grupos, 4 bots, 5 personas. */
    private var chatFilter = 1
    private val VIDEO_TAB = 7
    /** Pestaña Vídeos: null = lista de carpetas, "" = favoritos, otro = carpeta propia. */
    private var videoFolder: String? = null
    private val reloadRunnable = Runnable { refreshNow() }

    /** Vuelve a pedir lo que se está viendo: chats, temas o mensajes (también lo usa el botón de actualizar). */
    private fun refreshNow() {
        if (searching || isFinishing || isDestroyed || state != TgAuthState.READY) return
        val chat = currentChat
        when {
            chat == null -> loadChatList()
            showingTopics -> showTopics(chat)
            else -> refreshLatest(chat)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTelegramBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applySystemBarInsets(top = binding.toolbar, bottom = binding.recyclerView)
        title = getString(R.string.tgacc_title)

        // Abierto desde el widget con un vídeo favorito: se reproduce en cuanto la sesión esté lista.
        val playChat = intent.getLongExtra(EXTRA_PLAY_CHAT, 0L)
        val playMsg = intent.getLongExtra(EXTRA_PLAY_MSG, 0L)
        if (playChat != 0L) {
            pendingPlay = TelegramAccountPrefs.videoFavorites(this)
                .firstOrNull { it.optLong("c") == playChat && it.optLong("m") == playMsg }
                ?.let { TgMessage.fromJson(it) }
        }
        adapter = RowAdapter()
        chatAdapter = ChatAdapter()
        mediaAdapter = MediaAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.emptyView.setOnClickListener { promptForState() }
        setupTabs()

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = goBack()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        val basePad = binding.inputBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.inputBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePad + bars.bottom)
            insets
        }
        binding.tgSend.setOnClickListener { sendTyped() }
        binding.tgAttach.setOnClickListener { attachLauncher.launch("*/*") }
        binding.tgKbdToggle.setOnClickListener {
            kbHidden = !kbHidden
            updateKeyboard()
        }
        binding.tgReplyLabel.setOnClickListener { setReply(null) }
        setupSearchTabs()

        val b = TelegramAccountFactory.create(this)
        backend = b
        if (b == null) {
            showMessage(getString(R.string.tgacc_unavailable))
            return
        }
        if (!TelegramAccountPrefs.hasApi(this)) {
            showMessage(getString(R.string.tgacc_need_api))
            showApiDialog()
        } else {
            startBackend()
        }
    }

    override fun onDestroy() {
        // Al salir de Telegram se borra lo descargado (la caché de Telegram crece muy rápido).
        if (isFinishing && state == TgAuthState.READY) backend?.cleanCache()
        super.onDestroy()
    }

    // -----------------------------------------------------------------
    // Navegación con mando (TV): el foco no se pierde al refrescar y se ve dónde está
    // -----------------------------------------------------------------

    private val isTvDevice by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    private var returnToChatId = 0L
    private var pendingPlay: TgMessage? = null

    companion object {
        const val EXTRA_PLAY_CHAT = "play_chat"
        const val EXTRA_PLAY_MSG = "play_msg"
    }

    /** Cambia el contenido de la lista y devuelve el foco a la misma posición (si no, el mando salta arriba del todo). */
    private fun keepFocus(change: () -> Unit) {
        val rv = binding.recyclerView
        val focused = rv.focusedChild
        val pos = if (focused != null) rv.getChildAdapterPosition(focused) else RecyclerView.NO_POSITION
        change()
        if (pos == RecyclerView.NO_POSITION) return
        rv.post {
            val count = rv.adapter?.itemCount ?: 0
            if (count == 0) return@post
            val target = pos.coerceAtMost(count - 1)
            val holder = rv.findViewHolderForAdapterPosition(target)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else {
                rv.scrollToPosition(target)
                rv.post { rv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
            }
        }
    }

    /** En TV, si tras pintar la lista no hay nada con foco, se lo da al primer (o último) elemento. */
    private fun focusItemIfTv(last: Boolean = false) {
        if (!isTvDevice) return
        val rv = binding.recyclerView
        rv.post {
            if (currentFocus != null || rv.childCount == 0) return@post
            val child = if (last) rv.getChildAt(rv.childCount - 1) else rv.getChildAt(0)
            child?.requestFocus()
        }
    }

    private fun isAtBottom(): Boolean {
        val lm = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return false
        val count = binding.recyclerView.adapter?.itemCount ?: 0
        return count == 0 || lm.findLastCompletelyVisibleItemPosition() >= count - 2
    }

    /** Pide solo los mensajes más recientes y los mezcla con los ya cargados: la lista no salta ni pierde lo que ya bajaste. */
    private fun refreshLatest(chat: TgChat) {
        val topic = currentTopic
        if (messages.isEmpty()) {
            scrollToEnd = true
            autoLoads = 0
            loadMore()
            return
        }
        val atEnd = !mediaMode && isAtBottom()
        val onBatch: (List<TgMessage>) -> Unit = { batch ->
            runOnUiThread {
                val stale = isFinishing || isDestroyed || currentChat?.id != chat.id || currentTopic?.id != topic?.id ||
                    searching || showingTopics
                if (!stale && batch.isNotEmpty()) {
                    val oldest = batch.last().messageId
                    val kept = messages.filter { it.messageId < oldest }
                    messages.clear()
                    messages.addAll(batch)
                    messages.addAll(kept)
                    scrollToEnd = atEnd
                    renderMessages()
                }
            }
        }
        if (topic != null) {
            backend?.loadTopicHistory(chat.id, chat.title, topic.id, 0L, onBatch)
        } else {
            backend?.loadHistory(chat.id, chat.title, 0L, 0, onBatch)
        }
    }

    private fun useList() {
        if (!usingGrid && binding.recyclerView.adapter === adapter) return
        usingGrid = false
        binding.recyclerView.background = null
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    /** Conversación como burbujas, igual que en Telegram. */
    private fun useChat() {
        if (!usingGrid && binding.recyclerView.adapter === chatAdapter) return
        usingGrid = false
        binding.recyclerView.setBackgroundResource(R.color.tg_chat_bg)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = chatAdapter
    }

    private fun useGrid() {
        if (usingGrid && binding.recyclerView.adapter === mediaAdapter) return
        usingGrid = true
        binding.recyclerView.background = null
        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val span = (dp / 170f).toInt().coerceIn(2, 6)
        val lm = GridLayoutManager(this, span)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (mediaAdapter.isFull(position)) span else 1
        }
        binding.recyclerView.layoutManager = lm
        binding.recyclerView.adapter = mediaAdapter
    }

    private fun showMessage(text: String) {
        useList()
        adapter.submit(emptyList())
        binding.emptyView.text = text
        binding.emptyView.visibility = View.VISIBLE
    }

    private fun startBackend() {
        backend?.start(object : TgAccountBackend.Listener {
            override fun onState(state: TgAuthState) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    this@TelegramAccountActivity.state = state
                    promptForState()
                }
            }

            override fun onNewMessage(chatId: Long) {
                runOnUiThread {
                    if (isFinishing || isDestroyed || searching) return@runOnUiThread
                    val open = currentChat
                    if (open == null) {
                        scheduleReload(2000)
                    } else if (open.id == chatId) {
                        scheduleReload(600)
                    }
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this@TelegramAccountActivity, message, Toast.LENGTH_LONG).show()
                    if (state == TgAuthState.WAIT_PHONE || state == TgAuthState.WAIT_CODE || state == TgAuthState.WAIT_PASSWORD) {
                        promptForState()
                    }
                }
            }
        })
    }

    private fun promptForState() {
        val b = backend ?: return
        when (state) {
            TgAuthState.STARTING -> showMessage(getString(R.string.tgacc_connecting))
            TgAuthState.WAIT_PHONE -> {
                showMessage(getString(R.string.tgacc_tap_to_login))
                askText(R.string.tgacc_phone_title, R.string.tgacc_phone_hint, InputType.TYPE_CLASS_PHONE) { b.sendPhone(it) }
            }
            TgAuthState.WAIT_CODE -> {
                showMessage(getString(R.string.tgacc_tap_to_login))
                askText(R.string.tgacc_code_title, R.string.tgacc_code_hint, InputType.TYPE_CLASS_NUMBER) { b.sendCode(it) }
            }
            TgAuthState.WAIT_PASSWORD -> {
                showMessage(getString(R.string.tgacc_tap_to_login))
                askText(
                    R.string.tgacc_password_title, R.string.tgacc_password_hint,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                ) { b.sendPassword(it) }
            }
            TgAuthState.READY -> {
                if (!cacheCleaned) {
                    cacheCleaned = true
                    b.cleanCache()
                }
                loadChatList()
                pendingPlay?.let {
                    pendingPlay = null
                    playVideo(it)
                }
            }
            TgAuthState.CLOSED -> showMessage(getString(R.string.tgacc_closed))
        }
    }

    private var openDialog: AlertDialog? = null
    private var cacheCleaned = false

    private fun askText(titleRes: Int, hintRes: Int, inputType: Int, onOk: (String) -> Unit) {
        openDialog?.dismiss()
        val input = EditText(this).apply {
            hint = getString(hintRes)
            this.inputType = inputType
            setSingleLine(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        openDialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString()
                if (text.isNotBlank()) onOk(text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showApiDialog() {
        val idInput = EditText(this).apply {
            hint = "api_id"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            val current = TelegramAccountPrefs.apiId(this@TelegramAccountActivity)
            if (current != 0) setText(current.toString())
        }
        val hashInput = EditText(this).apply {
            hint = "api_hash"
            setSingleLine(true)
            setText(TelegramAccountPrefs.apiHash(this@TelegramAccountActivity))
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(idInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(hashInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tgacc_api_title)
            .setMessage(R.string.tgacc_api_help)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val id = idInput.text.toString().trim().toIntOrNull() ?: 0
                val hash = hashInput.text.toString().trim()
                if (id != 0 && hash.isNotEmpty()) {
                    TelegramAccountPrefs.setApi(this, id, hash)
                    Toast.makeText(this, R.string.tgacc_api_saved, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_telegram_account, menu)
        menuRef = menu
        val item = menu.findItem(R.id.action_tgacc_search)
        searchItem = item
        val sv = item?.actionView as? SearchView
        searchView = sv
        sv?.maxWidth = Int.MAX_VALUE
        sv?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchQuery = query.orEmpty().trim()
                binding.root.removeCallbacks(searchDebounce)
                runSearchNow()
                sv.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText.orEmpty().trim()
                if (searching) {
                    binding.root.removeCallbacks(searchDebounce)
                    binding.root.postDelayed(searchDebounce, if (searchMode == 0 && currentChat == null) 400L else 700L)
                }
                return true
            }
        })
        item?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                if (state != TgAuthState.READY) return false
                searching = true
                enterSearchUi()
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (suppressExit) return true
                searching = false
                exitSearchUi()
                return true
            }
        })
        return true
    }

    /** Muestra/oculta los botones del menú sin reconstruirlo (reconstruirlo cerraría la búsqueda). */
    private fun updateMenuItems() {
        menuRef?.let { onPrepareOptionsMenu(it) }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val chat = currentChat
        val inChat = chat != null && !searching
        menu.findItem(R.id.action_tgacc_refresh)?.isVisible = !searching && state == TgAuthState.READY
        menu.findItem(R.id.action_tgacc_view)?.let {
            it.isVisible = inChat && !showingTopics
            it.title = getString(if (mediaMode) R.string.tgacc_view_all else R.string.tgacc_view_media)
        }
        menu.findItem(R.id.action_tgacc_fav)?.isVisible = inChat
        menu.findItem(R.id.action_tgacc_cmds)?.isVisible = inChat && chat?.kind == "bot"
        menu.findItem(R.id.action_tgacc_kbd)?.isVisible = inChat && chat?.kind == "bot"
        menu.findItem(R.id.action_tgacc_live)?.isVisible = inChat && chat?.live == true
        menu.findItem(R.id.action_tgacc_watch)?.isVisible = inChat && (chat?.live == true || chat?.kind == "channel" || chat?.kind == "group" || chat?.kind == "forum")
        menu.findItem(R.id.action_tgacc_read)?.isVisible = inChat && !showingTopics
        menu.findItem(R.id.action_tgacc_leave)?.isVisible = inChat && (chat?.kind == "channel" || chat?.kind == "group" || chat?.kind == "forum")
        menu.findItem(R.id.action_tgacc_join)?.isVisible = inChat && (chat?.kind == "channel" || chat?.kind == "group" || chat?.kind == "forum")
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                goBack()
                return true
            }
            R.id.action_tgacc_view -> {
                mediaMode = !mediaMode
                autoLoads = 0
                invalidateOptionsMenu()
                if (currentChat != null && !showingTopics && !searching) renderMessages()
                return true
            }
            R.id.action_tgacc_refresh -> {
                Toast.makeText(this, R.string.tgacc_refreshing, Toast.LENGTH_SHORT).show()
                refreshNow()
                return true
            }
            R.id.action_tgacc_fav -> {
                currentChat?.let {
                    val nowFav = TelegramAccountPrefs.toggleFavorite(this, it.id, it)
                    Toast.makeText(this, if (nowFav) R.string.tgacc_fav_added else R.string.tgacc_fav_removed, Toast.LENGTH_SHORT).show()
                }
                return true
            }
            R.id.action_tgacc_cmds -> {
                showBotCommands()
                return true
            }
            R.id.action_tgacc_kbd -> {
                showBotKeyboard()
                return true
            }
            R.id.action_tgacc_join -> {
                val chat = currentChat ?: return true
                backend?.joinChat(chat.id) { ok ->
                    runOnUiThread {
                        Toast.makeText(this, if (ok) R.string.tgacc_joined else R.string.tgacc_join_error, Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }
            R.id.action_tgacc_ntg -> {
                val text = com.example.superplayer.telegram.NtgProbe.dump()
                val tv = android.widget.TextView(this).apply {
                    this.text = text
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(dpPx(12), dpPx(8), dpPx(12), dpPx(8))
                }
                val sv = android.widget.ScrollView(this).apply { addView(tv) }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.tgacc_menu_ntg)
                    .setView(sv)
                    .setPositiveButton(android.R.string.copy) { _, _ ->
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("ntgcalls", text))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return true
            }
            R.id.action_tgacc_watch -> {
                currentChat?.let {
                    startActivity(Intent(this, LiveActivity::class.java).putExtra("chatId", it.id).putExtra("title", it.title))
                }
                return true
            }
            R.id.action_tgacc_live -> {
                currentChat?.let { openLiveInTelegram(it) }
                return true
            }
            R.id.action_tgacc_read -> {
                val chat = currentChat ?: return true
                backend?.markRead(chat.id) { ok ->
                    runOnUiThread { Toast.makeText(this, if (ok) R.string.tgacc_marked_read else R.string.tgacc_action_error, Toast.LENGTH_SHORT).show() }
                }
                return true
            }
            R.id.action_tgacc_leave -> {
                val chat = currentChat ?: return true
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.tgacc_leave_confirm, chat.title))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        backend?.leaveChat(chat.id) { ok ->
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                Toast.makeText(this, if (ok) R.string.tgacc_left else R.string.tgacc_action_error, Toast.LENGTH_SHORT).show()
                                if (ok) closeChat()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return true
            }
            R.id.action_tgacc_api -> {
                showApiDialog()
                return true
            }
            R.id.action_tgacc_logout -> {
                AlertDialog.Builder(this)
                    .setMessage(R.string.tgacc_logout_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ -> backend?.logOut() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // -----------------------------------------------------------------
    // Chats y mensajes
    // -----------------------------------------------------------------

    private fun loadChatList() {
        backend?.loadChats { list ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val favs = TelegramAccountPrefs.favoriteChats(this)
                val have = list.map { it.id }.toHashSet()
                val info = TelegramAccountPrefs.favoriteChatInfo(this)
                // Favoritos que Telegram no ha traído en la lista: se muestran con la ficha guardada y se piden aparte.
                val extra = favs.filter { it !in have }.mapNotNull { info[it] }
                for (c in list) if (c.id in favs) TelegramAccountPrefs.saveChatInfo(this, c)
                chats = list + extra
                for (id in favs) {
                    if (id in have) continue
                    backend?.loadChat(id) { c ->
                        if (c == null) return@loadChat
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            TelegramAccountPrefs.saveChatInfo(this, c)
                            chats = chats.filter { it.id != c.id } + c
                            if (!searching && currentChat == null) renderChats()
                        }
                    }
                }
                if (searching || currentChat != null) return@runOnUiThread
                renderChats()
            }
        }
    }

    private fun kindLabel(kind: String) = when (kind) {
        "channel" -> getString(R.string.tgacc_kind_channel)
        "group" -> getString(R.string.tgacc_kind_group)
        "forum" -> getString(R.string.tgacc_kind_forum)
        "bot" -> getString(R.string.tgacc_kind_bot)
        else -> getString(R.string.tgacc_kind_private)
    }

    private fun updateBack() {
        backCallback.isEnabled = searching || currentChat != null || (chatFilter == VIDEO_TAB && videoFolder != null)
    }

    private fun goBack() {
        if (searching) {
            searchItem?.collapseActionView()
        } else if (navStack.isNotEmpty()) {
            val e = navStack.removeAt(navStack.size - 1)
            openChat(e.chat, e.anchor, keepStack = true)
            val t = e.topic
            if (t != null) openTopic(e.chat, t)
        } else if (currentTopic != null) {
            currentChat?.let { showTopics(it) }
        } else if (currentChat != null) {
            closeChat()
        } else if (chatFilter == VIDEO_TAB && videoFolder != null) {
            videoFolder = null
            renderChats()
        } else {
            finish()
        }
    }

    private fun setupTabs() {
        val labels = listOf(
            R.string.tgacc_tab_fav, R.string.tgacc_tab_all, R.string.tgacc_tab_channels,
            R.string.tgacc_tab_groups, R.string.tgacc_tab_forums, R.string.tgacc_tab_bots, R.string.tgacc_tab_people,
            R.string.tgvid_tab
        )
        if (TelegramAccountPrefs.favoriteChats(this).isNotEmpty()) chatFilter = 0
        for ((i, res) in labels.withIndex()) {
            binding.tgTabs.addTab(binding.tgTabs.newTab().setText(res), i == chatFilter)
        }
        binding.tgTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                if (chatFilter != tab.position) {
                    chatFilter = tab.position
                    videoFolder = null
                    if (currentChat == null && !searching) renderChats()
                }
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    private fun scheduleReload(delayMs: Long) {
        binding.root.removeCallbacks(reloadRunnable)
        binding.root.postDelayed(reloadRunnable, delayMs)
    }

    private fun renderChats() {
        if (chatFilter != VIDEO_TAB) useList()
        title = getString(R.string.tgacc_title)
        searchResults = null
        binding.inputBar.visibility = View.GONE
        binding.tgTabs.visibility = View.VISIBLE
        binding.tgSearchTabs.visibility = View.GONE
        updateBack()
        invalidateOptionsMenu()
        if (chatFilter == VIDEO_TAB) {
            renderVideoFavs()
            return
        }
        val favs = TelegramAccountPrefs.favoriteChats(this)
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val shown = chats.filter { chat ->
            when (chatFilter) {
                0 -> chat.id in favs
                2 -> chat.kind == "channel"
                3 -> chat.kind == "group"
                4 -> chat.kind == "forum"
                5 -> chat.kind == "bot"
                6 -> chat.kind == "private"
                else -> true
            }
        }
        val rows = shown.map { chat ->
            val sub = (if (chat.live) getString(R.string.tgacc_live_badge) + " · " else "") + kindLabel(chat.kind) +
                (if (chat.unread > 0) " · " + getString(R.string.tgacc_unread, chat.unread) else "") +
                (if (chat.lastDate > 0) " · " + df.format(Date(chat.lastDate * 1000L)) else "")
            Row(chat.title, sub, chat.id in favs, {
                val nowFav = TelegramAccountPrefs.toggleFavorite(this, chat.id, chat)
                Toast.makeText(this, if (nowFav) R.string.tgacc_fav_added else R.string.tgacc_fav_removed, Toast.LENGTH_SHORT).show()
                renderChats()
            }, thumbFileId = chat.photoId, circle = true) { openChat(chat) }
        }
        adapter.submit(rows)
        val back = returnToChatId
        returnToChatId = 0L
        val backIdx = if (back != 0L) shown.indexOfFirst { it.id == back } else -1
        if (backIdx >= 0) {
            binding.recyclerView.scrollToPosition(backIdx)
            binding.recyclerView.post { binding.recyclerView.findViewHolderForAdapterPosition(backIdx)?.itemView?.requestFocus() }
        } else {
            focusItemIfTv()
        }
        binding.emptyView.text = getString(if (chatFilter == 0) R.string.tgacc_no_favs else R.string.tgacc_no_chats)
        binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openChat(chat: TgChat, messageId: Long = 0L, startParam: String = "", keepStack: Boolean = false) {
        if (!keepStack) navStack.clear()
        if (searching) {
            suppressExit = true
            searchItem?.collapseActionView()
            suppressExit = false
            searching = false
        }
        binding.tgSearchTabs.visibility = View.GONE
        currentChat = chat
        currentTopic = null
        showingTopics = false
        messages.clear()
        lastBatchFull = false
        scrollToEnd = messageId == 0L
        mediaMode = chat.kind == "channel" || chat.kind == "group" || chat.kind == "forum"
        autoLoads = 0
        jumpTarget = messageId
        initialFrom = messageId
        setReply(null)
        kbHidden = false
        updateKeyboard()
        title = chat.title
        searchResults = null
        if (startParam.isNotEmpty()) binding.tgInput.setText("/start $startParam")
        updateBack()
        invalidateOptionsMenu()
        if (chat.kind == "forum") {
            showTopics(chat)
            return
        }
        binding.inputBar.visibility = View.VISIBLE
        binding.tgTabs.visibility = View.GONE
        adapter.submit(emptyList())
        binding.emptyView.text = getString(R.string.tg_loading)
        binding.emptyView.visibility = View.VISIBLE
        loadMore()
        refreshLive()
    }

    /** Lista de temas de un supergrupo con temas (foro). */
    private fun showTopics(chat: TgChat) {
        useList()
        currentTopic = null
        showingTopics = true
        title = chat.title
        binding.inputBar.visibility = View.GONE
        binding.tgTabs.visibility = View.GONE
        binding.tgSearchTabs.visibility = View.GONE
        updateBack()
        invalidateOptionsMenu()
        adapter.submit(emptyList())
        binding.emptyView.text = getString(R.string.tg_loading)
        binding.emptyView.visibility = View.VISIBLE
        backend?.loadTopics(chat.id) { list ->
            runOnUiThread {
                if (isFinishing || isDestroyed || currentChat?.id != chat.id || !showingTopics || searching) return@runOnUiThread
                val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                val rows = list.sortedByDescending { it.lastDate }.map { t ->
                    val sub = (if (t.unread > 0) getString(R.string.tgacc_unread, t.unread) + " · " else "") +
                        (if (t.lastDate > 0) df.format(Date(t.lastDate * 1000L)) else "")
                    Row("💬 " + t.name, sub) { openTopic(chat, t) }
                }
                adapter.submit(rows)
                focusItemIfTv()
                binding.emptyView.text = getString(R.string.tgacc_topics_empty)
                binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openTopic(chat: TgChat, topic: TgTopic) {
        currentTopic = topic
        showingTopics = false
        messages.clear()
        lastBatchFull = false
        scrollToEnd = true
        initialFrom = 0L
        jumpTarget = 0L
        autoLoads = 0
        setReply(null)
        title = chat.title + " › " + topic.name
        binding.inputBar.visibility = View.VISIBLE
        updateBack()
        invalidateOptionsMenu()
        adapter.submit(emptyList())
        binding.emptyView.text = getString(R.string.tg_loading)
        binding.emptyView.visibility = View.VISIBLE
        loadMore()
    }

    /** Id del primer mensaje visible, para volver justo ahí después de saltar a otro chat. */
    private fun currentAnchor(): Long {
        val lm = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return 0L
        val pos = lm.findFirstVisibleItemPosition()
        if (pos < 0) return 0L
        if (mediaMode) return mediaAdapter.messageIdAt(pos)
        val idx = pos - (if (lastBatchFull) 1 else 0)
        val list = messages.reversed()
        return list.getOrNull(idx)?.messageId ?: 0L
    }

    /** Aviso rojo «Directo en vivo · Unirme» arriba del chat, como en Telegram. */
    private fun updateLiveBanner() {
        val chat = currentChat
        val show = chat != null && !searching && chat.live
        val wasVisible = binding.tgLiveBanner.visibility == View.VISIBLE
        binding.tgLiveBanner.visibility = if (show) View.VISIBLE else View.GONE
        // En TV (mando) el foco va directo al aviso para poder unirse con OK.
        if (show && !wasVisible) binding.tgLiveBanner.post { binding.tgLiveBanner.requestFocus() }
        if (show) {
            binding.tgLiveBanner.setOnClickListener {
                currentChat?.let {
                    startActivity(Intent(this, LiveActivity::class.java).putExtra("chatId", it.id).putExtra("title", it.title))
                }
            }
        }
    }

    /** Vuelve a consultar si el chat abierto tiene un directo activo. */
    private fun refreshLive() {
        val chat = currentChat ?: return
        backend?.loadChat(chat.id) { c ->
            runOnUiThread {
                val cur = currentChat
                if (c != null && cur != null && cur.id == chat.id && c.live != cur.live) {
                    currentChat = cur.copy(live = c.live)
                    updateLiveBanner()
                    invalidateOptionsMenu()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLive()
    }

    private fun closeChat() {
        navStack.clear()
        returnToChatId = currentChat?.id ?: 0L
        currentChat = null
        updateLiveBanner()
        currentTopic = null
        showingTopics = false
        binding.root.removeCallbacks(reloadRunnable)
        renderChats()
        loadChatList()
    }

    private fun loadMore() {
        val chat = currentChat ?: return
        val topic = currentTopic
        val firstLoad = messages.isEmpty()
        val from = messages.lastOrNull()?.messageId ?: initialFrom
        val offset = if (firstLoad && initialFrom != 0L) -20 else 0
        val onBatch: (List<TgMessage>) -> Unit = { batch ->
            runOnUiThread {
                if (!(isFinishing || isDestroyed || currentChat?.id != chat.id || currentTopic?.id != topic?.id)) {
                    if (!firstLoad) pendingScroll = batch.size
                    initialFrom = 0L
                    messages.addAll(batch)
                    lastBatchFull = batch.size >= 5
                    if (!searching) renderMessages()
                }
            }
        }
        if (topic != null) {
            backend?.loadTopicHistory(chat.id, chat.title, topic.id, from, onBatch)
        } else {
            backend?.loadHistory(chat.id, chat.title, from, offset, onBatch)
        }
    }

    private fun renderMessages() {
        binding.inputBar.visibility = View.VISIBLE
        updateKeyboard()
        binding.tgSearchTabs.visibility = View.GONE
        updateBack()
        if (mediaMode) {
            renderMedia()
            return
        }
        useChat()
        // Como en Telegram: lo más antiguo arriba y lo más nuevo abajo.
        val ordered = messages.reversed()
        keepFocus { chatAdapter.submit(ordered, lastBatchFull) }
        binding.emptyView.text = getString(R.string.tg_empty_chat)
        binding.emptyView.visibility = if (ordered.isEmpty() && !lastBatchFull) View.VISIBLE else View.GONE
        val last = ordered.size + (if (lastBatchFull) 1 else 0) - 1
        val jump = jumpTarget
        if (jump != 0L && last >= 0) {
            val idx = messages.reversed().indexOfFirst { it.messageId == jump }
            val target = if (idx >= 0) idx + (if (lastBatchFull) 1 else 0) else last
            binding.recyclerView.post { binding.recyclerView.scrollToPosition(target) }
            jumpTarget = 0L
            scrollToEnd = false
        } else if (scrollToEnd && last >= 0) {
            binding.recyclerView.post { binding.recyclerView.scrollToPosition(last) }
        } else if (pendingScroll > 0 && last >= 0) {
            val target = pendingScroll.coerceAtMost(last)
            binding.recyclerView.post { binding.recyclerView.scrollToPosition(target) }
        }
        scrollToEnd = false
        pendingScroll = -1
        focusItemIfTv(last = true)
    }

    private val reNumPrefix = Regex("^\\s*\\d{1,5}\\s*[-.)]\\s*")
    private val reExtSuffix = Regex("(?i)[\\s.-]+(mkv|mp4|avi|mov|m4v|webm|ts|jpg|jpeg|png|webp)\\s*$")
    private val reUrlSuffix = Regex("(?i)\\s*\\burl\\s*=\\s*\\d+\\s*$")
    private val reYearOpen = Regex("\\(((?:19|20)\\d{2})[^)]*$")

    /** "253-Peli (2025-mkv" y "Peli (2025-" -> "Peli (2025)": el mismo nombre para el vídeo y su imagen. */
    private fun cleanName(raw: String): String {
        var t = raw.trim().replace(reNumPrefix, "")
        t = t.replace(reUrlSuffix, "")
        t = t.replace(reExtSuffix, "")
        t = t.replace(reYearOpen) { "(" + it.groupValues[1] + ")" }
        return t.trim(' ', '-', '_', '.')
    }

    private val reEpisode = Regex("(?i)\\bS(\\d{1,2})\\s*E(\\d{1,3})\\b|\\b(\\d{1,2})\\s*[x×]\\s*(\\d{1,3})\\b|\\bT(?:emporada)?\\s*(\\d{1,2})\\s*[ .-]*(?:E|Cap(?:itulo|ítulo)?\\.?)\\s*(\\d{1,3})\\b")
    private val reYearWord = Regex("\\b(?:19|20)\\d{2}\\b")

    /** Capítulo de una serie: temporada, número y nombre de la serie ("Serie 1x02", "Serie S01E02", "Serie T1 E02"). */
    private class EpInfo(val season: Int, val episode: Int, val series: String)

    private fun parseEpisode(raw: String): EpInfo? {
        var t = raw.trim().replace(reNumPrefix, "").replace(reExtSuffix, "")
        val m = reEpisode.find(t) ?: return null
        val g = m.groupValues
        val season = (g[1].ifEmpty { g[3].ifEmpty { g[5] } }).toIntOrNull() ?: return null
        val episode = (g[2].ifEmpty { g[4].ifEmpty { g[6] } }).toIntOrNull() ?: return null
        var series = t.substring(0, m.range.first).trim(' ', '-', '_', '.', ':', '|')
        if (series.length < 2) series = t.substring(m.range.last + 1).trim(' ', '-', '_', '.', ':', '|')
        series = cleanName(series)
        if (series.length < 2) return null
        return EpInfo(season, episode, series)
    }

    private fun noYear(key: String) = key.replace(reYearWord, " ").replace(Regex("\\s+"), " ").trim()

    private fun nameKey(raw: String): String =
        java.text.Normalizer.normalize(cleanName(raw).lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun isImageMsg(m: TgMessage) = m.kind == "photo" || (m.kind == "document" && (m.mime ?: "").startsWith("image/"))

    /** Un .m3u subido como documento suele llegar con tipo "audio/…": hay que mirarlo antes de darlo por audio/vídeo. */
    private fun isListMsg(m: TgMessage) = m.kind == "document" && looksLikePlaylist(m)

    private fun isVideoMsg(m: TgMessage) =
        !isListMsg(m) && (m.kind == "video" || m.kind == "linkvideo" || m.kind == "animation" || (m.kind == "document" && looksLikeMedia(m)))

    private fun isMovieFile(m: TgMessage): Boolean {
        if (m.kind == "animation") return false
        if (m.kind == "linkvideo") return true
        val mime = (m.mime ?: "").lowercase()
        val name = (m.fileName ?: "").lowercase()
        return m.kind == "video" || mime.startsWith("video/") ||
            listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".ts", ".m4v").any { name.endsWith(it) }
    }

    /** Primera línea con texto (sin enlaces) o, si no hay, el nombre del archivo. */
    private fun titleOf(m: TgMessage): String =
        m.text.lineSequence().map { it.replace(reUrlInText, "").trim() }.firstOrNull { it.isNotEmpty() } ?: m.fileName.orEmpty()

    /** Galería: solo fotos, vídeos, listas (archivos y enlaces) y enlaces de Telegram, en miniaturas con su texto debajo. */
    private fun renderMedia(auto: Boolean = true) {
        useGrid()
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val items = ArrayList<MediaItem>()
        val seenLinks = HashSet<String>()
        val source = withLinkVideos(messages)
        // Imagen y vídeo con el mismo nombre: la imagen es la miniatura del vídeo y no se repite aparte.
        val imageByName = HashMap<String, TgMessage>()
        for (m in source) {
            if (!isImageMsg(m)) continue
            val k = nameKey(titleOf(m))
            if (k.length >= 2) imageByName.putIfAbsent(k, m)
        }
        // Varias versiones del mismo vídeo (mismo nombre) o todos los capítulos de una serie: una sola miniatura.
        val imageNoYear = HashMap<String, TgMessage>()
        for ((k, img) in imageByName) imageNoYear.putIfAbsent(noYear(k), img)
        fun findImage(k: String): TgMessage? = imageByName[k] ?: imageNoYear[noYear(k)]?.takeIf { noYear(k).length >= 2 }
        val groups = LinkedHashMap<String, MutableList<TgMessage>>()
        val groupName = HashMap<String, String>() // clave del grupo -> nombre para buscar en TMDb
        for (m in source) {
            if (!isVideoMsg(m)) continue
            val ep = parseEpisode(titleOf(m))
            val base = if (ep != null) nameKey(ep.series) else nameKey(titleOf(m))
            val k = if (base.length >= 2) (if (ep != null) "s:$base" else base) else "id${m.messageId}"
            groups.getOrPut(k) { ArrayList() }.add(m)
            if (ep != null) groupName.putIfAbsent(k, ep.series)
        }
        val pairedByLeader = HashMap<Long, TgMessage>() // id del vídeo principal -> su imagen
        val usedImages = HashSet<Long>()
        for ((k, list) in groups) {
            val img = findImage(k.removePrefix("s:")) ?: continue
            if (usedImages.add(img.messageId)) pairedByLeader[list[0].messageId] = img
        }
        // Listas (.m3u…) con su imagen del mismo nombre.
        val listImage = HashMap<Long, TgMessage>()
        for (m in source) {
            if (!isListMsg(m)) continue
            val img = imageByName[nameKey(titleOf(m))] ?: continue
            if (usedImages.add(img.messageId)) listImage[m.messageId] = img
        }
        val groupOf = HashMap<Long, String>()
        for ((k, list) in groups) for (v in list) groupOf[v.messageId] = k
        for (m in source) { // de más nuevo a más antiguo
            val caption = m.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val date = df.format(Date(m.date * 1000L))
            val isImage = isImageMsg(m)
            val isVideo = isVideoMsg(m)
            if (isImage) {
                if (m.messageId in usedImages) continue
                items.add(MediaItem(key = "${m.messageId}:img", caption = caption.ifEmpty { m.fileName.orEmpty() }, meta = date, thumbFileId = m.thumbId,
                    messageId = m.messageId, onLong = { onMessageLong(m) }) { onMessageClick(m) })
            } else if (isVideo) {
                val k = groupOf[m.messageId]
                val list = if (k != null) groups[k].orEmpty() else listOf(m)
                if (list.isNotEmpty() && list[0].messageId != m.messageId) continue // ya salió con su grupo
                val isSeries = k != null && k.startsWith("s:")
                val options = if (isSeries) {
                    list.sortedWith(compareBy({ parseEpisode(titleOf(it))?.season ?: 0 }, { parseEpisode(titleOf(it))?.episode ?: 0 }, { it.messageId }))
                } else list.sortedBy { it.messageId }
                val img = pairedByLeader[m.messageId]
                val thumb = img?.thumbId?.takeIf { it != 0 } ?: m.thumbId
                val q = if (options.any { isMovieFile(it) }) (k?.let { groupName[it] } ?: cleanName(titleOf(m))).takeIf { it.length >= 2 }.orEmpty() else ""
                val fav = options.any { TelegramAccountPrefs.isVideoFavorite(this, it.chatId, it.messageId) }
                val badge = (if (options.size > 1) "▶ ×${options.size}" else "▶") + (if (fav) " ★" else "")
                items.add(MediaItem(key = "${m.messageId}:vid", caption = (if (q.isNotEmpty()) q else caption.ifEmpty { m.fileName.orEmpty() }), meta = date, thumbFileId = thumb,
                    badge = badge, messageId = m.messageId, query = q, options = options, pairedImage = img != null,
                    onLong = { toggleVideoFav(options, q, thumb) }) { onVideoClick(options, q, thumb) })
            } else if (isListMsg(m)) {
                val img = listImage[m.messageId]
                val name = m.fileName ?: caption
                items.add(MediaItem(key = "${m.messageId}:file", caption = cleanName(caption.ifEmpty { name }), meta = date, thumbFileId = img?.thumbId ?: 0,
                    iconRes = R.drawable.ic_tg_list, tileRes = R.drawable.bg_tg_tile_list,
                    badge = getString(R.string.tgacc_badge_list), messageId = m.messageId,
                    onLong = { onMessageLong(m) }) { onMessageClick(m) })
            }
            for (link in m.links) {
                if (!seenLinks.add(link)) continue
                if (isTgMessageLink(link)) continue // enlace a un vídeo: ya sale como vídeo
                val shortLink = link.removePrefix("https://").removePrefix("http://")
                if (isTgLink(link)) {
                    items.add(MediaItem(key = "${m.messageId}:tg:$link", caption = shortLink, meta = date, iconRes = R.drawable.ic_tg_link,
                        tileRes = R.drawable.bg_tg_tile_link,
                        badge = getString(R.string.tgacc_badge_link), link = link, messageId = m.messageId,
                        onLong = { onMessageLong(m) }) { openTgLink(link) })
                } else if (TelegramImport.looksLikeListUrl(link)) {
                    items.add(MediaItem(key = "${m.messageId}:l:$link", caption = shortLink, meta = date, iconRes = R.drawable.ic_tg_list,
                        tileRes = R.drawable.bg_tg_tile_list,
                        badge = getString(R.string.tgacc_badge_list), messageId = m.messageId,
                        onLong = { onMessageLong(m) }) { onMessageClick(m) })
                }
            }
        }
        val real = items.size
        if (lastBatchFull) items.add(MediaItem(key = "more", caption = getString(R.string.tgacc_load_more), loadMore = true) { loadMore() })
        mediaAdapter.submit(items)
        binding.emptyView.text = getString(R.string.tgacc_media_empty)
        binding.emptyView.visibility = if (real == 0 && !lastBatchFull) View.VISIBLE else View.GONE
        val jump = jumpTarget
        if (jump != 0L && items.isNotEmpty()) {
            val idx = items.indexOfFirst { it.messageId == jump }.coerceAtLeast(0)
            binding.recyclerView.post { binding.recyclerView.scrollToPosition(idx) }
            jumpTarget = 0L
        } else if (scrollToEnd && items.isNotEmpty()) {
            binding.recyclerView.post { binding.recyclerView.scrollToPosition(0) }
        }
        scrollToEnd = false
        pendingScroll = -1
        focusItemIfTv()
        // Pocos medios en esta tanda: sigue cargando mensajes más antiguos (con tope).
        if (auto && lastBatchFull && real < 12 && autoLoads < 6) {
            autoLoads++
            loadMore()
        }
    }

    private fun requestThumb(id: Int) {
        if (id == 0 || !thumbRequested.add(id)) return
        backend?.download(id, { _, _ -> }, { done ->
            if (done != null) {
                runOnUiThread {
                    thumbPaths[id] = done
                    adapter.thumbLoaded(id)
                    chatAdapter.thumbLoaded(id)
                    mediaAdapter.thumbLoaded(id)
                }
            }
        })
    }

    /** Resuelve un enlace de Telegram para mostrar el nombre y la foto del canal, grupo o bot al que lleva. */
    private fun requestLink(link: String) {
        if (linkRequested.size >= 80 || !linkRequested.add(link)) return
        backend?.resolveLink(link, false) { target ->
            if (target != null) {
                runOnUiThread {
                    linkChats[link] = target.chat
                    mediaAdapter.linkResolved(link)
                }
            }
        }
    }

    private fun kindIcon(m: TgMessage) = when (m.kind) {
        "video", "animation" -> "🎬"
        "audio", "voice" -> "🎵"
        "document" -> "📄"
        "photo" -> "🖼"
        "other" -> ""
        else -> "💬"
    }

    private fun messageTitle(m: TgMessage): String {
        val base = m.fileName ?: m.text.lineSequence().firstOrNull { it.isNotBlank() } ?: m.links.firstOrNull() ?: "(sin texto)"
        val icon = kindIcon(m)
        return if (icon.isEmpty()) base else "$icon $base"
    }

    private fun messageSubtitle(m: TgMessage, df: DateFormat): String {
        val parts = ArrayList<String>()
        val who = if (m.outgoing) getString(R.string.tgacc_me) else m.sender
        if (who.isNotEmpty()) parts.add(who)
        parts.add(df.format(Date(m.date * 1000L)))
        if (m.size > 0) parts.add(String.format(java.util.Locale.US, "%.1f MB", m.size / 1048576.0))
        if (m.links.isNotEmpty()) parts.add(getString(R.string.tg_links_count, m.links.size))
        return parts.joinToString(" · ")
    }

    // -----------------------------------------------------------------
    // Búsqueda y escribir
    // -----------------------------------------------------------------

    private fun normalize(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun setupSearchTabs() {
        binding.tgSearchTabs.addTab(binding.tgSearchTabs.newTab().setText(R.string.tgacc_search_tab_names), true)
        binding.tgSearchTabs.addTab(binding.tgSearchTabs.newTab().setText(R.string.tgacc_search_tab_messages), false)
        binding.tgSearchTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                if (searchMode != tab.position) {
                    searchMode = tab.position
                    if (searching) {
                        updateSearchHint()
                        runSearchNow()
                    }
                }
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    private fun updateSearchHint() {
        val inChat = currentChat != null
        searchView?.queryHint = getString(
            if (inChat || searchMode == 1) R.string.tgacc_search_hint_messages else R.string.tgacc_search_hint_names
        )
    }

    private fun enterSearchUi() {
        searchResults = null
        searchChatResults = emptyList()
        searchQuery = ""
        binding.inputBar.visibility = View.GONE
        binding.tgTabs.visibility = View.GONE
        binding.tgSearchTabs.visibility = if (currentChat == null) View.VISIBLE else View.GONE
        updateSearchHint()
        showMessage(getString(if (currentChat != null || searchMode == 1) R.string.tgacc_search_prompt_messages else R.string.tgacc_search_prompt_names))
        updateBack()
        updateMenuItems()
    }

    private fun exitSearchUi() {
        binding.root.removeCallbacks(searchDebounce)
        binding.tgSearchTabs.visibility = View.GONE
        searchResults = null
        searchChatResults = emptyList()
        val open = currentChat
        if (open != null) {
            if (showingTopics) {
                showTopics(open)
            } else {
                title = currentTopic?.let { open.title + " › " + it.name } ?: open.title
                renderMessages()
            }
        } else {
            renderChats()
        }
        updateBack()
        invalidateOptionsMenu()
    }

    private fun runSearchNow() {
        val b = backend ?: return
        if (!searching) return
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            showMessage(getString(if (currentChat != null || searchMode == 1) R.string.tgacc_search_prompt_messages else R.string.tgacc_search_prompt_names))
            return
        }
        val chat = currentChat
        if (chat == null && searchMode == 0) {
            b.searchChats(q) { list ->
                runOnUiThread {
                    if (isFinishing || isDestroyed || !searching || searchQuery.trim() != q || searchMode != 0 || currentChat != null) return@runOnUiThread
                    val nq = normalize(q)
                    val own = chats.filter { normalize(it.title).contains(nq) }
                    searchChatResults = (own + list).distinctBy { it.id }
                    searchResults = null
                    renderSearch()
                }
            }
        } else {
            b.searchMessages(q, chat?.id ?: 0L) { list ->
                runOnUiThread {
                    if (isFinishing || isDestroyed || !searching || searchQuery.trim() != q) return@runOnUiThread
                    searchChatResults = emptyList()
                    searchResults = list
                    renderSearch()
                }
            }
        }
    }

    private fun renderSearch() {
        useList()
        title = getString(R.string.tgacc_search_results, searchQuery)
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val favs = TelegramAccountPrefs.favoriteChats(this)
        val rows = ArrayList<Row>()
        searchChatResults.forEach { chat ->
            val sub = kindLabel(chat.kind) + (if (chat.unread > 0) " · " + getString(R.string.tgacc_unread, chat.unread) else "")
            rows.add(Row(chat.title, sub, chat.id in favs, {
                TelegramAccountPrefs.toggleFavorite(this, chat.id, chat)
                renderSearch()
            }, thumbFileId = chat.photoId, circle = true) { openChat(chat) })
        }
        val global = currentChat == null
        searchResults?.forEach { m ->
            val prefix = if (global && m.chatTitle.isNotEmpty()) m.chatTitle + " · " else ""
            rows.add(Row(messageTitle(m), prefix + messageSubtitle(m, df), null, null, { onMessageLong(m) }, m.thumbId) { onMessageClick(m) })
        }
        adapter.submit(rows)
        binding.emptyView.text = getString(R.string.tgacc_search_none)
        binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun sendTyped() {
        val chat = currentChat ?: return
        val text = binding.tgInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.tgSend.isEnabled = false
        val reply = replyTo?.messageId ?: 0L
        backend?.sendText(chat.id, text, reply, currentTopic?.id ?: 0L) { ok ->
            runOnUiThread {
                binding.tgSend.isEnabled = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    binding.tgInput.setText("")
                    setReply(null)
                    scrollToEnd = true
                    if (currentChat?.id == chat.id && !searching) scheduleReload(300)
                } else {
                    Toast.makeText(this, R.string.tgacc_send_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setReply(m: TgMessage?) {
        replyTo = m
        if (m == null) {
            binding.tgReplyLabel.visibility = View.GONE
        } else {
            val who = if (m.outgoing) getString(R.string.tgacc_me) else m.sender.ifEmpty { m.chatTitle }
            binding.tgReplyLabel.text = getString(R.string.tgacc_reply_to, who)
            binding.tgReplyLabel.visibility = View.VISIBLE
        }
    }

    private val infoRequested = HashSet<String>()

    /** Pide los datos de TMDb de una película (una vez) y, al llegar, refresca las miniaturas sin mover la lista. */
    private fun requestInfo(query: String) {
        if (query.isEmpty() || !infoRequested.add(query)) return
        if (!MovieInfo.needsFetch(this, query)) return
        MovieInfo.request(this, query) { found ->
            if (found != null) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    mediaAdapter.infoLoaded(query)
                }
            }
        }
    }

    private fun infoMeta(info: MovieInfo.Info): String = listOfNotNull(
        info.rating.takeIf { it > 0.0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) },
        info.genre
    ).joinToString(" · ")

    private val reQualityTag = Regex("(?i)\\b(4K|2160p|1080p|720p|480p|UHD|HDR|HEVC|x265|x264|DUAL|CAST|LAT|VOSE|MULTI)\\b")

    private fun formatSize(bytes: Long): String =
        if (bytes >= 1_000_000_000L) String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        else (bytes / 1_000_000L).toString() + " MB"

    /** "MP4 · 1080p · 1.4 GB": formato, calidad (si el nombre la trae) y tamaño de una versión del vídeo. */
    private fun optionInfo(m: TgMessage): String {
        val ext = m.fileName?.substringAfterLast('.', "")?.takeIf { it.length in 2..4 }?.uppercase()
            ?: (m.mime ?: "").substringAfter('/', "").let {
                when {
                    it.contains("matroska") -> "MKV"
                    it.contains("mp4") -> "MP4"
                    it.isNotBlank() -> it.uppercase().take(5)
                    else -> null
                }
            }
        val quality = reQualityTag.find(titleOf(m) + " " + (m.fileName ?: ""))?.value
        return listOfNotNull(ext, quality, m.size.takeIf { it > 0 }?.let { formatSize(it) }).joinToString(" · ")
    }

    private fun optionLabel(m: TgMessage, n: Int, multiple: Boolean): String {
        val base = if (multiple) getString(R.string.tgvid_option, n) else getString(R.string.tgvid_watch)
        val info = optionInfo(m)
        return if (info.isEmpty()) base else "$base · $info"
    }

    /** ¿Son los capítulos de una serie? (varios con temporada y número). */
    private fun isSeriesGroup(options: List<TgMessage>): Boolean =
        options.count { parseEpisode(titleOf(it)) != null } >= 2

    /** Ficha de la película o serie: carátula, datos y sinopsis; abajo, un botón por versión (opción 1…) o por capítulo (T1: 01 02 03…). */
    private fun showMovieInfo(options: List<TgMessage>, query: String, thumbId: Int) {
        val info = MovieInfo.peek(this, query)
        val series = isSeriesGroup(options)
        if (info == null && !series) {
            chooseVideoOption(options, query)
            return
        }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val col = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val poster = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(300)
            ).apply { topMargin = dp(12) }
        }
        val localThumb = if (thumbId != 0) thumbPaths[thumbId] else null
        if (info?.posterUrl != null) poster.load(info.posterUrl)
        else if (localThumb != null) poster.load(File(localThumb))
        else poster.visibility = View.GONE
        col.addView(poster)
        if (info != null) {
            val tv = TextView(this).apply {
                setPadding(dp(20), dp(12), dp(20), dp(8))
                textSize = 14f
            }
            val head = listOfNotNull(
                info.year,
                info.runtimeMin.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
                info.certification?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            val sb = StringBuilder()
            val line1 = listOf(infoMeta(info), head).filter { it.isNotEmpty() }.joinToString(" · ")
            if (line1.isNotEmpty()) sb.append(line1).append("\n\n")
            info.tagline?.takeIf { it.isNotBlank() }?.let { sb.append("“").append(it).append("”\n\n") }
            sb.append(info.overview?.takeIf { it.isNotBlank() } ?: getString(R.string.cine_no_synopsis))
            info.director?.takeIf { it.isNotBlank() }?.let {
                sb.append("\n\n").append(getString(if (info.isTv) R.string.cine_creator else R.string.cine_director)).append(": ").append(it)
            }
            if (info.cast.isNotEmpty()) sb.append("\n\n").append(getString(R.string.cine_cast)).append(": ").append(info.cast.joinToString(", "))
            tv.text = sb.toString()
            col.addView(tv)
        }
        var dialog: AlertDialog? = null
        // Capítulos en orden (una versión por capítulo; la elegida si hay varias): para el "siguiente capítulo" automático.
        fun queueFor(m: TgMessage): List<TgMessage> {
            if (!series) return emptyList()
            val byEp = options.mapNotNull { x -> parseEpisode(titleOf(x))?.let { Triple(it.season, it.episode, x) } }
                .groupBy { it.first to it.second }
            return byEp.toSortedMap(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
                .map { (_, v) -> v.firstOrNull { it.third.messageId == m.messageId }?.third ?: v.first().third }
        }
        fun play(m: TgMessage) {
            dialog?.dismiss()
            playVideo(m, queueFor(m))
        }
        if (series) {
            // Capítulos por temporada: T1, T2…; cada número es un botón.
            val eps = options.mapNotNull { m -> parseEpisode(titleOf(m))?.let { Triple(it.season, it.episode, m) } }
            val others = options.filter { parseEpisode(titleOf(it)) == null }
            for ((season, list) in eps.groupBy { it.first }.toSortedMap()) {
                col.addView(TextView(this).apply {
                    text = getString(R.string.tgvid_season, season)
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(androidx.core.content.ContextCompat.getColor(this@TelegramAccountActivity, R.color.primary))
                    setPadding(dp(20), dp(12), dp(20), dp(2))
                })
                val grid = android.widget.GridLayout(this).apply {
                    columnCount = 5
                    setPadding(dp(12), 0, dp(12), 0)
                }
                for ((_, ep, m) in list.sortedBy { it.second }) {
                    val btn = android.widget.Button(this).apply {
                        text = String.format(java.util.Locale.US, "%02d", ep)
                        isAllCaps = false
                        textSize = 13f
                        minWidth = 0
                        minimumWidth = 0
                        setPadding(0, 0, 0, 0)
                        setOnClickListener { play(m) }
                    }
                    val lp = android.widget.GridLayout.LayoutParams(
                        android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                        android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    ).apply {
                        width = 0
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                    grid.addView(btn, lp)
                }
                col.addView(grid)
            }
            for ((i, m) in others.withIndex()) col.addView(optionButton(m, i + 1, others.size > 1) { play(m) })
        } else {
            for ((i, m) in options.withIndex()) col.addView(optionButton(m, i + 1, options.size > 1) { play(m) })
        }
        col.addView(android.widget.Button(this).apply {
            text = getString(R.string.tgfwd_action)
            isAllCaps = false
            setOnClickListener { forwardVideoOptions(options, query) }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), dp(10), dp(16), dp(8)) }
        })
        col.addView(android.widget.Button(this).apply {
            text = getString(R.string.tgfold_add_btn)
            isAllCaps = false
            setOnClickListener { addToFolder(options, query, thumbId) }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), 0, dp(16), dp(8)) }
        })
        val scroll = android.widget.ScrollView(this).apply { addView(col) }
        val isFav = options.any { TelegramAccountPrefs.isVideoFavorite(this, it.chatId, it.messageId) }
        dialog = AlertDialog.Builder(this)
            .setTitle(info?.title ?: query)
            .setView(scroll)
            .setNeutralButton(if (isFav) R.string.tgvid_unfav_btn else R.string.tgvid_fav_btn) { _, _ -> toggleVideoFav(options, query, thumbId) }
            .setNegativeButton(R.string.channel_check_close, null)
            .show()
    }

    private fun optionButton(m: TgMessage, n: Int, multiple: Boolean, onClick: () -> Unit): android.widget.Button {
        val density = resources.displayMetrics.density
        return android.widget.Button(this).apply {
            text = optionLabel(m, n, multiple)
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt()) }
        }
    }

    /** Sin ficha de TMDb: una sola versión se reproduce directa; si hay varias, se elige cuál ver. */
    private fun chooseVideoOption(options: List<TgMessage>, query: String) {
        if (options.size <= 1) {
            options.firstOrNull()?.let { playVideo(it) }
            return
        }
        AlertDialog.Builder(this)
            .setTitle(query.ifEmpty { titleOf(options[0]) })
            .setItems(options.mapIndexed { i, m -> optionLabel(m, i + 1, true) }.toTypedArray()) { _, which -> playVideo(options[which]) }
            .setNeutralButton(R.string.tgfwd_action) { _, _ -> forwardVideoOptions(options, query) }
            .show()
    }

    /** Reenviar un vídeo: si tiene varias versiones, primero se elige cuál. */
    private fun forwardVideoOptions(options: List<TgMessage>, query: String) {
        if (options.size <= 1) {
            options.firstOrNull()?.let { forwardMessage(it) }
            return
        }
        AlertDialog.Builder(this)
            .setTitle(query.ifEmpty { titleOf(options[0]) })
            .setItems(options.mapIndexed { i, m -> optionLabel(m, i + 1, true) }.toTypedArray()) { _, which -> forwardMessage(options[which]) }
            .show()
    }

    /** Reproduce siempre directamente (sin descargar antes). */
    private fun playVideo(m: TgMessage, queue: List<TgMessage> = emptyList()) {
        when {
            m.kind == "linkvideo" -> m.links.firstOrNull()?.let { playLinkVideo(it) }
            m.fileId == null -> Toast.makeText(this, R.string.tg_nothing_to_play, Toast.LENGTH_SHORT).show()
            m.size > 0 -> streamPlay(m, queue)
            else -> downloadAndPlay(m) // Telegram no dio el tamaño: no se puede emitir, se descarga solo en este caso
        }
    }

    /** Pulsar un vídeo: con información, ficha con carátula y "Ver"; sin ella, se reproduce directamente (o se elige versión). */
    private fun onVideoClick(options: List<TgMessage>, query: String, thumbId: Int) {
        if ((query.isNotEmpty() && MovieInfo.peek(this, query) != null) || isSeriesGroup(options)) showMovieInfo(options, query, thumbId)
        else chooseVideoOption(options, query)
    }

    /** Pulsación larga: guarda o quita el vídeo (con todas sus versiones) de la carpeta "Vídeos". */
    private fun toggleVideoFav(options: List<TgMessage>, query: String, thumbId: Int) {
        val wasFav = options.any { TelegramAccountPrefs.isVideoFavorite(this, it.chatId, it.messageId) }
        for (m in options) {
            val isFav = TelegramAccountPrefs.isVideoFavorite(this, m.chatId, m.messageId)
            if (isFav == wasFav) TelegramAccountPrefs.toggleVideoFavorite(this, m, query, thumbId)
        }
        Toast.makeText(this, if (!wasFav) R.string.tgvid_fav_added else R.string.tgvid_fav_removed, Toast.LENGTH_SHORT).show()
        com.example.superplayer.widget.HomeWidgetProvider.refreshAll(this)
        if (currentChat == null && chatFilter == VIDEO_TAB && !searching) renderVideoFavs()
        else if (mediaMode && currentChat != null && !searching) renderMedia(auto = false)
    }

    /** Pestaña "Vídeos": solo los vídeos marcados como favoritos, en miniaturas (versiones del mismo vídeo juntas). */
    private fun renderVideoFavs() {
        val f = videoFolder
        updateBack()
        if (f == null) renderVideoFolders() else renderVideoCollection(f)
    }

    /** Inicio de la pestaña Vídeos: favoritos, tus carpetas y "nueva carpeta". */
    private fun renderVideoFolders() {
        useList()
        val rows = ArrayList<Row>()
        val favCount = TelegramAccountPrefs.videoFavorites(this).size
        rows.add(Row(getString(R.string.tgfold_favs), getString(R.string.tgfold_count, favCount)) {
            videoFolder = ""
            renderVideoFavs()
        })
        for (name in TelegramAccountPrefs.videoFolders(this)) {
            val n = TelegramAccountPrefs.folderVideos(this, name).size
            rows.add(Row("📁 $name", getString(R.string.tgfold_count, n), null, null, { manageFolder(name) }) {
                videoFolder = name
                renderVideoFavs()
            })
        }
        rows.add(Row(getString(R.string.tgfold_new), getString(R.string.tgfold_new_hint)) {
            promptNewFolder { }
        })
        adapter.submit(rows)
        binding.emptyView.visibility = View.GONE
        focusItemIfTv()
    }

    /** Miniaturas de los vídeos de favoritos ([folder] == "") o de una carpeta propia. */
    private fun renderVideoCollection(folder: String) {
        useGrid()
        val isFav = folder.isEmpty()
        title = if (isFav) getString(R.string.tgfold_favs) else "📁 $folder"
        val entries = if (isFav) TelegramAccountPrefs.videoFavorites(this) else TelegramAccountPrefs.folderVideos(this, folder)
        val items = ArrayList<MediaItem>()
        val groups = LinkedHashMap<String, MutableList<Triple<TgMessage, Int, String>>>()
        for (o in entries) {
            val m = TgMessage.fromJson(o)
            val q = o.optString("q")
            val name = q.ifEmpty { titleOf(m).ifEmpty { m.chatTitle } }
            val k = nameKey(name).ifEmpty { "id${m.messageId}" }
            groups.getOrPut(k) { ArrayList() }.add(Triple(m, o.optInt("th", 0), q))
        }
        for ((k, list) in groups) {
            val options = list.map { it.first }.sortedBy { it.messageId }
            val th = list.firstOrNull { it.second != 0 }?.second ?: 0
            val q = list[0].third
            val lead = list[0].first
            val name = q.ifEmpty { titleOf(lead).ifEmpty { lead.chatTitle } }
            val mark = if (isFav) "★" else "📁"
            items.add(MediaItem(key = "fav:$folder:$k", caption = name, meta = lead.chatTitle, thumbFileId = th,
                badge = if (options.size > 1) "▶ ×${options.size} $mark" else "▶ $mark", messageId = lead.messageId, query = q,
                options = options, pairedImage = th != 0,
                onLong = { if (isFav) toggleVideoFav(options, q, th) else removeFromFolder(folder, options, q, th) }) { onVideoClick(options, q, th) })
        }
        mediaAdapter.submit(items)
        binding.emptyView.text = getString(R.string.tgvid_empty)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        focusItemIfTv()
    }

    private fun removeFromFolder(folder: String, options: List<TgMessage>, query: String, thumbId: Int) {
        for (m in options) {
            if (TelegramAccountPrefs.isInFolder(this, folder, m.chatId, m.messageId)) {
                TelegramAccountPrefs.toggleInFolder(this, folder, m, query, thumbId)
            }
        }
        Toast.makeText(this, getString(R.string.tgfold_removed, folder), Toast.LENGTH_SHORT).show()
        if (currentChat == null && chatFilter == VIDEO_TAB && !searching) renderVideoFavs()
    }

    private fun promptNewFolder(onCreated: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.tgfold_name_hint)
            isSingleLine = true
            setPadding(dpPx(20), dpPx(12), dpPx(20), dpPx(12))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tgfold_new)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (TelegramAccountPrefs.createVideoFolder(this, name)) {
                    onCreated(name)
                    if (currentChat == null && chatFilter == VIDEO_TAB && !searching && videoFolder == null) renderVideoFavs()
                } else {
                    Toast.makeText(this, R.string.tgfold_name_error, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun manageFolder(name: String) {
        AlertDialog.Builder(this)
            .setTitle("📁 $name")
            .setItems(arrayOf(getString(R.string.tgfold_rename), getString(R.string.tgfold_delete))) { _, which ->
                if (which == 0) {
                    val input = EditText(this).apply {
                        setText(name)
                        setSelection(text.length)
                        isSingleLine = true
                        setPadding(dpPx(20), dpPx(12), dpPx(20), dpPx(12))
                    }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.tgfold_rename)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            if (!TelegramAccountPrefs.renameVideoFolder(this, name, input.text.toString())) {
                                Toast.makeText(this, R.string.tgfold_name_error, Toast.LENGTH_LONG).show()
                            }
                            renderVideoFavs()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.tgfold_delete_confirm, name))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            TelegramAccountPrefs.deleteVideoFolder(this, name)
                            if (videoFolder == name) videoFolder = null
                            renderVideoFavs()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    /** Elegir en qué carpetas está un vídeo (con todas sus versiones); "Nueva…" crea una y lo mete. */
    private fun addToFolder(options: List<TgMessage>, query: String, thumbId: Int) {
        val folders = TelegramAccountPrefs.videoFolders(this)
        fun setMember(folder: String, member: Boolean) {
            for (m in options) {
                val present = TelegramAccountPrefs.isInFolder(this, folder, m.chatId, m.messageId)
                if (member != present) TelegramAccountPrefs.toggleInFolder(this, folder, m, query, thumbId)
            }
        }
        if (folders.isEmpty()) {
            promptNewFolder { name ->
                setMember(name, true)
                Toast.makeText(this, getString(R.string.tgfold_added, name), Toast.LENGTH_SHORT).show()
            }
            return
        }
        val initial = BooleanArray(folders.size) { i ->
            options.any { TelegramAccountPrefs.isInFolder(this, folders[i], it.chatId, it.messageId) }
        }
        val checked = initial.copyOf()
        AlertDialog.Builder(this)
            .setTitle(R.string.tgfold_add_title)
            .setMultiChoiceItems(folders.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                for (i in folders.indices) if (checked[i] != initial[i]) setMember(folders[i], checked[i])
                Toast.makeText(this, R.string.tgfold_saved, Toast.LENGTH_SHORT).show()
                if (currentChat == null && chatFilter == VIDEO_TAB && !searching) renderVideoFavs()
            }
            .setNeutralButton(R.string.tgfold_new) { _, _ ->
                for (i in folders.indices) if (checked[i] != initial[i]) setMember(folders[i], checked[i])
                promptNewFolder { name ->
                    setMember(name, true)
                    Toast.makeText(this, getString(R.string.tgfold_added, name), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }


    private fun onMessageLong(m: TgMessage) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (currentChat != null && !searching) {
            labels.add(getString(R.string.tgacc_act_reply))
            actions.add { setReply(m) }
        }
        labels.add(getString(R.string.tgfwd_action))
        actions.add { forwardMessage(m) }
        m.links.firstOrNull { !isTgLink(it) && !isDirectMedia(it) }?.let { link ->
            labels.add(getString(R.string.tgweb_play_link))
            actions.add { playUrl(link, m.text.lineSequence().firstOrNull { it.isNotBlank() } ?: link) }
        }
        if (isVideoMsg(m) && realMessage(m) != null) {
            labels.add(getString(R.string.tgfold_add_btn))
            actions.add { addToFolder(listOf(realMessage(m)!!), cleanName(titleOf(m)), m.thumbId) }
        }
        labels.add(getString(R.string.tgacc_act_link))
        actions.add { copyMessageLink(m) }
        if (m.outgoing && m.fileId == null && m.kind != "other" && currentChat != null && !searching) {
            labels.add(getString(R.string.tgacc_act_edit))
            actions.add { editOwnMessage(m) }
        }
        if (m.text.isNotBlank()) {
            labels.add(getString(R.string.tgacc_act_copy))
            actions.add {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("telegram", m.text))
                Toast.makeText(this, R.string.tgacc_copied, Toast.LENGTH_SHORT).show()
            }
        }
        if (m.outgoing) {
            labels.add(getString(R.string.tgacc_act_delete))
            actions.add {
                backend?.deleteMessage(m.chatId, m.messageId) { ok ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        Toast.makeText(this, if (ok) R.string.tgacc_deleted else R.string.tgacc_delete_error, Toast.LENGTH_SHORT).show()
                        if (ok && !searching) scheduleReload(300)
                    }
                }
            }
        }
        if (actions.isEmpty()) return
        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** Mensaje real en Telegram (los vídeos "de enlace" son mensajes virtuales: se usa el que ya se resolvió). */
    private fun realMessage(m: TgMessage): TgMessage? =
        if (m.kind == "linkvideo") m.links.firstOrNull()?.let { linkVideoCache[it] } else m

    private fun forwardMessage(m: TgMessage) {
        val real = realMessage(m)
        if (real == null) {
            Toast.makeText(this, R.string.tgfwd_link_first, Toast.LENGTH_LONG).show()
            return
        }
        pickDestination { chat, topicId ->
            backend?.forwardMessage(real.chatId, real.messageId, chat.id, topicId) { ok ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (ok) Toast.makeText(this, getString(R.string.tgfwd_done, chat.title), Toast.LENGTH_LONG).show()
                    else Toast.makeText(this, R.string.tgfwd_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyMessageLink(m: TgMessage) {
        val real = realMessage(m) ?: return
        backend?.messageLink(real.chatId, real.messageId) { link ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (link == null) {
                    Toast.makeText(this, R.string.tgacc_no_link, Toast.LENGTH_SHORT).show()
                } else {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("telegram", link))
                    Toast.makeText(this, R.string.tgacc_copied, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun editOwnMessage(m: TgMessage) {
        val input = EditText(this).apply {
            setText(m.text)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tgacc_act_edit)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty() || text == m.text) return@setPositiveButton
                backend?.editMessage(m.chatId, m.messageId, text) { ok ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        Toast.makeText(this, if (ok) R.string.tgacc_edited else R.string.tgacc_action_error, Toast.LENGTH_SHORT).show()
                        if (ok) scheduleReload(500)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Elegir a qué chat (canal, grupo, foro, persona o bot de tu cuenta) enviar algo. Con un foro pregunta también el tema.
     * Se puede filtrar por tipo, buscar por nombre y, con 3+ letras, buscar también en Telegram.
     */
    private fun pickDestination(onPicked: (TgChat, Long) -> Unit) {
        val favs = TelegramAccountPrefs.favoriteChats(this)
        var filter = 1
        var remote: List<TgChat> = emptyList()
        var shown: List<TgChat> = emptyList()
        val labels = ArrayList<String>()
        val input = EditText(this).apply {
            hint = getString(R.string.tgfwd_search)
            isSingleLine = true
            setPadding(dpPx(20), dpPx(8), dpPx(20), dpPx(8))
        }
        val chipNames = listOf(R.string.tgfwd_f_favs, R.string.tgfwd_f_all, R.string.tgfwd_f_channels, R.string.tgfwd_f_groups, R.string.tgfwd_f_people)
        val chipFilter = listOf(0, 1, 2, 3, 4)
        val chips = ArrayList<TextView>()
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipScroll = android.widget.HorizontalScrollView(this).apply { addView(chipRow); isHorizontalScrollBarEnabled = false }
        val listView = android.widget.ListView(this)
        val listAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listView.adapter = listAdapter
        fun icon(kind: String) = when (kind) {
            "channel" -> "📢"; "group" -> "👥"; "forum" -> "🗂"; "bot" -> "🤖"; else -> "👤"
        }
        fun refresh() {
            val q = normalize(input.text.toString().trim())
            val base = (chats + remote).distinctBy { it.id }
            shown = base.filter { c ->
                val okType = when (filter) {
                    0 -> c.id in favs
                    2 -> c.kind == "channel"
                    3 -> c.kind == "group" || c.kind == "forum"
                    4 -> c.kind == "private" || c.kind == "bot"
                    else -> true
                }
                okType && (q.isEmpty() || normalize(c.title).contains(q))
            }.sortedWith(compareByDescending<TgChat> { it.id in favs }.thenByDescending { it.lastDate })
            labels.clear()
            labels.addAll(shown.map { icon(it.kind) + " " + it.title + (if (it.id in favs) "  ★" else "") })
            listAdapter.notifyDataSetChanged()
            chips.forEachIndexed { i, t ->
                val sel = chipFilter[i] == filter
                t.setTextColor(androidx.core.content.ContextCompat.getColor(this, if (sel) R.color.primary else R.color.on_background))
                t.setTypeface(null, if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
        for ((i, res) in chipNames.withIndex()) {
            val t = TextView(this).apply {
                setText(res)
                textSize = 14f
                setPadding(dpPx(14), dpPx(10), dpPx(14), dpPx(10))
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.bg_list_item)
                setOnClickListener {
                    filter = chipFilter[i]
                    refresh()
                }
            }
            chips.add(t)
            chipRow.addView(t)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(chipScroll)
            addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpPx(320)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.tgfwd_title)
            .setView(col)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        val debounce = Runnable {
            val q = input.text.toString().trim()
            if (q.length >= 3) {
                backend?.searchChats(q) { found ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed || !dialog.isShowing) return@runOnUiThread
                        remote = found
                        refresh()
                    }
                }
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                refresh()
                input.removeCallbacks(debounce)
                input.postDelayed(debounce, 500)
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        listView.setOnItemClickListener { _, _, position, _ ->
            val chat = shown.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            if (chat.kind == "forum") pickTopic(chat, onPicked) else onPicked(chat, 0L)
        }
        refresh()
    }

    private fun pickTopic(chat: TgChat, onPicked: (TgChat, Long) -> Unit) {
        backend?.loadTopics(chat.id) { topics ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (topics.isEmpty()) {
                    onPicked(chat, 0L)
                    return@runOnUiThread
                }
                val names = listOf(getString(R.string.tgfwd_general)) + topics.map { it.name }
                AlertDialog.Builder(this)
                    .setTitle(chat.title)
                    .setItems(names.toTypedArray()) { _, which -> onPicked(chat, if (which == 0) 0L else topics[which - 1].id) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun sendAttachment(uri: Uri) {
        val chat = currentChat ?: return
        val caption = binding.tgInput.text.toString().trim()
        val reply = replyTo
        Thread {
            var path: String? = null
            var isImage = false
            try {
                var name = "archivo"
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
                }
                isImage = (contentResolver.getType(uri) ?: "").startsWith("image/")
                val dir = File(cacheDir, "tg_upload").apply { mkdirs() }
                val out = File(dir, System.currentTimeMillis().toString() + "_" + name.replace('/', '_'))
                contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                path = out.absolutePath
            } catch (e: Exception) {
                path = null
            }
            val file = path
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (file == null) {
                    Toast.makeText(this, R.string.tgacc_attach_error, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                backend?.sendFile(chat.id, file, isImage, caption, currentTopic?.id ?: 0L) { ok ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (ok) {
                            binding.tgInput.setText("")
                            if (reply != null) setReply(null)
                            scrollToEnd = true
                            if (currentChat?.id == chat.id && !searching) scheduleReload(600)
                        } else {
                            Toast.makeText(this, R.string.tgacc_attach_error, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }.start()
    }

    private fun viewPhoto(m: TgMessage) {
        download(m) { path ->
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            img.load(File(path))
            val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.setContentView(img)
            img.setOnClickListener { dialog.dismiss() }
            dialog.show()
        }
    }

    // -----------------------------------------------------------------
    // Bots: comandos y botones
    // -----------------------------------------------------------------

    private fun sendToCurrentChat(text: String) {
        val chat = currentChat ?: return
        backend?.sendText(chat.id, text, 0L, currentTopic?.id ?: 0L) { ok ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) scheduleReload(1500) else Toast.makeText(this, R.string.tgacc_send_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showBotCommands() {
        val chat = currentChat ?: return
        backend?.loadBotCommands(chat.userId) { list ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (list.isEmpty()) {
                    Toast.makeText(this, R.string.tgacc_no_commands, Toast.LENGTH_LONG).show()
                } else {
                    val labels = list.map { "/" + it.first + (if (it.second.isNotEmpty()) " — " + it.second else "") }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.tgacc_cmds_title)
                        .setItems(labels.toTypedArray()) { _, which -> sendToCurrentChat("/" + list[which].first) }
                        .setNegativeButton(R.string.channel_check_close, null)
                        .show()
                }
            }
        }
    }

    /** Teclado de respuesta del bot: los botones del mensaje más reciente que lo traiga. */
    private fun showBotKeyboard() {
        val kb = messages.firstOrNull { m -> m.buttons.any { it.kind == "txt" } }?.buttons?.filter { it.kind == "txt" }
        if (kb.isNullOrEmpty()) {
            Toast.makeText(this, R.string.tgacc_no_keyboard, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tgacc_menu_kbd)
            .setItems(kb.map { it.text }.toTypedArray()) { _, which -> sendToCurrentChat(kb[which].data) }
            .setNegativeButton(R.string.channel_check_close, null)
            .show()
    }

    private fun showButtons(m: TgMessage) {
        val list = m.buttons
        AlertDialog.Builder(this)
            .setTitle(R.string.tgacc_buttons_title)
            .setItems(list.map { it.text }.toTypedArray()) { _, which -> pressInline(m, list[which]) }
            .setNegativeButton(R.string.channel_check_close, null)
            .show()
    }

    /** Pulsa un botón de un bot (en línea bajo el mensaje o de la lista de botones). */
    private fun pressInline(m: TgMessage, b: com.example.superplayer.telegram.TgButton) {
        when (b.kind) {
            "cb" -> backend?.pressButton(m.chatId, m.messageId, b.data) { answer, url ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (url != null) { if (isTgLink(url)) openTgLink(url) else openLink(url) }
                    if (answer != null) Toast.makeText(this, answer, Toast.LENGTH_LONG).show()
                    scheduleReload(1000)
                }
            }
            "url" -> if (isTgLink(b.data)) openTgLink(b.data) else openLink(b.data)
            "txt" -> sendToCurrentChat(b.data)
            else -> Toast.makeText(this, R.string.tgacc_btn_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dpPx(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun makeBotButton(text: String): android.widget.Button = android.widget.Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        minHeight = 0
        minimumHeight = dpPx(40)
        minWidth = 0
        minimumWidth = 0
        setPadding(dpPx(8), dpPx(6), dpPx(8), dpPx(6))
        setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.on_background))
        setBackgroundResource(R.drawable.bg_tg_inline_btn)
        stateListAnimator = null
        isFocusable = true
    }

    /** Pinta los botones en línea de un mensaje (filas como las manda el bot) debajo de la burbuja. */
    private fun fillInlineButtons(container: LinearLayout, m: TgMessage) {
        container.removeAllViews()
        val inline = m.buttons.filter { it.kind != "txt" }
        if (inline.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        for ((_, rowButtons) in inline.groupBy { it.row }.toSortedMap()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (b in rowButtons) {
                val label = when (b.kind) {
                    "url" -> "↗ " + b.text
                    else -> b.text
                }
                val btn = makeBotButton(label)
                btn.setOnClickListener { pressInline(m, b) }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(dpPx(2), dpPx(2), dpPx(2), dpPx(2))
                row.addView(btn, lp)
            }
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Teclado de respuesta del bot, encima de la caja de texto (como en Telegram), con el botón ⌨ para ocultarlo. */
    private fun updateKeyboard() {
        updateLiveBanner()
        val kb = if (currentChat == null || searching) emptyList()
        else messages.take(15).firstOrNull { m -> m.buttons.any { it.kind == "txt" } }?.buttons?.filter { it.kind == "txt" }.orEmpty()
        binding.tgKbdToggle.visibility = if (kb.isEmpty()) View.GONE else View.VISIBLE
        val panel = binding.tgKeyboard
        if (kb.isEmpty() || kbHidden) {
            binding.tgKeyboardScroll.visibility = View.GONE
            panel.removeAllViews()
            return
        }
        panel.removeAllViews()
        val rows = kb.groupBy { it.row }.toSortedMap()
        for ((_, rowButtons) in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (b in rowButtons) {
                val btn = makeBotButton(b.text)
                btn.setOnClickListener {
                    scrollToEnd = true
                    sendToCurrentChat(b.data)
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(dpPx(2), dpPx(2), dpPx(2), dpPx(2))
                row.addView(btn, lp)
            }
            panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val lp = binding.tgKeyboardScroll.layoutParams
        lp.height = if (rows.size > 4) dpPx(4 * 46) else ViewGroup.LayoutParams.WRAP_CONTENT
        binding.tgKeyboardScroll.layoutParams = lp
        binding.tgKeyboardScroll.visibility = View.VISIBLE
    }

    /** Una web va al apartado Web de la app; los enlaces internos de Telegram (t.me, tg://) siguen con Telegram. */
    private fun openWeb(url: String) {
        startActivity(Intent(this, WebActivity::class.java).setData(Uri.parse(url)))
    }

    private fun isDirectMedia(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return listOf(".m3u8", ".mpd", ".mp4", ".mkv", ".m4v", ".mp3", ".aac", ".ts").any { path.endsWith(it) }
    }

    /** El directo (llamada de vídeo del chat) se ve en la app oficial de Telegram: se abre el chat ahí, donde sale el aviso del directo. */
    private fun openLiveInTelegram(chat: TgChat) {
        val id = chat.id
        val uri = when {
            id <= -1_000_000_000_000L -> "https://t.me/c/${-id - 1_000_000_000_000L}/1"
            id < 0 -> "tg://openmessage?chat_id=${-id}"
            else -> "tg://openmessage?user_id=$id"
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(null))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.tgacc_live_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun openLink(url: String) {
        if (!isTgLink(url) && (url.startsWith("http://", true) || url.startsWith("https://", true))) {
            openWeb(url)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cine_trailer_error, Toast.LENGTH_SHORT).show()
        }
    }

    // -----------------------------------------------------------------
    // Acciones
    // -----------------------------------------------------------------

    private fun onMessageClick(m: TgMessage, withButtons: Boolean = true) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (withButtons && m.buttons.isNotEmpty()) {
            labels.add(getString(R.string.tgacc_buttons, m.buttons.size))
            actions.add { showButtons(m) }
        }
        if (m.fileId != null) {
            if (m.kind == "photo" || (m.kind == "document" && (m.mime ?: "").startsWith("image/"))) {
                labels.add(getString(R.string.tgacc_view_photo))
                actions.add { viewPhoto(m) }
            } else if (looksLikePlaylist(m)) {
                labels.add(getString(R.string.tg_load_as_main))
                actions.add { loadPlaylistFile(m) { data -> TelegramImport.openInMain(this, data) } }
                labels.add(getString(R.string.tg_open_playlist))
                actions.add { openPlaylist(m) }
            } else if (m.kind != "document" || looksLikeMedia(m)) {
                if (m.size > 0) {
                    labels.add(getString(R.string.tgacc_stream_play))
                    actions.add { streamPlay(m) }
                }
                labels.add(getString(R.string.tgacc_download_play))
                actions.add { downloadAndPlay(m) }
            }
        }
        for (link in m.links) {
            if (isTgLink(link)) {
                labels.add(getString(R.string.tgacc_open_link, link.removePrefix("https://").removePrefix("http://").take(40)))
                actions.add { openTgLink(link) }
                continue
            }
            if (TelegramImport.looksLikeListUrl(link)) {
                labels.add(getString(R.string.tg_link_as_list, link.removePrefix("https://").removePrefix("http://").take(40)))
                actions.add { loadPlaylistUrl(link) }
            }
            val short = link.removePrefix("https://").removePrefix("http://").take(60)
            if (isDirectMedia(link)) {
                labels.add("▶ $short")
                actions.add { playUrl(link, m.text.lineSequence().firstOrNull { it.isNotBlank() } ?: link) }
            } else {
                // Una página web: se abre en el apartado Web (el reproductor queda en la pulsación larga).
                labels.add(getString(R.string.tgweb_open, short))
                actions.add { openWeb(link) }
            }
        }
        when {
            actions.isEmpty() -> if (m.text.isNotBlank()) {
                AlertDialog.Builder(this)
                    .setMessage(m.text)
                    .setPositiveButton(R.string.channel_check_close, null)
                    .show()
            } else {
                Toast.makeText(this, R.string.tg_nothing_to_play, Toast.LENGTH_SHORT).show()
            }
            actions.size == 1 -> actions[0]()
            else -> AlertDialog.Builder(this)
                .setTitle(messageTitle(m))
                .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
                .show()
        }
    }

    private val reMsgLink = Regex("(?i)t\\.me/(?:c/\\d+/\\d+(?:/\\d+)?|[a-z][a-z0-9_]{3,}/\\d+(?:/\\d+)?)/?(?:\\?.*)?$")
    private val reUrlInText = Regex("https?://\\S+|(?:t|telegram)\\.me/\\S+")

    /** Enlace a un mensaje concreto (t.me/c/<chat>/<mensaje>, t.me/c/<chat>/<tema>/<mensaje>, t.me/nombre/<mensaje>). */
    private fun isTgMessageLink(url: String): Boolean = isTgLink(url) && reMsgLink.containsMatchIn(url.trim())

    /**
     * Mensajes con enlaces a otro mensaje de Telegram (donde está el vídeo de verdad) -p. ej. una imagen con
     * "Peli (2025) https://t.me/c/…/…"-: cada enlace se añade como un "vídeo" más, con el título de su línea,
     * para que se una a su imagen y a sus versiones/capítulos igual que un vídeo normal.
     */
    private fun withLinkVideos(list: List<TgMessage>): List<TgMessage> {
        val out = ArrayList<TgMessage>(list.size)
        for (m in list) {
            out.add(m)
            if (isVideoMsg(m)) continue
            val links = m.links.filter { isTgMessageLink(it) }.distinct()
            if (links.isEmpty()) continue
            val lines = m.text.lineSequence().filter { it.isNotBlank() }.toList()
            for ((i, link) in links.withIndex()) {
                val own = lines.firstOrNull { it.contains(link) || it.contains(link.removePrefix("https://").removePrefix("http://")) }
                val raw = own?.replace(reUrlInText, "")
                    ?: (if (links.size == lines.size) lines[i] else lines.firstOrNull())
                    ?: ""
                var title = raw.trim().trim('-', ':', '|', ' ', '·')
                if (title.isEmpty()) title = lines.firstOrNull()?.replace(reUrlInText, "")?.trim()?.trim('-', ':', '|', ' ', '·').orEmpty()
                out.add(m.copy(messageId = m.messageId + i + 1, text = title, kind = "linkvideo", fileId = null, fileName = null,
                    mime = null, size = 0L, links = listOf(link), buttons = emptyList(), thumbId = 0))
            }
        }
        return out
    }

    private val linkVideoCache = HashMap<String, TgMessage>()

    /** Busca el vídeo al que lleva un enlace interno de Telegram y lo reproduce. */
    private fun playLinkVideo(link: String) {
        val b = backend ?: return
        val cached = linkVideoCache[link]
        if (cached != null) {
            playVideo(cached)
            return
        }
        Toast.makeText(this, R.string.tgvid_searching, Toast.LENGTH_SHORT).show()
        b.resolveLink(link, false) { target ->
            if (target == null || target.chat.id == 0L || target.messageId == 0L) {
                runOnUiThread { if (!isFinishing && !isDestroyed) Toast.makeText(this, R.string.tgacc_link_error, Toast.LENGTH_LONG).show() }
                return@resolveLink
            }
            b.loadHistory(target.chat.id, target.chat.title, target.messageId, -1) { batch ->
                val found = batch.firstOrNull { it.messageId == target.messageId }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (found == null || (found.fileId == null && found.kind != "video")) {
                        Toast.makeText(this, R.string.tgvid_link_nofile, Toast.LENGTH_LONG).show()
                    } else {
                        linkVideoCache[link] = found
                        playVideo(found)
                    }
                }
            }
        }
    }

    private fun isTgLink(url: String): Boolean {
        val u = url.lowercase().removePrefix("https://").removePrefix("http://")
        return u.startsWith("t.me/") || u.startsWith("telegram.me/") || u.startsWith("telegram.dog/") || u.startsWith("tg://")
    }

    /** Abre dentro de la app un enlace interno de Telegram (usuario, canal, grupo, bot, invitación, mensaje). */
    private fun openTgLink(link: String, join: Boolean = false) {
        val b = backend ?: return
        Toast.makeText(this, R.string.tg_loading, Toast.LENGTH_SHORT).show()
        b.resolveLink(link, join) { target ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    target == null -> Toast.makeText(this, R.string.tgacc_link_error, Toast.LENGTH_LONG).show()
                    target.chat.id == 0L -> AlertDialog.Builder(this)
                        .setMessage(getString(R.string.tgacc_join_confirm, target.chat.title))
                        .setPositiveButton(android.R.string.ok) { _, _ -> openTgLink(link, true) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    else -> {
                        val here = currentChat
                        if (here != null && !showingTopics) navStack.add(NavEntry(here, currentTopic, currentAnchor()))
                        else if (here != null) navStack.add(NavEntry(here, null, 0L))
                        openChat(target.chat, target.messageId, target.startParam, keepStack = true)
                    }
                }
            }
        }
    }

    private fun looksLikePlaylist(m: TgMessage): Boolean {
        if (m.kind != "document" || m.size > 30_000_000) return false
        return TelegramImport.looksLikeListName(m.fileName) || TelegramImport.looksLikeListMime(m.mime)
    }

    private fun looksLikeMedia(m: TgMessage): Boolean {
        val mime = (m.mime ?: "").lowercase()
        val name = (m.fileName ?: "").lowercase()
        return mime.startsWith("video/") || mime.startsWith("audio/") ||
            listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".ts", ".mp3", ".m4a", ".flac", ".ogg").any { name.endsWith(it) }
    }

    /** Descarga el archivo del mensaje mostrando el progreso y llama a [onPath] con la ruta local. */
    private fun download(m: TgMessage, onPath: (String) -> Unit) {
        val fileId = m.fileId?.toIntOrNull() ?: return
        val b = backend ?: return
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.tgacc_downloading)
            .setMessage("0 %")
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> b.cancelDownload(fileId) }
            .show()
        downloadDialog = dialog
        b.download(fileId, { done, total ->
            runOnUiThread {
                val text = if (total > 0) {
                    String.format(java.util.Locale.US, "%d %%  (%.1f / %.1f MB)", done * 100 / total, done / 1048576.0, total / 1048576.0)
                } else {
                    String.format(java.util.Locale.US, "%.1f MB", done / 1048576.0)
                }
                dialog.setMessage(text)
            }
        }, { path ->
            runOnUiThread {
                dialog.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (path.isNullOrEmpty()) {
                    Toast.makeText(this, getString(R.string.tg_file_error, ""), Toast.LENGTH_LONG).show()
                } else {
                    onPath(path)
                }
            }
        })
    }

    /** Reproduce sin descargar el archivo entero: un servidor local pide a TDLib solo los trozos necesarios. */
    /** Stream de reproducción directa de un mensaje con archivo, o null si no se puede emitir (sin id o sin tamaño). */
    private fun streamFor(m: TgMessage): Stream? {
        val b = backend ?: return null
        val fileId = m.fileId?.toIntOrNull() ?: return null
        if (m.size <= 0) return null
        val name = m.fileName ?: m.text.lineSequence().firstOrNull { it.isNotBlank() } ?: m.chatTitle
        val audio = m.kind == "audio" || m.kind == "voice" || (m.mime ?: "").startsWith("audio/")
        val mime = m.mime ?: if (audio) "audio/mpeg" else "video/mp4"
        val url = TgStreamServer.urlFor(b, fileId, m.size, mime)
        return Stream(name = name, type = if (audio) "MP3" else "MP4", url = url, icon = null, category = "Telegram")
    }

    /** [queue]: los capítulos de la serie en orden; al acabar uno, el reproductor salta al siguiente con cuenta atrás. */
    private fun streamPlay(m: TgMessage, queue: List<TgMessage> = emptyList()) {
        val stream = streamFor(m) ?: return
        val appCtx = applicationContext
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        TgStreamServer.onProblem = { msg ->
            handler.post { android.widget.Toast.makeText(appCtx, "Telegram sin datos: $msg", android.widget.Toast.LENGTH_LONG).show() }
        }
        val list = if (queue.size > 1) queue.mapNotNull { streamFor(it) } else emptyList()
        open(stream, if (list.any { it.id == stream.id }) list else emptyList())
    }

    private fun downloadAndPlay(m: TgMessage) {
        val name = m.fileName ?: m.text.lineSequence().firstOrNull { it.isNotBlank() } ?: m.chatTitle
        val audio = m.kind == "audio" || m.kind == "voice" || (m.mime ?: "").startsWith("audio/")
        download(m) { path ->
            open(Stream(name = name, type = if (audio) "MP3" else "MP4", url = Uri.fromFile(File(path)).toString(), icon = null, category = "Telegram"))
        }
    }

    /** Descarga y analiza una lista enviada como archivo; [onData] se llama en el hilo principal. */
    private fun loadPlaylistFile(m: TgMessage, onData: (com.example.superplayer.model.PlaylistData) -> Unit) {
        download(m) { path ->
            Thread {
                var data: com.example.superplayer.model.PlaylistData? = null
                try {
                    data = PlaylistRepository.parse(File(path).readText())
                } catch (e: Exception) {
                    // se avisa abajo
                }
                val found = data
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (found == null || found.categories.sumOf { it.streams.size } == 0) {
                        Toast.makeText(this, getString(R.string.tg_file_error, getString(R.string.tg_playlist_error)), Toast.LENGTH_LONG).show()
                    } else {
                        onData(found)
                    }
                }
            }.start()
        }
    }

    /** Una lista a la que apunta un enlace del chat: se descarga y se carga como lista principal. */
    private fun loadPlaylistUrl(url: String) {
        Toast.makeText(this, R.string.tg_loading, Toast.LENGTH_SHORT).show()
        Thread {
            var data: com.example.superplayer.model.PlaylistData? = null
            try {
                data = PlaylistRepository.loadFromUrl(url)
            } catch (e: Exception) {
                // se avisa abajo
            }
            val found = data
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (found == null || found.categories.sumOf { it.streams.size } == 0) {
                    Toast.makeText(this, getString(R.string.tg_file_error, getString(R.string.tg_playlist_error)), Toast.LENGTH_LONG).show()
                } else {
                    TelegramImport.openInMain(this, found)
                }
            }
        }.start()
    }

    /** Elegir un canal suelto de la lista de un archivo, sin cargarla como lista principal. */
    private fun openPlaylist(m: TgMessage) {
        loadPlaylistFile(m) { data ->
            val found = data.categories.flatMap { it.streams }.take(500)
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.tg_playlist_title, found.size))
                .setItems(found.map { it.name }.toTypedArray()) { _, which ->
                    PlayerActivity.pendingStream = found[which]
                    PlayerActivity.pendingChannelList = found
                    startActivity(Intent(this, PlayerActivity::class.java))
                }
                .setNegativeButton(R.string.channel_check_close, null)
                .show()
        }
    }

    private fun playUrl(url: String, name: String) {
        val lower = url.lowercase().substringBefore('?')
        val type = when {
            lower.endsWith(".m3u8") -> "HLS"
            lower.endsWith(".mpd") -> "DASH"
            lower.endsWith(".mp3") || lower.endsWith(".aac") -> "MP3"
            else -> "MP4"
        }
        open(Stream(name = name.take(80), type = type, url = url, icon = null, category = "Telegram"))
    }

    private fun open(stream: Stream, list: List<Stream> = emptyList()) {
        PlayerActivity.pendingStream = stream
        PlayerActivity.pendingChannelList = list
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    /** La conversación como burbujas: lo tuyo a la derecha, lo demás a la izquierda, con foto, hora y botones del bot debajo. */
    private inner class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<TgMessage?> = emptyList() // null = "cargar mensajes más antiguos"
        private val linkColor = android.graphics.Color.parseColor("#3D8BFF")

        fun submit(list: List<TgMessage>, header: Boolean) {
            val old = items
            val now = ArrayList<TgMessage?>(list.size + 1)
            if (header) now.add(null)
            now.addAll(list)
            if (old.isEmpty() || now.isEmpty()) {
                items = now
                notifyDataSetChanged()
                return
            }
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = now.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o]?.messageId == now[n]?.messageId
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == now[n]
            })
            items = now
            diff.dispatchUpdatesTo(this)
        }

        fun thumbLoaded(id: Int) {
            items.forEachIndexed { i, m -> if (m != null && m.thumbId == id) notifyItemChanged(i) }
        }

        override fun getItemViewType(position: Int) = if (items[position] == null) 0 else 1
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderVH(ItemTgLoadmoreBinding.inflate(inf, parent, false))
            else BubbleVH(ItemTgBubbleBinding.inflate(inf, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val m = items[position]
            if (holder is HeaderVH) {
                holder.b.tgLoadMore.setText(R.string.tgacc_load_more)
                holder.b.tgLoadMore.setOnClickListener { loadMore() }
                return
            }
            if (m == null || holder !is BubbleVH) return
            val b = holder.b
            val out = m.outgoing
            // Lado y color de la burbuja
            val lp = b.tgbColumn.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.gravity = if (out) android.view.Gravity.END else android.view.Gravity.START
            lp.marginStart = if (out) dpPx(48) else 0
            lp.marginEnd = if (out) 0 else dpPx(48)
            b.tgbColumn.layoutParams = lp
            b.tgbBubble.setBackgroundResource(if (out) R.drawable.bg_tg_bubble_out else R.drawable.bg_tg_bubble_in)
            // Quién lo dice (solo en grupos)
            val kind = currentChat?.kind
            val showSender = !out && m.sender.isNotEmpty() && (kind == "group" || kind == "forum")
            b.tgbSender.visibility = if (showSender) View.VISIBLE else View.GONE
            b.tgbSender.text = m.sender
            // Imagen / miniatura
            if (m.thumbId != 0) {
                b.tgbImageFrame.visibility = View.VISIBLE
                val path = thumbPaths[m.thumbId]
                if (path != null) b.tgbImage.load(File(path)) else {
                    b.tgbImage.setImageDrawable(null)
                    requestThumb(m.thumbId)
                }
                b.tgbPlay.visibility = if (m.kind == "video" || m.kind == "animation") View.VISIBLE else View.GONE
            } else {
                b.tgbImageFrame.visibility = View.GONE
                b.tgbImage.setImageDrawable(null)
            }
            // Archivo
            val isImage = m.kind == "photo" || (m.mime ?: "").startsWith("image/")
            if (m.fileId != null && !(isImage && m.thumbId != 0) && m.kind != "other") {
                val name = m.fileName ?: when (m.kind) { "audio" -> "Audio"; "voice" -> "Nota de voz"; "video" -> "Vídeo"; else -> "Archivo" }
                val size = if (m.size > 0) String.format(java.util.Locale.US, "  (%.1f MB)", m.size / 1048576.0) else ""
                b.tgbFile.text = kindIcon(m) + " " + name + size
                b.tgbFile.visibility = View.VISIBLE
            } else {
                b.tgbFile.visibility = View.GONE
            }
            // Texto, con los enlaces en azul
            val text = m.text
            if (text.isBlank()) {
                b.tgbText.visibility = if (m.fileId == null && m.thumbId == 0) View.VISIBLE else View.GONE
                b.tgbText.text = getString(R.string.tgacc_no_text)
            } else {
                val sp = android.text.SpannableString(text)
                for (u in reUrlInText.findAll(text)) {
                    sp.setSpan(android.text.style.ForegroundColorSpan(linkColor), u.range.first, u.range.last + 1, 0)
                }
                b.tgbText.text = sp
                b.tgbText.visibility = View.VISIBLE
            }
            // Hora
            val ms = m.date * 1000L
            val f = if (android.text.format.DateUtils.isToday(ms)) "HH:mm" else "dd/MM HH:mm"
            b.tgbTime.text = java.text.SimpleDateFormat(f, java.util.Locale.getDefault()).format(Date(ms))
            // Pulsar / mantener
            val hasAction = m.fileId != null || m.links.isNotEmpty()
            b.tgbBubble.setOnClickListener { if (hasAction) onMessageClick(m, withButtons = false) }
            b.tgbBubble.setOnLongClickListener { onMessageLong(m); true }
            // Botones del bot, debajo
            fillInlineButtons(b.tgbButtons, m)
        }

        inner class HeaderVH(val b: ItemTgLoadmoreBinding) : RecyclerView.ViewHolder(b.root)
        inner class BubbleVH(val b: ItemTgBubbleBinding) : RecyclerView.ViewHolder(b.root)
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) {
            val same = rows.size == newRows.size && rows.indices.all { i ->
                val a = rows[i]
                val b = newRows[i]
                a.title == b.title && a.subtitle == b.subtitle && a.star == b.star && a.thumbFileId == b.thumbFileId
            }
            if (same) {
                rows = newRows // mismas filas: solo se actualizan las acciones, sin repintar (así no se pierde el foco)
                return
            }
            keepFocus {
                rows = newRows
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemTelegramBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.b.tgTitle.text = row.title
            holder.b.tgSubtitle.text = row.subtitle
            holder.b.tgSubtitle.visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE
            holder.b.root.setOnClickListener { row.onClick() }
            // Mando de TV: mantener pulsado OK sobre un chat lo marca/desmarca como favorito.
            val longAction = row.onLong ?: row.onStar
            if (longAction != null) {
                holder.b.root.setOnLongClickListener { longAction(); true }
            } else {
                holder.b.root.setOnLongClickListener(null)
                holder.b.root.isLongClickable = false
            }
            bindThumb(holder, row)
            val star = row.star
            if (star == null) {
                holder.b.tgStar.visibility = View.GONE
            } else {
                holder.b.tgStar.visibility = View.VISIBLE
                holder.b.tgStar.text = if (star) "★" else "☆"
                holder.b.tgStar.setOnClickListener { row.onStar?.invoke() }
            }
        }

        fun thumbLoaded(id: Int) {
            rows.forEachIndexed { i, r -> if (r.thumbFileId == id) notifyItemChanged(i) }
        }

        private fun bindThumb(holder: VH, row: Row) {
            val id = row.thumbFileId
            val path = if (id != 0) thumbPaths[id] else null
            if (path == null) {
                if (row.circle) {
                    holder.b.tgThumb.visibility = View.VISIBLE
                    holder.b.tgThumb.load(R.drawable.ic_placeholder) { transformations(CircleCropTransformation()) }
                } else {
                    holder.b.tgThumb.setImageDrawable(null)
                    holder.b.tgThumb.visibility = View.GONE
                }
                requestThumb(id)
            } else {
                holder.b.tgThumb.visibility = View.VISIBLE
                if (row.circle) {
                    holder.b.tgThumb.load(File(path)) { transformations(CircleCropTransformation()) }
                } else {
                    holder.b.tgThumb.load(File(path))
                }
            }
        }

        override fun getItemCount() = rows.size

        inner class VH(val b: ItemTelegramBinding) : RecyclerView.ViewHolder(b.root)
    }

    private inner class MediaAdapter : RecyclerView.Adapter<MediaAdapter.VH>() {
        private var items: List<MediaItem> = emptyList()

        fun submit(list: List<MediaItem>) {
            val old = items
            if (old.isEmpty() || list.isEmpty()) {
                items = list
                notifyDataSetChanged()
                return
            }
            // Cambios puntuales (mensajes nuevos, miniaturas): la lista no salta y el mando conserva el foco.
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o].key == list[n].key
                override fun areContentsTheSame(o: Int, n: Int): Boolean {
                    val a = old[o]
                    val b = list[n]
                    return a.caption == b.caption && a.meta == b.meta && a.thumbFileId == b.thumbFileId &&
                        a.iconRes == b.iconRes && a.badge == b.badge && a.link == b.link &&
                        a.options.size == b.options.size && a.pairedImage == b.pairedImage
                }
            })
            items = list
            diff.dispatchUpdatesTo(this)
        }

        fun isFull(position: Int) = items.getOrNull(position)?.loadMore == true

        fun thumbLoaded(id: Int) {
            items.forEachIndexed { i, r -> if (r.thumbFileId == id) notifyItemChanged(i) }
        }

        fun messageIdAt(position: Int) = items.getOrNull(position)?.messageId ?: 0L

        fun infoLoaded(query: String) {
            items.forEachIndexed { i, r -> if (r.query == query) notifyItemChanged(i) }
        }

        fun linkResolved(link: String) {
            items.forEachIndexed { i, r -> if (r.link == link) notifyItemChanged(i) }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemTgMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val b = holder.b
            b.root.setOnClickListener { item.onClick() }
            val long = item.onLong
            if (long != null) {
                b.root.setOnLongClickListener { long(); true }
            } else {
                b.root.setOnLongClickListener(null)
                b.root.isLongClickable = false
            }
            if (item.loadMore) {
                b.tgmFrame.visibility = View.GONE
                b.tgmCaption.text = item.caption
                b.tgmCaption.gravity = android.view.Gravity.CENTER
                b.tgmMeta.visibility = View.GONE
                return
            }
            b.tgmFrame.visibility = View.VISIBLE
            b.tgmCaption.gravity = android.view.Gravity.START
            var caption = item.caption
            var thumbId = item.thumbFileId
            val link = item.link
            if (link != null) {
                val chat = linkChats[link]
                if (chat != null) {
                    caption = chat.title
                    thumbId = chat.photoId
                } else {
                    requestLink(link)
                }
            }
            var meta = item.meta
            var posterUrl: String? = null
            if (item.query.isNotEmpty()) {
                val info = MovieInfo.peek(this@TelegramAccountActivity, item.query)
                if (info != null) {
                    caption = info.title + (info.year?.let { " ($it)" } ?: "")
                    meta = listOf(infoMeta(info), item.meta).filter { it.isNotEmpty() }.joinToString(" · ")
                    posterUrl = info.posterUrl
                } else {
                    requestInfo(item.query)
                }
            }
            b.tgmCaption.text = caption
            b.tgmMeta.text = meta
            b.tgmMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
            b.tgmBadge.text = item.badge
            b.tgmBadge.visibility = if (item.badge.isEmpty()) View.GONE else View.VISIBLE
            val path = if (thumbId != 0) thumbPaths[thumbId] else null
            b.tgmFrame.setBackgroundResource(R.color.surface_dark)
            if (posterUrl != null && !item.pairedImage) {
                // Sin imagen en el chat: la carátula de TMDb (mejor que el fotograma del propio vídeo, que suele salir negro).
                b.tgmIcon.visibility = View.GONE
                b.tgmThumb.visibility = View.VISIBLE
                b.tgmThumb.load(posterUrl)
            } else if (path != null) {
                b.tgmIcon.visibility = View.GONE
                b.tgmThumb.visibility = View.VISIBLE
                b.tgmThumb.load(File(path))
            } else if (posterUrl != null) {
                b.tgmIcon.visibility = View.GONE
                b.tgmThumb.visibility = View.VISIBLE
                b.tgmThumb.load(posterUrl)
                requestThumb(thumbId)
            } else {
                if (item.tileRes != 0) b.tgmFrame.setBackgroundResource(item.tileRes)
                b.tgmThumb.setImageDrawable(null)
                b.tgmThumb.visibility = View.GONE
                b.tgmIcon.visibility = View.VISIBLE
                b.tgmIcon.setImageResource(if (item.iconRes != 0) item.iconRes else R.drawable.ic_placeholder)
                requestThumb(thumbId)
            }
        }

        override fun getItemCount() = items.size

        inner class VH(val b: ItemTgMediaBinding) : RecyclerView.ViewHolder(b.root)
    }
}
