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
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
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

    private class Row(val title: String, val subtitle: String, val onClick: () -> Unit)

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

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = closeChat()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

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
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                if (currentChat != null) closeChat() else finish()
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
        else -> getString(R.string.tgacc_kind_private)
    }

    private fun renderChats() {
        title = getString(R.string.tgacc_title)
        backCallback.isEnabled = false
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val rows = chats.map { chat ->
            val sub = kindLabel(chat.kind) + (if (chat.lastDate > 0) " · " + df.format(Date(chat.lastDate * 1000L)) else "")
            Row(chat.title, sub) { openChat(chat) }
        }
        adapter.submit(rows)
        binding.emptyView.text = getString(R.string.tgacc_no_chats)
        binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openChat(chat: TgChat) {
        currentChat = chat
        messages.clear()
        lastBatchFull = false
        title = chat.title
        backCallback.isEnabled = true
        adapter.submit(emptyList())
        binding.emptyView.text = getString(R.string.tg_loading)
        binding.emptyView.visibility = View.VISIBLE
        loadMore()
    }

    private fun closeChat() {
        currentChat = null
        renderChats()
    }

    private fun loadMore() {
        val chat = currentChat ?: return
        val from = messages.lastOrNull()?.messageId ?: 0L
        backend?.loadHistory(chat.id, chat.title, from) { batch ->
            runOnUiThread {
                if (isFinishing || isDestroyed || currentChat?.id != chat.id) return@runOnUiThread
                messages.addAll(batch)
                lastBatchFull = batch.size >= 5
                renderMessages()
            }
        }
    }

    private fun renderMessages() {
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val rows = ArrayList<Row>()
        messages.forEach { m ->
            rows.add(Row(messageTitle(m), messageSubtitle(m, df)) { onMessageClick(m) })
        }
        if (lastBatchFull) {
            rows.add(Row(getString(R.string.tgacc_load_more), "") { loadMore() })
        }
        adapter.submit(rows)
        binding.emptyView.text = getString(R.string.tg_empty_chat)
        binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun kindIcon(m: TgMessage) = when (m.kind) {
        "video", "animation" -> "🎬"
        "audio", "voice" -> "🎵"
        "document" -> "📄"
        else -> "💬"
    }

    private fun messageTitle(m: TgMessage): String {
        val base = m.fileName ?: m.text.lineSequence().firstOrNull { it.isNotBlank() } ?: m.links.firstOrNull() ?: "(sin texto)"
        return kindIcon(m) + " " + base
    }

    private fun messageSubtitle(m: TgMessage, df: DateFormat): String {
        val parts = ArrayList<String>()
        parts.add(df.format(Date(m.date * 1000L)))
        if (m.size > 0) parts.add(String.format(java.util.Locale.US, "%.1f MB", m.size / 1048576.0))
        if (m.links.isNotEmpty()) parts.add(getString(R.string.tg_links_count, m.links.size))
        return parts.joinToString(" · ")
    }

    // -----------------------------------------------------------------
    // Acciones
    // -----------------------------------------------------------------

    private fun onMessageClick(m: TgMessage) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (m.fileId != null) {
            if (looksLikePlaylist(m)) {
                labels.add(getString(R.string.tg_open_playlist))
                actions.add { openPlaylist(m) }
            } else if (m.kind != "document" || looksLikeMedia(m)) {
                labels.add(getString(R.string.tgacc_download_play))
                actions.add { downloadAndPlay(m) }
            }
        }
        for (link in m.links) {
            labels.add("▶ " + link.removePrefix("https://").removePrefix("http://").take(60))
            actions.add { playUrl(link, m.text.lineSequence().firstOrNull { it.isNotBlank() } ?: link) }
        }
        when {
            actions.isEmpty() -> Toast.makeText(this, R.string.tg_nothing_to_play, Toast.LENGTH_SHORT).show()
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
        }

        override fun getItemCount() = rows.size

        inner class VH(val b: ItemTelegramBinding) : RecyclerView.ViewHolder(b.root)
    }
}
