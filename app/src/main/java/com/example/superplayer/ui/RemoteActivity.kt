package com.example.superplayer.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ImageView
import coil.load
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.superplayer.R
import com.example.superplayer.data.EpgFormat
import com.example.superplayer.data.FavoritesStore
import com.example.superplayer.model.Stream
import com.example.superplayer.model.toJson
import com.example.superplayer.player.MultiViewActivity
import com.example.superplayer.remote.RemoteClient
import com.example.superplayer.remote.RemoteDevice
import com.example.superplayer.remote.RemoteServer
import org.json.JSONObject

/**
 * El móvil como mando de la tele: busca en la red las pantallas con la app
 * abierta (o se escribe su IP), pide el código que sale en la tele (menú ⋮ →
 * Control remoto…) y manda órdenes: canal +/-, pausa, retroceder/adelantar,
 * volumen y "enviar este canal a la tele". Ver remote/RemoteServer.
 */
class RemoteActivity : AppCompatActivity() {

    private var device: RemoteDevice? = null
    private lateinit var deviceButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var discovery: RemoteClient.Discovery? = null
    private val density by lazy { resources.displayMetrics.density }

    // Tarjeta "Ahora en la tele"
    private lateinit var cardPoster: ImageView
    private lateinit var cardChannel: TextView
    private lateinit var cardTitle: TextView
    private lateinit var cardMeta: TextView
    private lateinit var cardDesc: TextView
    private lateinit var cardProgress: ProgressBar
    private lateinit var cardTimes: TextView
    private lateinit var cardNext: TextView
    private lateinit var statusText: TextView
    private lateinit var volumeText: TextView

    private val statusRunnable = object : Runnable {
        override fun run() {
            pollStatus()
            handler.postDelayed(this, 2_000L)
        }
    }

