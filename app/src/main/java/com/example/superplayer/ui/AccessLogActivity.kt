package com.example.superplayer.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.superplayer.R
import com.example.superplayer.data.AccessAdmin
import com.example.superplayer.data.AccessControl
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Registro de accesos: con el PIN maestro, carga el archivo de acceso (acceso.json) y permite añadir, quitar y editar
 * códigos (nombre, PIN, desde, caducidad, apartados, tiempo) y los ajustes generales, y lo publica en GitHub.
 * Necesita un token de GitHub guardado solo en este dispositivo; sin él, solo se puede ver.
 */
class AccessLogActivity : AppCompatActivity() {
    private var root: JSONObject = JSONObject()
    private var sha: String? = null
    private var dirty = false
    private var online = false
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var btnPublish: Button
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun mkButton(text: String, filled: Boolean = false): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(if (filled) 0xFF1565C0.toInt() else 0xFF2A2A2A.toInt()) }
        setPadding(dp(14), dp(6), dp(14), dp(6))
        isFocusable = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AccessControl.masterActive(this)) {
            finish()
            return
        }
        title = getString(R.string.access_log_title)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (dirty) confirmDiscard { dirty = false; finish() } else finish()
            }
        })
        status = TextView(this).apply { setTextColor(0xFFB0B0B0.toInt()); textSize = 13f; setPadding(dp(16), dp(4), dp(16), dp(8)) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(16)) }
        val btnAdd = mkButton(getString(R.string.access_log_add), true).apply { setOnClickListener { editCode(null) } }
        val btnSettings = mkButton(getString(R.string.access_log_settings)).apply { setOnClickListener { settingsDialog() } }
        val btnToken = mkButton(getString(R.string.access_log_token)).apply { setOnClickListener { tokenDialog() } }
        val btnReload = mkButton(getString(R.string.access_log_reload)).apply { setOnClickListener { if (dirty) confirmDiscard { load() } else load() } }
        btnPublish = mkButton(getString(R.string.access_log_publish), true).apply { setOnClickListener { publish() } }
        val bar1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
            for (b in listOf(btnAdd, btnSettings)) addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        val bar2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(4))
            for (b in listOf(btnToken, btnReload, btnPublish)) addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        val header = TextView(this).apply {
            setText(R.string.access_log_title)
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(16), dp(16), dp(4))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header); addView(status); addView(bar1); addView(bar2); addView(list)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF121212.toInt())
            isFillViewport = true
            fitsSystemWindows = true
            addView(col)
        }
        setContentView(scroll)
        load()
    }

    // ---- Carga y publicación ----

    private fun load() {
        status.text = getString(R.string.access_log_loading)
        val app = applicationContext
        Thread({
            var text: String? = null
            var newSha: String? = null
            var err: String? = null
            if (AccessAdmin.token(app).isNotEmpty()) {
                try {
                    val l = AccessAdmin.fetch(app)
                    text = l.text; newSha = l.sha
                } catch (e: AccessAdmin.Failure) {
                    err = when (e.code) {
                        401, 403 -> getString(R.string.access_log_err_token, e.message ?: "")
                        404 -> getString(R.string.access_log_err_404)
                        else -> getString(R.string.access_log_err_other, e.message ?: "")
                    }
                } catch (e: Exception) {
                    err = getString(R.string.access_log_err_other, e.message ?: "")
                }
            }
            if (text == null) text = AccessControl.cachedText(app)
            val t = text
            val s = newSha
            val e = err
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    root = JSONObject(t ?: "{}")
                } catch (ex: Exception) {
                    root = JSONObject()
                }
                sha = s
                online = s != null
                dirty = false
                status.text = when {
                    online -> getString(R.string.access_log_status_online)
                    e != null -> e
                    else -> getString(R.string.access_log_status_readonly)
                }
                render()
            }
        }, "access-log-load").start()
    }

    private fun publish() {
        val s = sha
        if (!online || s == null) {
            Toast.makeText(this, R.string.access_log_need_token, Toast.LENGTH_LONG).show()
            tokenDialog()
            return
        }
        if (!dirty) {
            Toast.makeText(this, R.string.access_log_nochanges, Toast.LENGTH_SHORT).show()
            return
        }
        val text = root.toString(2)
        btnPublish.isEnabled = false
        status.text = getString(R.string.access_log_publishing)
        val app = applicationContext
        Thread({
            var newSha: String? = null
            var err: String? = null
            try {
                newSha = AccessAdmin.publish(app, text, s, "Registro de accesos")
            } catch (e: AccessAdmin.Failure) {
                err = when (e.code) {
                    401, 403 -> getString(R.string.access_log_err_token, e.message ?: "")
                    409, 422 -> getString(R.string.access_log_err_conflict)
                    else -> getString(R.string.access_log_err_other, e.message ?: "")
                }
            } catch (e: Exception) {
                err = getString(R.string.access_log_err_other, e.message ?: "")
            }
            val ns = newSha
            val er = err
            runOnUiThread {
                btnPublish.isEnabled = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ns != null) {
                    sha = ns
                    dirty = false
                    AccessControl.storeLocal(app, text)
                    status.text = getString(R.string.access_log_status_online)
                    Toast.makeText(this, R.string.access_log_published, Toast.LENGTH_LONG).show()
                } else {
                    status.text = er
                    AlertDialog.Builder(this).setTitle(R.string.access_title).setMessage(er).setPositiveButton(android.R.string.ok, null).show()
                }
                render()
            }
        }, "access-log-publish").start()
    }

    private fun confirmDiscard(then: () -> Unit) {
        AlertDialog.Builder(this).setMessage(R.string.access_log_discard)
            .setPositiveButton(android.R.string.ok) { _, _ -> then() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ---- Lista ----

    private fun users(): JSONArray {
        var u = root.optJSONArray("users")
        if (u == null) { u = JSONArray(); root.put("users", u) }
        return u
    }

    private fun expiryText(o: JSONObject, now: Long): Pair<String, Int> {
        val exp = o.optString("expires", "")
        val from = o.optString("from", "")
        try {
            if (from.isNotEmpty() && day.parse(from)!!.time > now) return getString(R.string.access_log_st_future, from) to 0xFFFFB300.toInt()
            if (exp.isNotEmpty()) {
                val end = day.parse(exp)!!.time + 86_400_000L - 1
                if (now > end) return getString(R.string.access_log_st_expired, exp) to 0xFFE53935.toInt()
                val days = ((end - now) / 86_400_000L).toInt() + 1
                return getString(R.string.access_log_st_active_until, exp, days) to 0xFF43A047.toInt()
            }
        } catch (e: Exception) {
            return getString(R.string.access_log_st_baddate) to 0xFFE53935.toInt()
        }
        return getString(R.string.access_log_st_active) to 0xFF43A047.toInt()
    }

    private fun render() {
        list.removeAllViews()
        btnPublish.alpha = if (dirty) 1f else 0.5f
        val arr = users()
        val now = System.currentTimeMillis()
        if (arr.length() == 0) {
            list.addView(TextView(this).apply { setText(R.string.access_log_empty); setTextColor(0xFFB0B0B0.toInt()); setPadding(dp(8), dp(16), dp(8), dp(8)) })
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val (st, color) = expiryText(o, now)
            val secs = o.optJSONArray("sections")
            val secText = if (secs == null || secs.length() == 0) getString(R.string.access_log_all) else (0 until secs.length()).joinToString(", ") { secs.optString(it) }
            val mins = if (o.has("unlock_minutes")) getString(R.string.access_log_minutes, o.optInt("unlock_minutes")) else null
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF1E1E1E.toInt()) }
                isFocusable = true
                isClickable = true
                setOnClickListener { editCode(i) }
            }
            card.addView(TextView(this).apply {
                text = o.optString("name", "").ifBlank { getString(R.string.access_log_noname, i + 1) }
                setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply { text = st; setTextColor(color); textSize = 13f })
            card.addView(TextView(this).apply {
                text = getString(R.string.access_log_sections_line, secText) + (if (mins != null) " · $mins" else "")
                setTextColor(0xFFB0B0B0.toInt()); textSize = 12f
            })
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) })
        }
    }

    // ---- Editar / añadir un código ----

    private fun field(hint: String, number: Boolean = false, text: String = "") = EditText(this).apply {
        this.hint = hint
        setText(text)
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
    }

    private fun addDays(n: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, n)
        return day.format(Date(c.timeInMillis))
    }

    private fun sectionsDialog(current: MutableSet<String>, allIsEmpty: Boolean, onDone: () -> Unit) {
        val names = AccessControl.ALL_SECTIONS.toTypedArray()
        val checked = BooleanArray(names.size) { names[it] in current }
        AlertDialog.Builder(this)
            .setTitle(if (allIsEmpty) R.string.access_log_pick_sections_code else R.string.access_log_pick_sections_locked)
            .setMultiChoiceItems(names, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                current.clear()
                for (i in names.indices) if (checked[i]) current.add(names[i])
                onDone()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editCode(index: Int?) {
        val arr = users()
        val old = if (index != null) arr.optJSONObject(index) else null
        val sections = HashSet<String>()
        old?.optJSONArray("sections")?.let { a -> for (i in 0 until a.length()) sections.add(a.optString(i)) }
        val name = field(getString(R.string.access_code_name), false, old?.optString("name", "").orEmpty())
        val pin = field(if (old == null) getString(R.string.access_pin_hint) else getString(R.string.access_log_newpin), true)
        val from = field(getString(R.string.access_code_from), false, old?.optString("from", "").orEmpty())
        val exp = field(getString(R.string.access_code_expires), false, old?.optString("expires", "").orEmpty())
        val mins = field(getString(R.string.access_log_mins_hint), true, if (old != null && old.has("unlock_minutes")) old.optInt("unlock_minutes").toString() else "")
        val secBtn = mkButton("").apply { }
        fun refreshSec() { secBtn.text = getString(R.string.access_log_sections_line, if (sections.isEmpty()) getString(R.string.access_log_all) else sections.joinToString(", ")) }
        refreshSec()
        secBtn.setOnClickListener { sectionsDialog(sections, true) { refreshSec() } }
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((label, action) in listOf<Pair<String, () -> Unit>>(
                getString(R.string.access_log_plus7) to { exp.setText(addDays(7)) },
                getString(R.string.access_log_plus30) to { exp.setText(addDays(30)) },
                getString(R.string.access_log_noexp) to { exp.setText("") }
            )) {
                addView(mkButton(label).apply { textSize = 12f; setOnClickListener { action() } },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            for (v in listOf<android.view.View>(name, pin, from, exp, quick, mins, secBtn)) addView(v)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (old == null) R.string.access_log_add else R.string.access_log_edit)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (old != null && index != null) {
            b.setNeutralButton(R.string.access_log_delete) { _, _ ->
                AlertDialog.Builder(this).setMessage(getString(R.string.access_log_delete_ask, old.optString("name", "")))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        arr.remove(index)
                        dirty = true
                        render()
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        val dialog = b.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = pin.text.toString().trim()
                val f = from.text.toString().trim()
                val e = exp.text.toString().trim()
                if (old == null && code.length < 3) {
                    Toast.makeText(this, R.string.access_pin_short, Toast.LENGTH_SHORT).show(); return@setOnClickListener
                }
                if (code.isNotEmpty() && code.length < 3) {
                    Toast.makeText(this, R.string.access_pin_short, Toast.LENGTH_SHORT).show(); return@setOnClickListener
                }
                try {
                    if (f.isNotEmpty()) day.parse(f)!!
                    if (e.isNotEmpty()) day.parse(e)!!
                } catch (ex: Exception) {
                    Toast.makeText(this, R.string.access_date_bad, Toast.LENGTH_LONG).show(); return@setOnClickListener
                }
                val o = old ?: JSONObject()
                fun putOrRemove(k: String, v: String) { if (v.isEmpty()) o.remove(k) else o.put(k, v) }
                putOrRemove("name", name.text.toString().trim())
                if (code.isNotEmpty()) {
                    o.put("pin_hash", AccessControl.sha256(root.optString("salt", "") + code))
                    o.remove("pin")
                }
                putOrRemove("from", f)
                putOrRemove("expires", e)
                val m = mins.text.toString().trim()
                if (m.isEmpty()) o.remove("unlock_minutes") else o.put("unlock_minutes", m.toIntOrNull()?.coerceIn(1, 43200) ?: 10)
                if (sections.isEmpty()) o.remove("sections") else o.put("sections", JSONArray(sections.sorted()))
                if (old == null) arr.put(o)
                dirty = true
                dialog.dismiss()
                render()
            }
        }
        dialog.show()
    }

    // ---- Ajustes generales ----

    private fun settingsDialog() {
        val locked = HashSet<String>()
        root.optJSONArray("locked")?.let { a -> for (i in 0 until a.length()) locked.add(a.optString(i)) }
        val enabled = CheckBox(this).apply { text = getString(R.string.access_log_enabled); isChecked = root.optBoolean("enabled", true) }
        val blockAll = CheckBox(this).apply { text = getString(R.string.access_log_blockall); isChecked = root.optBoolean("block_all", false) }
        val message = field(getString(R.string.access_log_message), false, root.optString("message", ""))
        val unlock = field(getString(R.string.access_log_unlock_default), true, root.optInt("unlock_minutes", 10).toString())
        val offline = field(getString(R.string.access_log_offline), true, root.optInt("offline_days", 7).toString())
        val newMaster = field(getString(R.string.access_log_newmaster), true)
        val newPin = field(getString(R.string.access_log_newgeneral), true)
        val lockedBtn = mkButton("")
        fun refreshLocked() { lockedBtn.text = getString(R.string.access_log_locked_line, if (locked.isEmpty()) "—" else locked.joinToString(", ")) }
        refreshLocked()
        lockedBtn.setOnClickListener { sectionsDialog(locked, false) { refreshLocked() } }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            for (v in listOf<android.view.View>(enabled, blockAll, message, unlock, offline, lockedBtn, newMaster, newPin)) addView(v)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.access_log_settings)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nm = newMaster.text.toString().trim()
                val np = newPin.text.toString().trim()
                if ((nm.isNotEmpty() && nm.length < 3) || (np.isNotEmpty() && np.length < 3)) {
                    Toast.makeText(this, R.string.access_pin_short, Toast.LENGTH_SHORT).show(); return@setOnClickListener
                }
                val salt = root.optString("salt", "")
                root.put("enabled", enabled.isChecked)
                root.put("block_all", blockAll.isChecked)
                root.put("message", message.text.toString().trim())
                root.put("unlock_minutes", unlock.text.toString().toIntOrNull()?.coerceIn(1, 43200) ?: 10)
                root.put("offline_days", offline.text.toString().toIntOrNull()?.coerceIn(0, 3650) ?: 7)
                root.put("locked", JSONArray(locked.sorted()))
                if (nm.isNotEmpty()) { root.put("master_hash", AccessControl.sha256(salt + nm)); root.remove("master_pin") }
                if (np.isNotEmpty()) { root.put("pin_hash", AccessControl.sha256(salt + np)); root.remove("pin") }
                dirty = true
                dialog.dismiss()
                render()
            }
        }
        dialog.show()
    }

    // ---- Token de GitHub ----

    private fun tokenDialog() {
        val input = field("github_pat_…", false, AccessAdmin.token(this))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(4)); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.access_log_token)
            .setMessage(R.string.access_log_token_help)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AccessAdmin.setToken(this, input.text.toString())
                if (dirty) Toast.makeText(this, R.string.access_log_token_saved_dirty, Toast.LENGTH_LONG).show() else load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
