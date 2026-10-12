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
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun useGrid() {
        if (usingGrid && binding.recyclerView.adapter === mediaAdapter) return
        usingGrid = true
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
                    val nowFav = TelegramAccountPrefs.toggleFavorite(this, it.id)
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
                chats = list
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
        backCallback.isEnabled = searching || currentChat != null
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
            val sub = kindLabel(chat.kind) +
                (if (chat.unread > 0) " · " + getString(R.string.tgacc_unread, chat.unread) else "") +
                (if (chat.lastDate > 0) " · " + df.format(Date(chat.lastDate * 1000L)) else "")
            Row(chat.title, sub, chat.id in favs, {
                val nowFav = TelegramAccountPrefs.toggleFavorite(this, chat.id)
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

    private fun closeChat() {
        navStack.clear()
        returnToChatId = currentChat?.id ?: 0L
        currentChat = null
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
        binding.tgSearchTabs.visibility = View.GONE
        updateBack()
        if (mediaMode) {
            renderMedia()
            return
        }
        useList()
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val rows = ArrayList<Row>()
        if (lastBatchFull) {
            rows.add(Row(getString(R.string.tgacc_load_more), "") { loadMore() })
        }
        // Como en Telegram: lo más antiguo arriba y lo más nuevo abajo.
        messages.reversed().forEach { m ->
            rows.add(Row(messageTitle(m), messageSubtitle(m, df), null, null, { onMessageLong(m) }, m.thumbId) { onMessageClick(m) })
        }
        adapter.submit(rows)
        binding.emptyView.text = getString(R.string.tg_empty_chat)
        binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        val last = rows.size - 1
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

    private fun nameKey(raw: String): String =
        java.text.Normalizer.normalize(cleanName(raw).lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun isImageMsg(m: TgMessage) = m.kind == "photo" || (m.kind == "document" && (m.mime ?: "").startsWith("image/"))

    /** Un .m3u subido como documento suele llegar con tipo "audio/…": hay que mirarlo antes de darlo por audio/vídeo. */
    private fun isListMsg(m: TgMessage) = m.kind == "document" && looksLikePlaylist(m)

    private fun isVideoMsg(m: TgMessage) =
        !isListMsg(m) && (m.kind == "video" || m.kind == "animation" || (m.kind == "document" && looksLikeMedia(m)))

    private fun isMovieFile(m: TgMessage): Boolean {
        if (m.kind == "animation") return false
        val mime = (m.mime ?: "").lowercase()
        val name = (m.fileName ?: "").lowercase()
        return m.kind == "video" || mime.startsWith("video/") ||
            listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".ts", ".m4v").any { name.endsWith(it) }
    }

    private fun titleOf(m: TgMessage): String =
        m.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: m.fileName.orEmpty()

    /** Galería: solo fotos, vídeos, listas (archivos y enlaces) y enlaces de Telegram, en miniaturas con su texto debajo. */
    private fun renderMedia(auto: Boolean = true) {
        useGrid()
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val items = ArrayList<MediaItem>()
        val seenLinks = HashSet<String>()
        // Imagen y vídeo con el mismo nombre: la imagen es la miniatura del vídeo y no se repite aparte.
        val imageByName = HashMap<String, TgMessage>()
        for (m in messages) {
            if (!isImageMsg(m)) continue
            val k = nameKey(titleOf(m))
            if (k.length >= 2) imageByName.putIfAbsent(k, m)
        }
        val paired = HashMap<Long, TgMessage>() // id del vídeo -> su imagen
        val usedImages = HashSet<Long>()
        for (m in messages) {
            if (!isVideoMsg(m) && !isListMsg(m)) continue
            val img = imageByName[nameKey(titleOf(m))] ?: continue
            if (usedImages.add(img.messageId)) paired[m.messageId] = img
        }
        for (m in messages) { // de más nuevo a más antiguo
            val caption = m.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val date = df.format(Date(m.date * 1000L))
            val isImage = isImageMsg(m)
            val isVideo = isVideoMsg(m)
            if (isImage) {
                if (m.messageId in usedImages) continue
                items.add(MediaItem(key = "${m.messageId}:img", caption = caption.ifEmpty { m.fileName.orEmpty() }, meta = date, thumbFileId = m.thumbId,
                    messageId = m.messageId, onLong = { onMessageLong(m) }) { onMessageClick(m) })
            } else if (isVideo) {
                val img = paired[m.messageId]
                val thumb = img?.thumbId?.takeIf { it != 0 } ?: m.thumbId
                val q = if (isMovieFile(m)) cleanName(titleOf(m)).takeIf { it.length >= 2 }.orEmpty() else ""
                val fav = TelegramAccountPrefs.isVideoFavorite(this, m.chatId, m.messageId)
                items.add(MediaItem(key = "${m.messageId}:vid", caption = (if (q.isNotEmpty()) q else caption.ifEmpty { m.fileName.orEmpty() }), meta = date, thumbFileId = thumb,
                    badge = if (fav) "▶ ★" else "▶", messageId = m.messageId, query = q,
                    onLong = { toggleVideoFav(m, q, thumb) }) { onVideoClick(m, q, thumb) })
            } else if (isListMsg(m)) {
                val img = paired[m.messageId]
                val name = m.fileName ?: caption
                items.add(MediaItem(key = "${m.messageId}:file", caption = cleanName(caption.ifEmpty { name }), meta = date, thumbFileId = img?.thumbId ?: 0,
                    iconRes = R.drawable.ic_tg_list, tileRes = R.drawable.bg_tg_tile_list,
                    badge = getString(R.string.tgacc_badge_list), messageId = m.messageId,
                    onLong = { onMessageLong(m) }) { onMessageClick(m) })
            }
            for (link in m.links) {
                if (!seenLinks.add(link)) continue
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
                    searchChatResults = list
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
                TelegramAccountPrefs.toggleFavorite(this, chat.id)
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

    /** Ficha de la película: carátula, datos y sinopsis, con "Ver" debajo (los mismos datos que en Cine). */
    private fun showMovieInfo(m: TgMessage, query: String, thumbId: Int) {
        val info = MovieInfo.peek(this, query)
        if (info == null) {
            playVideo(m)
            return
        }
        val density = resources.displayMetrics.density
        val col = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val poster = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (300 * density).toInt()
            ).apply { topMargin = (12 * density).toInt() }
        }
        val localThumb = if (thumbId != 0) thumbPaths[thumbId] else null
        if (info.posterUrl != null) poster.load(info.posterUrl)
        else if (localThumb != null) poster.load(File(localThumb))
        else poster.visibility = View.GONE
        val tv = TextView(this).apply {
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
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
        col.addView(poster)
        col.addView(tv)
        val scroll = android.widget.ScrollView(this).apply { addView(col) }
        val isFav = TelegramAccountPrefs.isVideoFavorite(this, m.chatId, m.messageId)
        AlertDialog.Builder(this)
            .setTitle(info.title)
            .setView(scroll)
            .setPositiveButton(R.string.cine_watch) { _, _ -> playVideo(m) }
            .setNeutralButton(if (isFav) R.string.tgvid_unfav_btn else R.string.tgvid_fav_btn) { _, _ -> toggleVideoFav(m, query, thumbId) }
            .setNegativeButton(R.string.channel_check_close, null)
            .show()
    }

    /** Reproduce siempre directamente (sin descargar antes). */
    private fun playVideo(m: TgMessage) {
        when {
            m.fileId == null -> Toast.makeText(this, R.string.tg_nothing_to_play, Toast.LENGTH_SHORT).show()
            m.size > 0 -> streamPlay(m)
            else -> downloadAndPlay(m) // Telegram no dio el tamaño: no se puede emitir, se descarga solo en este caso
        }
    }

    /** Pulsar un vídeo: con información, ficha con carátula y "Ver"; sin ella, se reproduce directamente. */
    private fun onVideoClick(m: TgMessage, query: String, thumbId: Int) {
        if (query.isNotEmpty() && MovieInfo.peek(this, query) != null) showMovieInfo(m, query, thumbId) else playVideo(m)
    }

    /** Pulsación larga: guarda o quita el vídeo de la carpeta "Vídeos" (favoritos de vídeo). */
    private fun toggleVideoFav(m: TgMessage, query: String, thumbId: Int) {
        val now = TelegramAccountPrefs.toggleVideoFavorite(this, m, query, thumbId)
        Toast.makeText(this, if (now) R.string.tgvid_fav_added else R.string.tgvid_fav_removed, Toast.LENGTH_SHORT).show()
        com.example.superplayer.widget.HomeWidgetProvider.refreshAll(this)
        if (currentChat == null && chatFilter == VIDEO_TAB && !searching) renderVideoFavs()
        else if (mediaMode && currentChat != null && !searching) renderMedia(auto = false)
    }

    /** Pestaña "Vídeos": solo los vídeos marcados como favoritos, en miniaturas. */
    private fun renderVideoFavs() {
        useGrid()
        val items = ArrayList<MediaItem>()
        for (o in TelegramAccountPrefs.videoFavorites(this)) {
            val m = TgMessage.fromJson(o)
            val th = o.optInt("th", 0)
            val q = o.optString("q")
            val name = q.ifEmpty { m.fileName ?: m.text.lineSequence().firstOrNull { it.isNotBlank() } ?: m.chatTitle }
            items.add(MediaItem(key = "fav:${m.chatId}:${m.messageId}", caption = name, meta = m.chatTitle, thumbFileId = th,
                badge = "▶ ★", messageId = m.messageId, query = q,
                onLong = { toggleVideoFav(m, q, th) }) { onVideoClick(m, q, th) })
        }
        mediaAdapter.submit(items)
        binding.emptyView.text = getString(R.string.tgvid_empty)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        focusItemIfTv()
    }

    private fun onMessageLong(m: TgMessage) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (currentChat != null && !searching) {
            labels.add(getString(R.string.tgacc_act_reply))
            actions.add { setReply(m) }
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
            .setItems(list.map { it.text }.toTypedArray()) { _, which ->
                val b = list[which]
                when (b.kind) {
                    "cb" -> backend?.pressButton(m.chatId, m.messageId, b.data) { answer, url ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (url != null) openLink(url)
                            if (answer != null) {
                                AlertDialog.Builder(this)
                                    .setTitle(R.string.tgacc_bot_answer)
                                    .setMessage(answer)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                            scheduleReload(1200)
                        }
                    }
                    "url" -> openLink(b.data)
                    else -> sendToCurrentChat(b.data)
                }
            }
            .setNegativeButton(R.string.channel_check_close, null)
            .show()
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cine_trailer_error, Toast.LENGTH_SHORT).show()
        }
    }

    // -----------------------------------------------------------------
    // Acciones
    // -----------------------------------------------------------------

    private fun onMessageClick(m: TgMessage) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (m.buttons.isNotEmpty()) {
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
            labels.add("▶ " + link.removePrefix("https://").removePrefix("http://").take(60))
            actions.add { playUrl(link, m.text.lineSequence().firstOrNull { it.isNotBlank() } ?: link) }
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
    private fun streamPlay(m: TgMessage) {
        val b = backend ?: return
        val fileId = m.fileId?.toIntOrNull() ?: return
        val name = m.fileName ?: m.text.lineSequence().firstOrNull { it.isNotBlank() } ?: m.chatTitle
        val audio = m.kind == "audio" || m.kind == "voice" || (m.mime ?: "").startsWith("audio/")
        val mime = m.mime ?: if (audio) "audio/mpeg" else "video/mp4"
        val url = TgStreamServer.urlFor(b, fileId, m.size, mime)
        open(Stream(name = name, type = if (audio) "MP3" else "MP4", url = url, icon = null, category = "Telegram"))
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

    private fun open(stream: Stream) {
        PlayerActivity.pendingStream = stream
        PlayerActivity.pendingChannelList = emptyList()
        startActivity(Intent(this, PlayerActivity::class.java))
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
                        a.iconRes == b.iconRes && a.badge == b.badge && a.link == b.link
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
            if (path != null) {
                b.tgmIcon.visibility = View.GONE
                b.tgmThumb.visibility = View.VISIBLE
                b.tgmThumb.load(File(path))
            } else if (posterUrl != null) {
                // Sin imagen en el chat: la carátula de TMDb.
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
