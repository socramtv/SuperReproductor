package com.example.superplayer.ui

import android.content.Intent
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.superplayer.R
import com.example.superplayer.data.PlaylistRepository
import com.example.superplayer.databinding.ActivityTelegramBinding
import com.example.superplayer.databinding.ItemTelegramBinding
import com.example.superplayer.model.Stream
import com.example.superplayer.player.PlayerActivity
import com.example.superplayer.telegram.TelegramAccountFactory
import com.example.superplayer.telegram.TelegramAccountPrefs
import com.example.superplayer.telegram.TgAccountBackend
import com.example.superplayer.telegram.TgAuthState
import com.example.superplayer.telegram.TgStreamServer
import com.example.superplayer.telegram.TgChat
import com.example.superplayer.telegram.TgMessage
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
        val onClick: () -> Unit
    )

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
    private var searchView: SearchView? = null
    private val searchDebounce = Runnable { runSearchNow() }
    private val attachLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendAttachment(uri)
    }
    /** Pestaña de la lista de chats: 0 favoritos, 1 todos, 2 canales, 3 grupos, 4 bots, 5 personas. */
    private var chatFilter = 1
    private val reloadRunnable = Runnable {
        val c = currentChat
        if (c != null && !searching && !isFinishing && !isDestroyed) {
            messages.clear()
            lastBatchFull = false
            scrollToEnd = true
            loadMore()
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

        adapter = RowAdapter()
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

    private fun showMessage(text: String) {
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
                    if (!isFinishing && !isDestroyed && currentChat?.id == chatId && !searching) scheduleReload(600)
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
            TgAuthState.READY -> loadChatList()
            TgAuthState.CLOSED -> showMessage(getString(R.string.tgacc_closed))
        }
    }

    private var openDialog: AlertDialog? = null

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
                if (searchMode == 0 && currentChat == null) {
                    binding.root.removeCallbacks(searchDebounce)
                    binding.root.postDelayed(searchDebounce, 400)
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

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val chat = currentChat
        val inChat = chat != null && !searching
        menu.findItem(R.id.action_tgacc_fav)?.isVisible = inChat
        menu.findItem(R.id.action_tgacc_cmds)?.isVisible = inChat && chat?.kind == "bot"
        menu.findItem(R.id.action_tgacc_kbd)?.isVisible = inChat && chat?.kind == "bot"
        menu.findItem(R.id.action_tgacc_join)?.isVisible = inChat && (chat?.kind == "channel" || chat?.kind == "group")
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                goBack()
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
                currentChat = null
                renderChats()
            }
        }
    }

    private fun kindLabel(kind: String) = when (kind) {
        "channel" -> getString(R.string.tgacc_kind_channel)
        "group" -> getString(R.string.tgacc_kind_group)
        "bot" -> getString(R.string.tgacc_kind_bot)
        else -> getString(R.string.tgacc_kind_private)
    }

    private fun updateBack() {
        backCallback.isEnabled = searching || currentChat != null
    }

    private fun goBack() {
        if (searching) {
            searchItem?.collapseActionView()
        } else if (currentChat != null) {
            closeChat()
        } else {
            finish()
        }
    }

    private fun setupTabs() {
        val labels = listOf(
            R.string.tgacc_tab_fav, R.string.tgacc_tab_all, R.string.tgacc_tab_channels,
            R.string.tgacc_tab_groups, R.string.tgacc_tab_bots, R.string.tgacc_tab_people
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
        title = getString(R.string.tgacc_title)
        searchResults = null
        binding.inputBar.visibility = View.GONE
        binding.tgTabs.visibility = View.VISIBLE
        binding.tgSearchTabs.visibility = View.GONE
        updateBack()
        invalidateOptionsMenu()
        val favs = TelegramAccountPrefs.favoriteChats(this)
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val shown = chats.filter { chat ->
            when (chatFilter) {
                0 -> chat.id in favs
                2 -> chat.kind == "channel"
                3 -> chat.kind == "group"
                4 -> chat.kind == "bot"
                5 -> chat.kind == "private"
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
            }) { openChat(chat) }
        }
        adapter.submit(rows)
        binding.emptyView.text = getString(if (chatFilter == 0) R.string.tgacc_no_favs else R.string.tgacc_no_chats)
        binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openChat(chat: TgChat) {
        if (searching) {
            suppressExit = true
            searchItem?.collapseActionView()
            suppressExit = false
            searching = false
        }
        binding.tgSearchTabs.visibility = View.GONE
        currentChat = chat
        messages.clear()
        lastBatchFull = false
        scrollToEnd = true
        setReply(null)
        title = chat.title
        searchResults = null
        binding.inputBar.visibility = View.VISIBLE
        binding.tgTabs.visibility = View.GONE
        updateBack()
        invalidateOptionsMenu()
        adapter.submit(emptyList())
        binding.emptyView.text = getString(R.string.tg_loading)
        binding.emptyView.visibility = View.VISIBLE
        loadMore()
    }

    private fun closeChat() {
        currentChat = null
        binding.root.removeCallbacks(reloadRunnable)
        renderChats()
        loadChatList()
    }

    private fun loadMore() {
        val chat = currentChat ?: return
        val from = messages.lastOrNull()?.messageId ?: 0L
        backend?.loadHistory(chat.id, chat.title, from) { batch ->
            runOnUiThread {
                if (isFinishing || isDestroyed || currentChat?.id != chat.id) return@runOnUiThread
                if (from != 0L) pendingScroll = batch.size
                messages.addAll(batch)
                lastBatchFull = batch.size >= 5
                if (!searching) renderMessages()
            }
        }
    }

    private fun renderMessages() {
        binding.inputBar.visibility = View.VISIBLE
        binding.tgSearchTabs.visibility = View.GONE
        updateBack()
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val rows = ArrayList<Row>()
        if (lastBatchFull) {
            rows.add(Row(getString(R.string.tgacc_load_more), "") { loadMore() })
        }
        // Como en Telegram: lo más antiguo arriba y lo más nuevo abajo.
        messages.reversed().forEach { m ->
            rows.add(Row(messageTitle(m), messageSubtitle(m, df), null, null, { onMessageLong(m) }) { onMessageClick(m) })
        }
        adapter.submit(rows)
        binding.emptyView.text = getString(R.string.tg_empty_chat)
        binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        val last = rows.size - 1
        if (scrollToEnd && last >= 0) {
            binding.recyclerView.post { binding.recyclerView.scrollToPosition(last) }
        } else if (pendingScroll > 0 && last >= 0) {
            val target = pendingScroll.coerceAtMost(last)
            binding.recyclerView.post { binding.recyclerView.scrollToPosition(target) }
        }
        scrollToEnd = false
        pendingScroll = -1
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
        invalidateOptionsMenu()
    }

    private fun exitSearchUi() {
        binding.root.removeCallbacks(searchDebounce)
        binding.tgSearchTabs.visibility = View.GONE
        searchResults = null
        searchChatResults = emptyList()
        if (currentChat != null) {
            title = currentChat?.title
            renderMessages()
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
        title = getString(R.string.tgacc_search_results, searchQuery)
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val favs = TelegramAccountPrefs.favoriteChats(this)
        val rows = ArrayList<Row>()
        searchChatResults.forEach { chat ->
            val sub = kindLabel(chat.kind) + (if (chat.unread > 0) " · " + getString(R.string.tgacc_unread, chat.unread) else "")
            rows.add(Row(chat.title, sub, chat.id in favs, {
                TelegramAccountPrefs.toggleFavorite(this, chat.id)
                renderSearch()
            }) { openChat(chat) })
        }
        val global = currentChat == null
        searchResults?.forEach { m ->
            val prefix = if (global && m.chatTitle.isNotEmpty()) m.chatTitle + " · " else ""
            rows.add(Row(messageTitle(m), prefix + messageSubtitle(m, df), null, null, { onMessageLong(m) }) { onMessageClick(m) })
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
        backend?.sendText(chat.id, text, reply) { ok ->
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
                backend?.sendFile(chat.id, file, isImage, caption) { ok ->
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
                adjustViewBounds = true
            }
            img.load(File(path))
            AlertDialog.Builder(this)
                .setView(img)
                .setPositiveButton(R.string.channel_check_close, null)
                .show()
        }
    }

    // -----------------------------------------------------------------
    // Bots: comandos y botones
    // -----------------------------------------------------------------

    private fun sendToCurrentChat(text: String) {
        val chat = currentChat ?: return
        backend?.sendText(chat.id, text) { ok ->
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
            if (m.kind == "photo") {
                labels.add(getString(R.string.tgacc_view_photo))
                actions.add { viewPhoto(m) }
            } else if (looksLikePlaylist(m)) {
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

    private fun looksLikePlaylist(m: TgMessage): Boolean {
        val name = (m.fileName ?: "").lowercase()
        val mime = (m.mime ?: "").lowercase()
        if (m.kind != "document" || m.size > 2_000_000) return false
        return name.endsWith(".m3u") || name.endsWith(".m3u8") || name.endsWith(".json") || mime.contains("mpegurl")
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

    private fun openPlaylist(m: TgMessage) {
        download(m) { path ->
            Thread {
                var streams: List<Stream> = emptyList()
                try {
                    val data = PlaylistRepository.parse(File(path).readText())
                    streams = data.categories.flatMap { it.streams }.take(500)
                } catch (e: Exception) {
                    // se avisa abajo
                }
                val found = streams
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (found.isEmpty()) {
                        Toast.makeText(this, getString(R.string.tg_file_error, getString(R.string.tg_playlist_error)), Toast.LENGTH_LONG).show()
                    } else {
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
            }.start()
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
            rows = newRows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemTelegramBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.b.tgTitle.text = row.title
            holder.b.tgSubtitle.text = row.subtitle
            holder.b.tgSubtitle.visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE
            holder.b.root.setOnClickListener { row.onClick() }
            val long = row.onLong
            if (long != null) {
                holder.b.root.setOnLongClickListener { long(); true }
            } else {
                holder.b.root.setOnLongClickListener(null)
                holder.b.root.isLongClickable = false
            }
            val star = row.star
            if (star == null) {
                holder.b.tgStar.visibility = View.GONE
            } else {
                holder.b.tgStar.visibility = View.VISIBLE
                holder.b.tgStar.text = if (star) "★" else "☆"
                holder.b.tgStar.setOnClickListener { row.onStar?.invoke() }
            }
        }

        override fun getItemCount() = rows.size

        inner class VH(val b: ItemTelegramBinding) : RecyclerView.ViewHolder(b.root)
    }
}