    private fun dp(v: Int) = (v * density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        title = getString(R.string.remote_title)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.background_dark))
        }
        val header = TextView(this).apply {
            text = getString(R.string.remote_title)
            textSize = 20f
            setPadding(dp(16), dp(14), dp(16), dp(10))
            setTextColor(color(R.color.on_background))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(16))
        }
        scroll.addView(content)
        root.addView(header)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        com.example.superplayer.data.AccessControl.guardActivity(this, com.example.superplayer.data.AccessControl.S_REMOTE)
        applySystemBarInsets(top = header, bottom = scroll)

        deviceButton = button(getString(R.string.remote_choose_device), 1f, 48) { chooseDevice() }
        content.addView(row(deviceButton))

        // ---- Tarjeta: lo que se está viendo, con la guía ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color(R.color.surface_dark))
                cornerRadius = 16f * density
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(4), dp(8), dp(4), dp(8)) }
        }
        cardPoster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(108)).apply { rightMargin = dp(12) }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun tv(size: Float, colorId: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(color(colorId))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        cardChannel = tv(12f, R.color.primary, true)
        cardTitle = tv(17f, R.color.on_background, true)
        cardMeta = tv(12f, R.color.on_surface_muted)
        cardDesc = tv(13f, R.color.on_background).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) }
        cardProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) }
        }
        cardTimes = tv(12f, R.color.on_surface_muted)
        cardNext = tv(12f, R.color.on_surface_muted).apply { setPadding(0, dp(4), 0, 0) }
        statusText = tv(13f, R.color.on_surface_muted)
        for (v in listOf(cardChannel, cardTitle, cardMeta, cardDesc, cardProgress, cardTimes, cardNext, statusText)) col.addView(v)
        card.addView(cardPoster)
        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(card)

        // ---- Reproducción ----
        content.addView(row(
            button(getString(R.string.remote_prev), 1f, 64, repeat = true) { send("/cmd?c=prev") },
            button(getString(R.string.remote_playpause), 1f, 64) { send("/cmd?c=playpause") },
            button(getString(R.string.remote_next), 1f, 64, repeat = true) { send("/cmd?c=next") }
        ))
        content.addView(row(
            button(getString(R.string.remote_back5m), 1f, 48, repeat = true) { send("/cmd?c=back5m") },
            button(getString(R.string.remote_back30), 1f, 48, repeat = true) { send("/cmd?c=back30") },
            button(getString(R.string.remote_fwd30), 1f, 48, repeat = true) { send("/cmd?c=fwd30") },
            button(getString(R.string.remote_fwd5m), 1f, 48, repeat = true) { send("/cmd?c=fwd5m") }
        ))

        // ---- Volumen ----
        volumeText = tv(13f, R.color.on_surface_muted).apply { gravity = android.view.Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        content.addView(volumeText)
        content.addView(row(
            button(getString(R.string.remote_vol_down), 1f, 56, repeat = true) { send("/cmd?c=voldown") },
            button(getString(R.string.remote_mute), 1f, 56) { send("/cmd?c=mute") },
            button(getString(R.string.remote_vol_up), 1f, 56, repeat = true) { send("/cmd?c=volup") }
        ))

        // ---- Cruceta: navegar por los menús de la tele ----
        content.addView(TextView(this).apply {
            text = getString(R.string.remote_pad_title)
            textSize = 13f
            setTextColor(color(R.color.on_surface_muted))
            setPadding(dp(4), dp(12), 0, dp(2))
        })
        content.addView(row(
            button(getString(R.string.remote_back), 1f, 60) { send("/cmd?c=key_back") },
            button("▲", 1f, 60, repeat = true) { send("/cmd?c=key_up") },
            button(getString(R.string.remote_menu), 1f, 60) { send("/cmd?c=key_menu") }
        ))
        content.addView(row(
            button("◀", 1f, 60, repeat = true) { send("/cmd?c=key_left") },
            button("OK", 1f, 60) { send("/cmd?c=key_ok") },
            button("▶", 1f, 60, repeat = true) { send("/cmd?c=key_right") }
        ))
        content.addView(row(
            button(getString(R.string.remote_home), 1f, 60) { send("/cmd?c=home") },
            button("▼", 1f, 60, repeat = true) { send("/cmd?c=key_down") },
            button(getString(R.string.remote_type), 1f, 60) { showTypeDialog() }
        ))

        // ---- Enviar canales ----
        content.addView(row(
            button(getString(R.string.remote_send_channel), 1f, 56) {
                val all = MultiViewActivity.pickerCategories.flatMap { it.streams }.distinctBy { it.id }
                showChannelSender(all, getString(R.string.remote_send_channel))
            },
            button(getString(R.string.remote_send_favorite), 1f, 56) {
                showChannelSender(FavoritesStore(this).getAllStreams(), getString(R.string.remote_send_favorite))
            }
        ))

        device = RemoteClient.lastDevice(this)
        updateDeviceUi()
        if (device == null) chooseDevice()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(statusRunnable)
        handler.post(statusRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        discovery?.stop()
        discovery = null
    }

    // -----------------------------------------------------------------
    // Piezas de pantalla
    // -----------------------------------------------------------------

    /** Botón del mando. Con [repeat], mantenerlo pulsado repite la orden (volumen, canales, flechas…). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun button(label: String, weight: Float = 1f, heightDp: Int = 64, repeat: Boolean = false, onClick: () -> Unit): Button {
        val margin = dp(4)
        return Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(heightDp), weight).apply { setMargins(margin, margin, margin, margin) }
            if (!repeat) {
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                }
            } else {
                val again = object : Runnable {
                    override fun run() {
                        onClick()
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        this@RemoteActivity.handler.postDelayed(this, 220L)
                    }
                }
                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onClick()
                            this@RemoteActivity.handler.postDelayed(again, 450L)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            this@RemoteActivity.handler.removeCallbacks(again)
                        }
                    }
                    true
                }
            }
        }
    }

    private fun row(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in buttons) addView(b)
    }

    private fun updateDeviceUi() {
        val d = device
        deviceButton.text = if (d == null) getString(R.string.remote_choose_device) else "📺 ${d.name}  ·  ${d.host}"
        if (d == null) statusText.text = getString(R.string.remote_no_device)
    }

    // -----------------------------------------------------------------
    // Órdenes
    // -----------------------------------------------------------------

    private fun send(path: String, body: String? = null, failMsg: Int = R.string.remote_not_playing) {
        val d = device
        if (d == null) {
            Toast.makeText(this, R.string.remote_no_device, Toast.LENGTH_SHORT).show()
            chooseDevice()
            return
        }
        Thread {
            val (code, text) = RemoteClient.call(d, path, body)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    code == 401 -> {
                        Toast.makeText(this, R.string.remote_wrong_pin, Toast.LENGTH_LONG).show()
                        askPin(d)
                    }
                    code < 0 -> Toast.makeText(this, R.string.remote_unreachable, Toast.LENGTH_LONG).show()
                    else -> {
                        val ok = try { JSONObject(text).optBoolean("ok", true) } catch (e: Exception) { true }
                        if (!ok) Toast.makeText(this, failMsg, Toast.LENGTH_SHORT).show()
                        // Se refresca pronto lo que se ve (volumen, canal nuevo…).
                        handler.postDelayed({ pollStatus() }, 500L)
                    }
                }
            }
        }.start()
    }

    private fun pollStatus() {
        val d = device ?: return
        Thread {
            val (code, text) = RemoteClient.call(d, "/status")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (code) {
                    200 -> showStatus(text)
                    401 -> showMessage(getString(R.string.remote_wrong_pin))
                    else -> showMessage(getString(R.string.remote_unreachable))
                }
            }
        }.start()
    }

    private fun showMessage(msg: String) {
        cardChannel.text = ""
        cardTitle.text = ""
        cardMeta.visibility = View.GONE
        cardDesc.visibility = View.GONE
        cardProgress.visibility = View.GONE
        cardTimes.visibility = View.GONE
        cardNext.visibility = View.GONE
        cardPoster.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = msg
    }

    private fun clock(millis: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))

    private fun showStatus(text: String) {
        try {
            val root = JSONObject(text)
            val vol = root.optJSONObject("volume")
            volumeText.text = if (vol == null) "" else {
                val pct = vol.optInt("pct")
                if (vol.optBoolean("muted")) getString(R.string.remote_vol_muted)
                else getString(if (vol.optBoolean("app")) R.string.remote_vol_app else R.string.remote_vol_level, pct)
            }
            val player = root.optJSONObject("player")
            if (player == null) {
                showMessage(getString(R.string.remote_idle))
                return
            }
            statusText.visibility = View.GONE
            val paused = !player.optBoolean("playing", true)
            cardChannel.text = (if (paused) "⏸ " else "▶ ") + player.optString("title")
            val epg = player.optJSONObject("epg")
            if (epg == null) {
                val now = player.optString("now")
                cardTitle.text = now.ifBlank { player.optString("title") }
                cardMeta.visibility = View.GONE
                cardDesc.visibility = View.GONE
                cardProgress.visibility = View.GONE
                cardTimes.visibility = View.GONE
                cardPoster.visibility = View.GONE
            } else {
                cardTitle.text = epg.optString("title")
                bind(cardMeta, listOf(epg.optString("meta"), epg.optString("category"), epg.optString("country"))
                    .filter { it.isNotBlank() }.joinToString("  ·  "))
                val extra = listOf(epg.optString("desc"),
                    epg.optString("cast").takeIf { it.isNotBlank() }?.let { getString(R.string.cine_cast) + ": " + it }.orEmpty())
                    .filter { it.isNotBlank() }.joinToString("\n")
                cardDesc.text = EpgFormat.spanned(extra, (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                cardDesc.visibility = if (extra.isBlank()) View.GONE else View.VISIBLE
                val start = epg.optLong("start")
                val stop = epg.optLong("stop")
                val tvNow = player.optLong("tvNow", System.currentTimeMillis())
                if (stop > start) {
                    cardProgress.progress = (((tvNow - start).toDouble() / (stop - start)) * 1000).toInt().coerceIn(0, 1000)
                    cardProgress.visibility = View.VISIBLE
                    val left = ((stop - tvNow) / 60_000L).coerceAtLeast(0)
                    cardTimes.text = "${clock(start)}  –  ${clock(stop)}   ·   " + getString(R.string.remote_minutes_left, left.toInt())
                    cardTimes.visibility = View.VISIBLE
                } else {
                    cardProgress.visibility = View.GONE
                    cardTimes.visibility = View.GONE
                }
                val poster = epg.optString("poster")
                if (poster.isBlank()) cardPoster.visibility = View.GONE
                else {
                    cardPoster.visibility = View.VISIBLE
                    if (cardPoster.tag != poster) {
                        cardPoster.tag = poster
                        cardPoster.load(poster) { listener(onError = { _, _ -> cardPoster.visibility = View.GONE }) }
                    }
                }
            }
            val later = player.optJSONArray("later")
            val lines = ArrayList<String>()
            if (later != null) {
                for (i in 0 until later.length()) {
                    val o = later.getJSONObject(i)
                    lines.add("${clock(o.optLong("start"))}  ${o.optString("title")}")
                }
            }
            bind(cardNext, if (lines.isEmpty()) "" else getString(R.string.remote_next_programs) + "\n" + lines.joinToString("\n"))
        } catch (e: Exception) {
            showMessage("")
        }
    }

    private fun bind(view: TextView, text: String) {
        view.text = text
        view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    /** Escribir en la tele: el texto va al campo que tenga el foco (buscador, dirección web…). */
    private fun showTypeDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.remote_type_hint)
        input.isSingleLine = true
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_type)
            .setMessage(R.string.remote_type_message)
            .setView(box)
            .setPositiveButton(R.string.remote_type_send) { _, _ ->
                send("/text", JSONObject().put("text", input.text.toString()).put("submit", true).toString(), R.string.remote_type_fail)
            }
            .setNeutralButton(R.string.remote_type_only) { _, _ ->
                send("/text", JSONObject().put("text", input.text.toString()).put("submit", false).toString(), R.string.remote_type_fail)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendStream(stream: Stream) {
        send("/open", stream.toJson())
        Toast.makeText(this, getString(R.string.remote_sent, stream.name), Toast.LENGTH_SHORT).show()
    }

    // -----------------------------------------------------------------
    // Elegir tele
    // -----------------------------------------------------------------

    private fun chooseDevice() {
        val names = ArrayList<String>()
        val devices = ArrayList<RemoteDevice>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        fun add(d: RemoteDevice) {
            if (devices.none { it.key == d.key }) {
                devices.add(d)
                names.add("📺 ${d.name}  (${d.host})")
                adapter.notifyDataSetChanged()
            }
        }
        for (saved in RemoteClient.savedDevices(this)) add(saved)
        discovery?.stop()
        val disc = RemoteClient.Discovery(this) { found ->
            runOnUiThread {
                val saved = RemoteClient.savedDevices(this).firstOrNull { it.key == found.key }
                add(saved ?: found)
            }
        }
        discovery = disc
        disc.start()
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_choose_title)
            .setAdapter(adapter) { _, which -> selectDevice(devices[which]) }
            .setNeutralButton(R.string.remote_add_ip) { _, _ -> showAddByIpDialog() }
            .setNegativeButton(R.string.channel_check_close, null)
            .setOnDismissListener { disc.stop() }
            .show()
    }

    private fun selectDevice(d: RemoteDevice) {
        if (d.pin == null) {
            askPin(d)
        } else {
            device = d
            RemoteClient.saveDevice(this, d)
            updateDeviceUi()
            pollStatus()
        }
    }

    private fun showAddByIpDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "192.168.1.20"
        val box = FrameLayout(this)
        val pad = (20 * density).toInt()
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_add_ip)
            .setMessage(R.string.remote_add_ip_message)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val host = text.substringBefore(':')
                val port = text.substringAfter(':', "").toIntOrNull() ?: RemoteServer.DEFAULT_PORT
                askPin(RemoteDevice(host, host, port, null))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askPin(d: RemoteDevice) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "0000"
        val box = FrameLayout(this)
        val pad = (20 * density).toInt()
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remote_pin_title, d.name))
            .setMessage(R.string.remote_pin_message)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val candidate = d.copy(pin = input.text.toString().trim())
                Thread {
                    val (code, _) = RemoteClient.call(candidate, "/status")
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        when (code) {
                            200 -> {
                                device = candidate
                                RemoteClient.saveDevice(this, candidate)
                                updateDeviceUi()
                                pollStatus()
                            }
                            401 -> Toast.makeText(this, R.string.remote_wrong_pin, Toast.LENGTH_LONG).show()
                            else -> Toast.makeText(this, R.string.remote_unreachable, Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -----------------------------------------------------------------
    // Enviar un canal a la tele
    // -----------------------------------------------------------------

    private fun showChannelSender(source: List<Stream>, dialogTitle: String) {
        if (source.isEmpty()) {
            Toast.makeText(this, R.string.remote_no_channels, Toast.LENGTH_LONG).show()
            return
        }
        val input = EditText(this)
        input.hint = getString(R.string.search_hint)
        input.isSingleLine = true
        val shown = ArrayList<Stream>()
        val labels = ArrayList<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val list = ListView(this)
        list.adapter = adapter

        fun refilter(query: String) {
            shown.clear()
            labels.clear()
            val q = query.trim().lowercase()
            for (s in source) {
                if (q.isEmpty() || s.name.lowercase().contains(q)) {
                    shown.add(s)
                    labels.add(s.name)
                    if (shown.size >= 200) break
                }
            }
            adapter.notifyDataSetChanged()
        }
        refilter("")
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refilter(s?.toString().orEmpty())
        })

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * density).toInt()))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setView(box)
            .setNegativeButton(R.string.channel_check_close, null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            shown.getOrNull(position)?.let { sendStream(it) }
        }
        dialog.show()
    }
}
