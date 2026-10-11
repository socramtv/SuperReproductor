package com.example.superplayer.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.superplayer.R
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
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var discovery: RemoteClient.Discovery? = null
    private val density by lazy { resources.displayMetrics.density }

    private val statusRunnable = object : Runnable {
        override fun run() {
            pollStatus()
            handler.postDelayed(this, 3_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        title = getString(R.string.remote_title)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@RemoteActivity, R.color.background_dark))
        }
        val header = TextView(this).apply {
            text = getString(R.string.remote_title)
            textSize = 20f
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            setTextColor(ContextCompat.getColor(this@RemoteActivity, R.color.on_background))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), (12 * density).toInt())
        }
        scroll.addView(content)
        root.addView(header)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        applySystemBarInsets(top = header, bottom = scroll)

        deviceButton = button(getString(R.string.remote_choose_device), 1f) { chooseDevice() }
        content.addView(row(deviceButton))
        statusText = TextView(this).apply {
            textSize = 14f
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (12 * density).toInt())
            setTextColor(ContextCompat.getColor(this@RemoteActivity, R.color.on_surface_muted))
        }
        content.addView(statusText)

        content.addView(
            row(
                button(getString(R.string.remote_prev)) { send("/cmd?c=prev") },
                button(getString(R.string.remote_next)) { send("/cmd?c=next") }
            )
        )
        content.addView(
            row(
                button(getString(R.string.remote_back30)) { send("/cmd?c=back30") },
                button(getString(R.string.remote_playpause)) { send("/cmd?c=playpause") },
                button(getString(R.string.remote_fwd30)) { send("/cmd?c=fwd30") }
            )
        )
        content.addView(
            row(
                button(getString(R.string.remote_vol_down)) { send("/cmd?c=voldown") },
                button(getString(R.string.remote_mute)) { send("/cmd?c=mute") },
                button(getString(R.string.remote_vol_up)) { send("/cmd?c=volup") }
            )
        )
        content.addView(
            row(
                button(getString(R.string.remote_send_channel)) {
                    val all = MultiViewActivity.pickerCategories.flatMap { it.streams }.distinctBy { it.id }
                    showChannelSender(all, getString(R.string.remote_send_channel))
                },
                button(getString(R.string.remote_send_favorite)) {
                    showChannelSender(FavoritesStore(this).getAllStreams(), getString(R.string.remote_send_favorite))
                }
            )
        )

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

    private fun button(label: String, weight: Float = 1f, onClick: () -> Unit): Button {
        val margin = (4 * density).toInt()
        return Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, (64 * density).toInt(), weight).apply {
                setMargins(margin, margin, margin, margin)
            }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
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

    private fun send(path: String, body: String? = null) {
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
                        if (!ok) Toast.makeText(this, R.string.remote_not_playing, Toast.LENGTH_LONG).show()
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
                statusText.text = when {
                    code == 200 -> describeStatus(text)
                    code == 401 -> getString(R.string.remote_wrong_pin)
                    else -> getString(R.string.remote_unreachable)
                }
            }
        }.start()
    }

    private fun describeStatus(text: String): String {
        return try {
            val player = JSONObject(text).optJSONObject("player")
                ?: return getString(R.string.remote_idle)
            val title = player.optString("title")
            val now = player.optString("now")
            val paused = !player.optBoolean("playing", true)
            val lines = ArrayList<String>()
            lines.add((if (paused) "⏸ " else "▶ ") + title)
            if (now.isNotBlank()) lines.add(now)
            lines.joinToString("\n")
        } catch (e: Exception) {
            ""
        }
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
