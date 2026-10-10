package com.example.superplayer.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.GridLayoutManager
import com.example.superplayer.R
import com.example.superplayer.data.ChannelOverrides
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.data.FavoritesStore
import com.example.superplayer.databinding.ActivityNowLiveBinding
import com.example.superplayer.model.Stream
import com.example.superplayer.player.MultiViewActivity
import com.example.superplayer.player.PlayerActivity
import com.example.superplayer.player.StreamMediaExtras
import com.example.superplayer.player.StreamMediaSourceFactory
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Ahora en directo": un mosaico con tus canales favoritos y lo que emiten
 * ahora mismo (programa, cuánto lleva y el siguiente, de la guía EPG), con
 * una vista previa en vivo y SIN sonido del canal seleccionado arriba.
 * Tocar una tarjeta (o enfocarla con el mando) pone su vista previa; tocar
 * la ya seleccionada, o la propia vista previa, abre el canal entero.
 *
 * Solo hay UNA vista previa a la vez (un solo decodificador): varias a la vez
 * no funcionan en muchas teles. Si no tienes favoritos, enseña los primeros
 * canales de la lista cargada que tengan guía.
 */
@OptIn(UnstableApi::class)
class NowLiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowLiveBinding
    private var channels: List<Stream> = emptyList()
    private var selectedId: String? = null
    private var adapter: NowLiveAdapter? = null
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPreview: Runnable? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            // Las barras de progreso avanzan y los programas cambian: se repinta cada medio minuto.
            adapter?.notifyDataSetChanged()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNowLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.nowlive_title)
        applySystemBarInsets(top = binding.toolbar, bottom = binding.recyclerView)

        channels = loadChannels()
        binding.emptyView.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE

        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val span = if (isTv) 3 else if (resources.configuration.screenWidthDp >= 600) 3 else 2
        adapter = NowLiveAdapter(
            channels,
            selectedIdProvider = { selectedId },
            onSelect = { select(it) },
            onOpen = { open(it) }
        )
        binding.recyclerView.layoutManager = GridLayoutManager(this, span)
        binding.recyclerView.adapter = adapter
        binding.previewBox.setOnClickListener { channels.firstOrNull { it.id == selectedId }?.let { open(it) } }
    }

    /** Favoritos (en su orden, con los ocultos fuera y los nombres propios); si no hay, los primeros canales con guía de la lista cargada. */
    private fun loadChannels(): List<Stream> {
        val favs = FavoritesStore(this).getAllStreams()
            .filter { !ChannelOverrides.isHidden(this, it.id) }
            .map { ChannelOverrides.renamed(this, it) }
        if (favs.isNotEmpty()) return favs
        return MultiViewActivity.pickerCategories
            .flatMap { it.streams }
            .distinctBy { it.id }
            .filter { EpgRepository.hasData(it.tvgId) }
            .take(30)
    }

    override fun onStart() {
        super.onStart()
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(StreamMediaSourceFactory(this))
            .build()
        exo.volume = 0f
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setMaxVideoSize(854, 480).build()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                binding.previewHint.text = getString(R.string.nowlive_preview_error)
                binding.previewHint.visibility = View.VISIBLE
            }
        })
        player = exo
        binding.previewPlayer.player = exo
        channels.firstOrNull { it.id == selectedId }?.let { startPreview(it) }
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 30_000L)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        binding.previewPlayer.player = null
    }

    private fun select(stream: Stream) {
        val previous = selectedId
        selectedId = stream.id
        // Solo se repintan las dos tarjetas afectadas (y fuera del layout en curso): así el foco del mando no se pierde.
        binding.recyclerView.post {
            val a = adapter ?: return@post
            channels.indexOfFirst { it.id == previous }.takeIf { it >= 0 }?.let { a.notifyItemChanged(it) }
            channels.indexOfFirst { it.id == stream.id }.takeIf { it >= 0 }?.let { a.notifyItemChanged(it) }
        }
        binding.previewName.text = stream.name
        binding.previewName.visibility = View.VISIBLE
        // Un pequeño retardo: al recorrer las tarjetas con el mando no se abre una vista previa por cada una que se pasa.
        pendingPreview?.let { handler.removeCallbacks(it) }
        val task = Runnable { startPreview(stream) }
        pendingPreview = task
        handler.postDelayed(task, 700L)
    }

    private fun startPreview(stream: Stream) {
        if (player == null) return
        binding.previewHint.visibility = View.GONE
        val tokenUrl = stream.tokenUrl
        if (tokenUrl.isNullOrBlank()) {
            play(stream)
            return
        }
        Thread {
            val token = try {
                val c = URL(tokenUrl).openConnection() as HttpURLConnection
                try {
                    stream.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                    c.connectTimeout = 10_000
                    c.readTimeout = 10_000
                    c.inputStream.bufferedReader().readText().trim()
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || selectedId != stream.id) return@runOnUiThread
                play(if (token.isNullOrBlank()) stream else stream.copy(url = stream.url.replace("{token}", token)))
            }
        }.start()
    }

    private fun play(stream: Stream) {
        val exo = player ?: return
        val mime = when (stream.type.trim().uppercase()) {
            "DASH", "MPD" -> MimeTypes.APPLICATION_MPD
            "HLS", "M3U8" -> MimeTypes.APPLICATION_M3U8
            else -> {
                val path = Uri.parse(stream.url).path.orEmpty().lowercase()
                when {
                    path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
                    path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                    else -> null
                }
            }
        }
        val item = MediaItem.Builder()
            .setMediaId(stream.id)
            .setUri(stream.url)
            .setMimeType(mime)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(stream.name).setExtras(StreamMediaExtras.build(stream)).build()
            )
            .build()
        exo.setMediaItem(item)
        exo.playWhenReady = true
        exo.prepare()
        exo.volume = 0f
    }

    private fun open(stream: Stream) {
        PlayerActivity.pendingStream = stream
        PlayerActivity.pendingChannelList = channels
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
