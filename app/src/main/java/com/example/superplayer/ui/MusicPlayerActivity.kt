package com.example.superplayer.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.superplayer.R
import com.example.superplayer.player.PlaybackService
import com.example.superplayer.telegram.MusicArchive
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * Pantalla de música para un álbum sacado de un archivo comprimido de Telegram (ver [MusicArchive]):
 * portada, título, barra de progreso, anterior/play/siguiente, aleatorio, repetir, velocidad,
 * temporizador y la lista de pistas. Suena a través de PlaybackService (sigue con la pantalla apagada).
 */
class MusicPlayerActivity : AppCompatActivity() {
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var album: MusicArchive.Album? = null
    private val ui = Handler(Looper.getMainLooper())
    private var seeking = false
    private var sleepRunnable: Runnable? = null

    private lateinit var cover: ImageView
    private lateinit var titleView: TextView
    private lateinit var sub: TextView
    private lateinit var seek: SeekBar
    private lateinit var tPos: TextView
    private lateinit var tDur: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnShuffle: Button
    private lateinit var btnRepeat: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnSleep: Button
    private lateinit var list: RecyclerView
    private val adapter = TrackAdapter()
    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun mkButton(text: String, size: Float = 18f): Button = Button(this).apply {
        this.text = text
        textSize = size
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xFF2A2A2A.toInt()) }
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(14), dp(6), dp(14), dp(6))
        isFocusable = true
    }

    private fun timeText(ms: Long): String {
        if (ms < 0) return "0:00"
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = intent.getStringExtra(EXTRA_DIR)?.let { File(it) }
        album = dir?.let { MusicArchive.cached(it) }
        val alb = album
        if (alb == null) {
            Toast.makeText(this, R.string.music_err_open, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        titleView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; maxLines = 2 }
        sub = TextView(this).apply { setTextColor(0xFFB0B0B0.toInt()); textSize = 14f; gravity = Gravity.CENTER; maxLines = 1 }
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1E1E1E.toInt())
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF1E1E1E.toInt()) }
        }
        if (alb.cover != null) cover.load(File(alb.cover)) else cover.setImageResource(android.R.drawable.ic_media_play)
        if (alb.cover == null) cover.scaleType = ImageView.ScaleType.CENTER_INSIDE

        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val d = controller?.duration ?: 0L
                        if (d > 0) tPos.text = timeText(d * p / 1000)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    seeking = false
                    val c = controller ?: return
                    val d = c.duration
                    if (d > 0) c.seekTo(d * (s?.progress ?: 0) / 1000)
                }
            })
        }
        tPos = TextView(this).apply { setTextColor(0xFFB0B0B0.toInt()); textSize = 12f; text = "0:00" }
        tDur = TextView(this).apply { setTextColor(0xFFB0B0B0.toInt()); textSize = 12f; text = "0:00" }
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tPos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tDur)
        }

        val prev = mkButton("⏮", 22f).apply { setOnClickListener { prevTrack() } }
        btnPlay = mkButton("▶", 26f).apply { setOnClickListener { togglePlay() } }
        val next = mkButton("⏭", 22f).apply { setOnClickListener { controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } } }
        val back = mkButton("⏪", 18f).apply { setOnClickListener { controller?.let { it.seekTo(maxOf(0L, it.currentPosition - 10_000)) } } }
        val fwd = mkButton("⏩", 18f).apply { setOnClickListener { controller?.let { it.seekTo(it.currentPosition + 10_000) } } }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            for (b in listOf(back, prev, btnPlay, next, fwd)) {
                addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        }
        btnShuffle = mkButton("🔀", 16f).apply { setOnClickListener { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled; refreshModes() } } }
        btnRepeat = mkButton("🔁", 16f).apply {
            setOnClickListener {
                controller?.let {
                    it.repeatMode = when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    refreshModes()
                }
            }
        }
        btnSpeed = mkButton("1x", 14f).apply {
            setOnClickListener {
                val c = controller ?: return@setOnClickListener
                val i = speeds.indexOfFirst { kotlin.math.abs(it - c.playbackParameters.speed) < 0.01f }
                c.setPlaybackSpeed(speeds[(i + 1) % speeds.size])
                refreshModes()
            }
        }
        btnSleep = mkButton("⏲", 16f).apply { setOnClickListener { showSleepDialog() } }
        val opts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            for (b in listOf(btnShuffle, btnRepeat, btnSpeed, btnSleep)) {
                addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        }

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MusicPlayerActivity)
            adapter = this@MusicPlayerActivity.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        adapter.tracks = alb.tracks

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            val side = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels * 2 / 5, dp(280))
            addView(cover, LinearLayout.LayoutParams(side, side))
            addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(times, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(main, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(opts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            fitsSystemWindows = true
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        setTitle(alb.name)
        titleView.text = alb.name
        sub.text = getString(R.string.music_tracks_count, alb.tracks.size)
        connect()
    }

    private fun connect() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            try {
                onConnected(f.get())
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.player_error, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
        override fun onPlaybackStateChanged(state: Int) = refresh()
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Toast.makeText(this@MusicPlayerActivity, getString(R.string.player_error, error.message ?: ""), Toast.LENGTH_SHORT).show()
            controller?.let { if (it.hasNextMediaItem()) { it.seekToNextMediaItem(); it.prepare(); it.play() } }
        }
    }

    private fun onConnected(c: MediaController) {
        controller = c
        val alb = album ?: return
        c.addListener(listener)
        val firstId = alb.tracks.first().path
        val same = c.mediaItemCount == alb.tracks.size && c.getMediaItemAt(0).mediaId == firstId
        if (!same) {
            val art = alb.cover?.let { Uri.fromFile(File(it)) }
            val items = alb.tracks.map { t ->
                MediaItem.Builder()
                    .setMediaId(t.path)
                    .setUri(Uri.fromFile(File(t.path)))
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(t.title).setArtist(alb.name).setAlbumTitle(alb.name).setArtworkUri(art).build()
                    )
                    .build()
            }
            c.setMediaItems(items, 0, 0L)
            c.prepare()
            c.play()
        }
        refresh()
        refreshModes()
        ui.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val c = controller
            if (c != null && !seeking) {
                val d = c.duration
                val p = c.currentPosition
                if (d > 0) {
                    seek.progress = (p * 1000 / d).toInt().coerceIn(0, 1000)
                    tDur.text = timeText(d)
                }
                tPos.text = timeText(p)
            }
            ui.postDelayed(this, 500)
        }
    }

    private fun refresh() {
        val c = controller ?: return
        btnPlay.text = if (c.isPlaying || c.playWhenReady && c.playbackState == Player.STATE_BUFFERING) "⏸" else "▶"
        val idx = c.currentMediaItemIndex
        val alb = album ?: return
        if (idx in alb.tracks.indices) {
            titleView.text = alb.tracks[idx].title
            sub.text = getString(R.string.music_track_of, idx + 1, alb.tracks.size, alb.name)
        }
        if (adapter.current != idx) {
            adapter.current = idx
            adapter.notifyDataSetChanged()
            list.scrollToPosition(maxOf(0, idx - 1))
        }
    }

    private fun refreshModes() {
        val c = controller ?: return
        btnShuffle.alpha = if (c.shuffleModeEnabled) 1f else 0.45f
        btnRepeat.text = if (c.repeatMode == Player.REPEAT_MODE_ONE) "🔂" else "🔁"
        btnRepeat.alpha = if (c.repeatMode == Player.REPEAT_MODE_OFF) 0.45f else 1f
        val sp = c.playbackParameters.speed
        btnSpeed.text = (if (sp == sp.toInt().toFloat()) sp.toInt().toString() else sp.toString()) + "x"
        btnSleep.alpha = if (sleepRunnable != null) 1f else 0.45f
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0L)
            c.play()
        }
    }

    private fun prevTrack() {
        val c = controller ?: return
        if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    private fun showSleepDialog() {
        val mins = intArrayOf(0, 15, 30, 45, 60, 90)
        val labels = mins.map { if (it == 0) getString(R.string.music_sleep_off) else getString(R.string.music_sleep_min, it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.music_sleep)
            .setItems(labels) { _, i ->
                sleepRunnable?.let { ui.removeCallbacks(it) }
                sleepRunnable = null
                if (mins[i] > 0) {
                    val r = Runnable {
                        sleepRunnable = null
                        controller?.pause()
                        refreshModes()
                        Toast.makeText(this, R.string.sleep_timer_fired, Toast.LENGTH_LONG).show()
                    }
                    sleepRunnable = r
                    ui.postDelayed(r, mins[i] * 60_000L)
                    Toast.makeText(this, getString(R.string.music_sleep_set, mins[i]), Toast.LENGTH_SHORT).show()
                }
                refreshModes()
            }.show()
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        sleepRunnable?.let { ui.removeCallbacks(it) }
        controller?.removeListener(listener)
        if (isFinishing) {
            // Al salir de la pantalla de música se acaba la música.
            try { controller?.stop(); controller?.clearMediaItems() } catch (e: Exception) { }
        }
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }

    private inner class TrackAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var tracks: List<MusicArchive.Track> = emptyList()
        var current = -1
        override fun getItemCount() = tracks.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context).apply {
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val tv = h.itemView as TextView
            val on = position == current
            tv.text = String.format("%02d   %s%s", position + 1, if (on) "♪ " else "", tracks[position].title)
            tv.setTextColor(if (on) 0xFF4FC3F7.toInt() else Color.WHITE)
            tv.setBackgroundColor(if (on) 0xFF1E2A33.toInt() else Color.TRANSPARENT)
            tv.setOnClickListener {
                controller?.let { c -> c.seekTo(position, 0L); c.play() }
            }
        }
    }

    companion object {
        const val EXTRA_DIR = "dir"
        fun intent(ctx: Context, dir: File) = Intent(ctx, MusicPlayerActivity::class.java).putExtra(EXTRA_DIR, dir.absolutePath)
    }
}
