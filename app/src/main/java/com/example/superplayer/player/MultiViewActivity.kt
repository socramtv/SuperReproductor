package com.example.superplayer.player

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.text.Editable
import android.text.TextWatcher
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
import androidx.media3.exoplayer.DefaultRenderersFactory
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
    private val handler = Handler(Looper.getMainLooper())
    private val decoderRetries = IntArray(2)
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
        // El aviso de colores solo hace falta con el mando de TV; en el móvil sobra.
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) {
            binding.multiHint.visibility = android.view.View.GONE
        }
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
            decoderRetries[i] = 0
            // Dos vídeos a la vez exigen dos decodificadores: en algunos aparatos (Google TV...) solo hay
            // uno de hardware libre. Con el respaldo activado, si el de hardware no se puede abrir se usa otro
            // (incluido el de software); y con la resolución limitada (cada recuadro es pequeño) se gasta menos.
            val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            val player = ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(StreamMediaSourceFactory(this))
                .build()
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setMaxVideoSize(1280, 720)
                .build()
            players[i] = player
            views[i].player = player
            val index = i
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    handlePlayerError(index, error)
                }
            })
        }
        // El primero arranca ya y el segundo un momento después: así los decodificadores se abren uno tras otro.
        streams[0]?.let { startPane(0, it) }
        handler.postDelayed({
            if (players[1] != null) streams[1]?.let { startPane(1, it) }
        }, SECOND_PANE_DELAY_MS)
        applyAudio()
    }

    /**
     * Error de un recuadro. Si es de decodificador (el aparato no puede abrir otro más), se reintenta
     * bajando la resolución; si aun así falla, se avisa de que este aparato no da para dos vídeos a la vez.
     */
    private fun handlePlayerError(pane: Int, error: PlaybackException) {
        val name = streams[pane]?.name ?: ""
        val decoderError = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
        val player = players[pane] ?: return
        if (decoderError && decoderRetries[pane] < 2) {
            decoderRetries[pane]++
            val maxHeight = if (decoderRetries[pane] == 1) 540 else 360
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setMaxVideoSize(maxHeight * 16 / 9, maxHeight)
                .build()
            handler.postDelayed({
                val p = players[pane] ?: return@postDelayed
                p.prepare()
                p.playWhenReady = true
            }, 700L)
            return
        }
        val message = if (decoderError) {
            getString(R.string.multiview_decoder_error, name)
        } else {
            getString(R.string.player_error, "$name: ${error.errorCodeName}")
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
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
        // El canal que no suena lleva el altavoz tachado (mute).
        binding.audioA.setImageResource(if (audioPane == 0) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
        binding.audioB.setImageResource(if (audioPane == 1) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
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

    /**
     * Elegir canal para un recuadro: primero el grupo (todos los canales de la
     * lista cargada, favoritos, la categoría de origen o cualquier categoría)
     * y luego el canal, con buscador.
     */
    private fun showPicker(pane: Int) {
        val groups = ArrayList<Pair<String, List<Stream>>>()
        val all = pickerCategories.flatMap { it.streams }.distinctBy { it.id }
        if (all.isNotEmpty()) groups.add(getString(R.string.multiview_all_channels) to all)
        val favs = FavoritesStore(this).getAllStreams()
        if (favs.isNotEmpty()) groups.add(getString(R.string.favorites_category_name) to favs)
        if (candidates.size > 1) groups.add(getString(R.string.multiview_same_group) to candidates)
        for (category in pickerCategories) {
            if (category.streams.isNotEmpty()) groups.add(category.name to category.streams)
        }
        if (groups.size == 1) {
            showChannelPicker(pane, groups[0].first, groups[0].second)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.multiview_pick_title)
            .setItems(groups.map { it.first }.toTypedArray()) { _, which ->
                showChannelPicker(pane, groups[which].first, groups[which].second)
            }
            .show()
    }

    private fun showChannelPicker(pane: Int, title: String, source: List<Stream>) {
        val input = EditText(this)
        input.hint = getString(R.string.search_hint)
        input.isSingleLine = true
        var shown: List<Stream> = source
        val labels = ArrayList<String>(source.map { it.name })
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val list = ListView(this)
        list.adapter = adapter
        list.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.5f).toInt()
        )
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty().trim()
                shown = if (query.isEmpty()) source else source.filter { it.name.contains(query, ignoreCase = true) }
                labels.clear()
                labels.addAll(shown.map { it.name })
                adapter.notifyDataSetChanged()
            }
        })
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, 0, pad, 0)
        container.addView(input)
        container.addView(list)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            val chosen = shown.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            streams[pane] = chosen
            decoderRetries[pane] = 0
            if (pane == 0) binding.nameA.text = chosen.name else binding.nameB.text = chosen.name
            startPane(pane, chosen)
        }
        dialog.show()
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
        private const val SECOND_PANE_DELAY_MS = 1200L

        var pendingCandidates: List<Stream> = emptyList()

        /** Todas las categorías de la lista cargada (las pone MainActivity), para poder elegir cualquier canal. */
        var pickerCategories: List<com.example.superplayer.model.Category> = emptyList()
    }
}
