package com.example.superplayer.player

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.superplayer.R
import com.example.superplayer.data.FavoritesStore
import com.example.superplayer.databinding.ActivityMultiviewBinding
import com.example.superplayer.model.Stream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Multivista: dos canales a la vez, uno al lado del otro (en vertical, uno
 * encima del otro). Solo suena uno (el que tiene el marco de color); tocar
 * el otro le pasa el sonido. El botón ⇅ de cada recuadro cambia su canal.
 *
 * Usa dos ExoPlayer propios de esta pantalla (no el del servicio de
 * reproducción): al salir se liberan, no hay segundo plano. Se abre desde el
 * botón de multivista del reproductor (ver PlayerActivity), con el canal que
 * se estaba viendo en el primer recuadro.
 */
@OptIn(UnstableApi::class)
class MultiViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiviewBinding
    private val streams = arrayOfNulls<Stream>(2)
    private val players = arrayOfNulls<ExoPlayer>(2)
    private var audioPane = 0
    /** Recuadro cuyos botones tienen el foco del mando (-1 = ninguno). */
    private var focusedPane = -1
    private var candidates: List<Stream> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsetsCompat.Type.systemBars())
        }

        val first = pendingFirst
        if (first == null) {
            finish()
            return
        }
        var list = pendingCandidates.filter { it.id.isNotBlank() }
        if (list.size < 2) {
            // Sin una categoría con varios canales: se usan los favoritos para elegir.
            val favs = FavoritesStore(this).getAllStreams()
            list = (listOf(first) + favs).distinctBy { it.id }
        }
        candidates = list
        val second = candidates.firstOrNull { it.id != first.id }
        if (second == null) {
            Toast.makeText(this, R.string.multiview_no_channels, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // El segundo, el siguiente de la lista al que se estaba viendo.
        val idx = candidates.indexOfFirst { it.id == first.id }
        val next = if (idx >= 0 && candidates.size > 1) candidates[(idx + 1) % candidates.size] else second
        streams[0] = first
        streams[1] = if (next.id != first.id) next else second

        binding.paneA.setOnClickListener { setAudioPane(0) }
        binding.paneB.setOnClickListener { setAudioPane(1) }
        binding.audioA.setOnClickListener { setAudioPane(0) }
        binding.audioB.setOnClickListener { setAudioPane(1) }
        // Recuadro con el foco del mando (aro rojo): el canal sobre el que vas a actuar.
        for ((pane, buttons) in listOf(0 to listOf(binding.audioA, binding.changeA), 1 to listOf(binding.audioB, binding.changeB))) {
            for (button in buttons) {
                button.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) focusedPane = pane else if (focusedPane == pane) focusedPane = -1
                    updateFrames()
                }
            }
        }
        binding.changeA.setOnClickListener { showPicker(0) }
        binding.changeB.setOnClickListener { showPicker(1) }
        binding.closeMulti.setOnClickListener { finish() }
        applyOrientation()
        updateFrames()
        binding.nameA.text = streams[0]?.name
        binding.nameB.text = streams[1]?.name
        // Con el mando: el foco empieza en "cambiar canal" del primer recuadro.
        binding.changeA.requestFocus()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    /** Apaisado: uno al lado del otro. Vertical: uno encima del otro. */
    private fun applyOrientation() {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.multiRoot.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (pane in listOf(binding.paneA, binding.paneB)) {
            val lp = pane.layoutParams as LinearLayout.LayoutParams
            if (portrait) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = 0
            } else {
                lp.width = 0
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            lp.weight = 1f
            pane.layoutParams = lp
        }
    }

    override fun onStart() {
        super.onStart()
        if (streams[0] == null) return
        val views = listOf<PlayerView>(binding.playerA, binding.playerB)
        for (i in 0..1) {
            val player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(StreamMediaSourceFactory(this))
                .build()
            players[i] = player
            views[i].player = player
            val index = i
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val name = streams[index]?.name ?: ""
                    Toast.makeText(
                        this@MultiViewActivity,
                        getString(R.string.player_error, "$name: ${error.errorCodeName}"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
            streams[i]?.let { startPane(i, it) }
        }
        applyAudio()
    }

    override fun onStop() {
        super.onStop()
        for (i in 0..1) {
            players[i]?.release()
            players[i] = null
        }
        binding.playerA.player = null
        binding.playerB.player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            pendingFirst = null
            pendingCandidates = emptyList()
        }
    }

    private fun setAudioPane(pane: Int) {
        audioPane = pane
        applyAudio()
        updateFrames()
        Toast.makeText(
            this,
            getString(R.string.multiview_audio_on, streams[pane]?.name ?: ""),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyAudio() {
        for (i in 0..1) players[i]?.volume = if (i == audioPane) 1f else 0f
        updateFrames()
    }

    private fun updateFrames() {
        binding.frameA.setBackgroundResource(frameFor(0))
        binding.frameB.setBackgroundResource(frameFor(1))
    }

    /** Naranja = el que suena; rojo = el que tiene el foco del mando; los dos a la vez = rojo por fuera y naranja por dentro. */
    private fun frameFor(pane: Int): Int {
        val hasAudio = pane == audioPane
        val hasFocus = pane == focusedPane
        return when {
            hasFocus && hasAudio -> R.drawable.bg_multiview_focus_active
            hasFocus -> R.drawable.bg_multiview_focus
            hasAudio -> R.drawable.bg_multiview_active
            else -> R.drawable.bg_multiview_idle
        }
    }

    private fun showPicker(pane: Int) {
        val names = candidates.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.multiview_pick_title)
            .setItems(names) { _, which ->
                val chosen = candidates[which]
                streams[pane] = chosen
                if (pane == 0) binding.nameA.text = chosen.name else binding.nameB.text = chosen.name
                startPane(pane, chosen)
            }
            .show()
    }

    /** Pone [stream] a reproducir en el recuadro [pane] (resolviendo antes su token, si lo tiene). */
    private fun startPane(pane: Int, stream: Stream) {
        val tokenUrl = stream.tokenUrl
        if (tokenUrl.isNullOrBlank()) {
            play(pane, stream)
            return
        }
        Thread {
            val token = try {
                fetchToken(tokenUrl, stream.headers)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (streams[pane]?.id != stream.id) return@runOnUiThread // mientras tanto se eligió otro canal
                play(pane, if (token.isNullOrBlank()) stream else stream.copy(url = stream.url.replace("{token}", token)))
            }
        }.start()
    }

    private fun play(pane: Int, stream: Stream) {
        val player = players[pane] ?: return
        val item = MediaItem.Builder()
            .setMediaId(stream.id)
            .setUri(stream.url)
            .setMimeType(guessMimeType(stream))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(stream.name)
                    .setExtras(StreamMediaExtras.build(stream))
                    .build()
            )
            .build()
        player.setMediaItem(item)
        player.playWhenReady = true
        player.prepare()
        player.volume = if (pane == audioPane) 1f else 0f
    }

    private fun fetchToken(tokenUrl: String, headers: Map<String, String>): String {
        val connection = URL(tokenUrl).openConnection() as HttpURLConnection
        return try {
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.inputStream.bufferedReader().readText().trim()
        } finally {
            connection.disconnect()
        }
    }

    private fun guessMimeType(stream: Stream): String? {
        val explicit = when (stream.type.trim().uppercase()) {
            "DASH", "MPD" -> MimeTypes.APPLICATION_MPD
            "HLS", "M3U8" -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
        if (explicit != null) return explicit
        val path = Uri.parse(stream.url).path.orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    companion object {
        /** El canal que se estaba viendo (va al primer recuadro) y los canales entre los que elegir. */
        var pendingFirst: Stream? = null
        var pendingCandidates: List<Stream> = emptyList()
    }
}
