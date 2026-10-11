package com.example.superplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import com.example.superplayer.telegram.TelegramBot
import com.example.superplayer.telegram.TgMessage
import java.text.DateFormat
import java.util.Date

/**
 * Telegram con un bot: lista de canales/grupos donde está el bot y, al abrir
 * uno, sus mensajes (vídeos, audios, listas y enlaces) para reproducirlos.
 * Mientras la pantalla está abierta se consultan los mensajes nuevos.
 */
class TelegramActivity : AppCompatActivity() {

    private class Row(val title: String, val subtitle: String, val onClick: () -> Unit)

    private lateinit var binding: ActivityTelegramBinding
    private lateinit var adapter: RowAdapter
    private var currentChat: Long? = null
    private var polling = false
    private var pollGeneration = 0
    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTelegramBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applySystemBarInsets(top = binding.toolbar, bottom = binding.recyclerView)

        adapter = RowAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                currentChat = null
                render()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        render()
        if (TelegramBot.getToken(this).isEmpty()) showTokenDialog()
    }

    override fun onStart() {
        super.onStart()
        startPolling()
    }

    override fun onStop() {
        super.onStop()
        polling = false
        pollGeneration++
    }

    private fun startPolling() {
        if (TelegramBot.getToken(this).isEmpty()) return
        polling = true
        val generation = ++pollGeneration
        Thread {
            while (polling && generation == pollGeneration) {
                val n = TelegramBot.poll(applicationContext, 20)
                if (n > 0) {
                    runOnUiThread { if (!isFinishing && !isDestroyed) render() }
                } else if (n < 0) {
                    try { Thread.sleep(4000) } catch (e: InterruptedException) { return@Thread }
                }
            }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_telegram, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                if (currentChat != null) {
                    currentChat = null
                    render()
                } else finish()
                return true
            }
            R.id.action_tg_token -> {
                showTokenDialog()
                return true
            }
            R.id.action_tg_clear -> {
                AlertDialog.Builder(this)
                    .setMessage(R.string.tg_clear_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        TelegramBot.clear(this)
                        currentChat = null
                        render()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showTokenDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.tg_token_hint)
            setText(TelegramBot.getToken(this@TelegramActivity))
            setSingleLine(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tg_token_title)
            .setMessage(R.string.tg_token_help)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val token = input.text.toString().trim()
                if (token.isEmpty()) return@setPositiveButton
                Toast.makeText(this, R.string.tg_checking, Toast.LENGTH_SHORT).show()
                Thread {
                    val name = TelegramBot.checkToken(token)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (name == null) {
                            Toast.makeText(this, R.string.tg_token_bad, Toast.LENGTH_LONG).show()
                        } else {
                            TelegramBot.setToken(this, token)
                            Toast.makeText(this, getString(R.string.tg_token_ok, name), Toast.LENGTH_LONG).show()
                            currentChat = null
                            render()
                            pollGeneration++
                            startPolling()
                        }
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -----------------------------------------------------------------
    // Pantallas
    // -----------------------------------------------------------------

    private fun render() {
        val all = TelegramBot.loadAll(this)
        val chat = currentChat
        val rows = ArrayList<Row>()
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        if (chat == null) {
            title = getString(R.string.tg_title)
            backCallback.isEnabled = false
            all.groupBy { it.chatId }
                .values
                .sortedByDescending { list -> list.maxOf { it.date } }
                .forEach { list ->
                    val last = list.maxByOrNull { it.date }!!
                    val sub = getString(R.string.tg_chat_sub, list.size, df.format(Date(last.date * 1000L)))
                    rows.add(Row(last.chatTitle, sub) {
                        currentChat = last.chatId
                        render()
                    })
                }
        } else {
            val msgs = all.filter { it.chatId == chat }.sortedByDescending { it.date }
            title = msgs.firstOrNull()?.chatTitle ?: getString(R.string.tg_title)
            backCallback.isEnabled = true
            msgs.forEach { m ->
                rows.add(Row(messageTitle(m), messageSubtitle(m, df)) { onMessageClick(m) })
            }
        }
        adapter.submit(rows)
        binding.emptyView.text = when {
            TelegramBot.getToken(this).isEmpty() -> getString(R.string.tg_empty_no_token)
            else -> getString(R.string.tg_empty)
        }
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
    // Acciones sobre un mensaje
    // -----------------------------------------------------------------

    private fun onMessageClick(m: TgMessage) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val fileId = m.fileId
        if (fileId != null) {
            val isPlaylist = looksLikePlaylist(m)
            if (isPlaylist) {
                labels.add(getString(R.string.tg_open_playlist))
                actions.add { openPlaylistFile(fileId) }
            } else if (m.kind != "document" || looksLikeMedia(m)) {
                labels.add(getString(R.string.tg_play_file))
                actions.add { playFile(m) }
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

    private fun playFile(m: TgMessage) {
        val fileId = m.fileId ?: return
        if (m.size > TelegramBot.MAX_FILE_BYTES) {
            Toast.makeText(this, R.string.tg_too_big, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.tg_loading, Toast.LENGTH_SHORT).show()
        val name = m.fileName ?: m.text.lineSequence().firstOrNull { it.isNotBlank() } ?: m.chatTitle
        val audio = m.kind == "audio" || m.kind == "voice" || (m.mime ?: "").startsWith("audio/")
        Thread {
            val r = TelegramBot.fileUrl(applicationContext, fileId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val url = r.url
                if (url == null) {
                    Toast.makeText(this, getString(R.string.tg_file_error, r.error.orEmpty()), Toast.LENGTH_LONG).show()
                } else {
                    open(Stream(name = name, type = if (audio) "MP3" else "MP4", url = url, icon = null, category = "Telegram"))
                }
            }
        }.start()
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

    /** Un archivo .m3u/.json de lista enviado al chat: se descarga, se analiza y se elige un canal. */
    private fun openPlaylistFile(fileId: String) {
        Toast.makeText(this, R.string.tg_loading, Toast.LENGTH_SHORT).show()
        Thread {
            val r = TelegramBot.fileUrl(applicationContext, fileId)
            var streams: List<Stream> = emptyList()
            var error: String? = r.error
            val url = r.url
            if (url != null) {
                try {
                    val data = PlaylistRepository.parse(PlaylistRepository.downloadRaw(url))
                    streams = data.categories.flatMap { it.streams }.take(500)
                    if (streams.isEmpty()) error = getString(R.string.tg_playlist_empty)
                } catch (e: Exception) {
                    error = getString(R.string.tg_playlist_error)
                }
            }
            val found = streams
            val err = error
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (found.isEmpty()) {
                    Toast.makeText(this, getString(R.string.tg_file_error, err.orEmpty()), Toast.LENGTH_LONG).show()
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

    // -----------------------------------------------------------------

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
            holder.b.root.setOnClickListener { row.onClick() }
        }

        override fun getItemCount() = rows.size

        inner class VH(val b: ItemTelegramBinding) : RecyclerView.ViewHolder(b.root)
    }
}
