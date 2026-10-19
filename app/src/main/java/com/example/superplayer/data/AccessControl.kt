package com.example.superplayer.data

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.superplayer.R
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Bloqueo de apartados con PIN gestionado a distancia.
 *
 * Se guarda una URL (p. ej. un "raw" de GitHub) que apunta a un archivo JSON:
 *   {
 *     "enabled": true,
 *     "salt": "a1b2c3",
 *     "pin_hash": "<sha256 en hex de salt+pin>",      // o "pin": "1234" (en claro)
 *     "locked": ["telegram","cine","web","remote","settings"],   // o ["all"]
 *     "block_all": false,                             // true: nadie entra en la app
 *     "message": "Texto opcional para el aviso de bloqueo",
 *     "offline_days": 7,                              // días que vale la última copia sin conexión (0 = siempre)
 *     "unlock_minutes": 10                            // tiempo que sigue abierto tras meter el PIN
 *   }
 * La app lo baja al abrirse y cada pocos minutos; si lo cambias, el PIN cambia en todas las instalaciones.
 * Sin URL guardada no hay ningún bloqueo. Con URL pero sin poder comprobarla (nunca se bajó), todo queda bloqueado.
 */
object AccessControl {
    const val S_TELEGRAM = "telegram"
    const val S_CINE = "cine"
    const val S_WEB = "web"
    const val S_REMOTE = "remote"
    const val S_SETTINGS = "settings"
    const val S_TV = "tv"
    const val S_LIST2 = "lista2"
    const val S_TDT = "tdt"
    const val S_RADIO = "radio"
    const val S_NOWLIVE = "nowlive"
    const val S_EPG = "epg"

    private const val PREFS = "access_ctl"
    private const val K_URL = "url"
    private const val K_JSON = "json"
    private const val K_OK_AT = "ok_at"
    private const val MIN_REFRESH_MS = 2 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var unlockedUntil = 0L
    @Volatile private var masterUntil = 0L
    @Volatile private var lastTry = 0L
    private var failures = 0
    private var lockedOutUntil = 0L
    private var shownMain: AlertDialog? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Archivo de acceso incluido en la app: todas las instalaciones lo consultan sin tener que configurar nada. */
    const val DEFAULT_URL = "https://raw.githubusercontent.com/socramtv/m3u/refs/heads/main/acceso.json"

    fun url(c: Context): String = prefs(c).getString(K_URL, "").orEmpty().ifBlank { DEFAULT_URL }
    fun lastOkAt(c: Context): Long = prefs(c).getLong(K_OK_AT, 0L)

    class Config(
        val enabled: Boolean, val salt: String, val pinHash: String, val pinPlain: String, val masterHash: String, val masterPlain: String,
        val locked: Set<String>, val blockAll: Boolean, val message: String, val offlineDays: Int, val unlockMinutes: Int
    )

    private fun parse(text: String): Config? = try {
        val o = JSONObject(text.trim())
        val arr: JSONArray = o.optJSONArray("locked") ?: JSONArray()
        val set = HashSet<String>()
        for (i in 0 until arr.length()) set.add(arr.optString(i).trim().lowercase())
        Config(
            enabled = o.optBoolean("enabled", true),
            salt = o.optString("salt", ""),
            pinHash = o.optString("pin_hash", "").lowercase(),
            pinPlain = o.optString("pin", ""),
            masterHash = o.optString("master_hash", "").lowercase(),
            masterPlain = o.optString("master_pin", ""),
            locked = set,
            blockAll = o.optBoolean("block_all", false),
            message = o.optString("message", ""),
            offlineDays = o.optInt("offline_days", 7),
            unlockMinutes = o.optInt("unlock_minutes", 10).coerceIn(1, 24 * 60)
        )
    } catch (e: Exception) {
        null
    }

    private fun config(c: Context): Config? = prefs(c).getString(K_JSON, null)?.let { parse(it) }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** ¿Hay control de acceso configurado en este dispositivo? */
    fun isConfigured(c: Context) = url(c).isNotBlank()

    /** Motivo por el que todo está bloqueado (sin PIN que valga), o null. */
    private fun blockReason(c: Context): String? {
        if (!isConfigured(c)) return null
        val cfg = config(c) ?: return c.getString(R.string.access_err_nocheck)
        if (!cfg.enabled) return null
        if (cfg.blockAll) return cfg.message.ifBlank { c.getString(R.string.access_blocked_default) }
        if (cfg.offlineDays > 0 && System.currentTimeMillis() - lastOkAt(c) > cfg.offlineDays * 86_400_000L) {
            return c.getString(R.string.access_err_expired, cfg.offlineDays)
        }
        return null
    }

    fun isLocked(c: Context, section: String): Boolean {
        if (!isConfigured(c)) return false
        if (blockReason(c) != null) return true
        val cfg = config(c) ?: return true
        if (!cfg.enabled) return false
        if (System.currentTimeMillis() < unlockedUntil) return false
        return "all" in cfg.locked || section in cfg.locked
    }

    private fun hasMaster(cfg: Config) = cfg.masterHash.isNotEmpty() || cfg.masterPlain.isNotEmpty()

    /** 0 = no vale, 1 = PIN de acceso a los apartados, 2 = PIN maestro (apartados + configuración del control de acceso). */
    private fun verify(c: Context, pin: String): Int {
        val cfg = config(c) ?: return 0
        if (pin.isEmpty()) return 0
        val master = if (cfg.masterHash.isNotEmpty()) sha256(cfg.salt + pin) == cfg.masterHash
        else cfg.masterPlain.isNotEmpty() && cfg.masterPlain == pin
        if (master) return 2
        val user = if (cfg.pinHash.isNotEmpty()) sha256(cfg.salt + pin) == cfg.pinHash
        else cfg.pinPlain.isNotEmpty() && cfg.pinPlain == pin
        return if (user) 1 else 0
    }

    // ---- Descarga ----

    /** Baja el archivo en segundo plano (como mucho cada pocos minutos, salvo [force]). [done] (en hilo principal): ¿se pudo? */
    fun refreshAsync(c: Context, force: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        val app = c.applicationContext
        if (!isConfigured(app)) { done?.invoke(true); return }
        val now = System.currentTimeMillis()
        if (!force && now - lastTry < MIN_REFRESH_MS) { done?.invoke(true); return }
        lastTry = now
        Thread({
            val ok = try { fetchAndStore(app, url(app)) } catch (e: Exception) { false }
            main.post { done?.invoke(ok) }
        }, "access-refresh").start()
    }

    /** Descarga [url], comprueba que es un archivo de acceso válido y lo guarda. Bloquea: hilo de fondo. */
    private fun fetchAndStore(c: Context, url: String): Boolean {
        val bust = url + (if (url.contains('?')) "&" else "?") + "t=" + System.currentTimeMillis()
        val conn = java.net.URL(bust).openConnection() as java.net.HttpURLConnection
        val text = try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Cache-Control", "no-cache")
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val cfg = parse(text) ?: return false
        if (cfg.enabled && cfg.pinHash.isEmpty() && cfg.pinPlain.isEmpty() && !hasMaster(cfg)) return false
        val p = prefs(c)
        val old = p.getString(K_JSON, null)?.let { parse(it) }
        // Si cambió el PIN o la lista de bloqueos, hay que volver a pedirlo.
        if (old == null || old.pinHash != cfg.pinHash || old.pinPlain != cfg.pinPlain || old.masterHash != cfg.masterHash || old.masterPlain != cfg.masterPlain || old.locked != cfg.locked || old.salt != cfg.salt) { unlockedUntil = 0L; masterUntil = 0L }
        p.edit().putString(K_JSON, text).putLong(K_OK_AT, System.currentTimeMillis()).apply()
        return true
    }

    /** Guarda una URL nueva solo si el archivo se baja y es válido. [done] en hilo principal. */
    fun setUrl(c: Context, url: String, done: (Boolean) -> Unit) {
        val app = c.applicationContext
        Thread({
            val ok = try { fetchAndStore(app, url.trim()) } catch (e: Exception) { false }
            if (ok) prefs(app).edit().putString(K_URL, url.trim()).apply()
            main.post { done(ok) }
        }, "access-seturl").start()
    }

    fun clear(c: Context) {
        prefs(c).edit().clear().apply()
        unlockedUntil = 0L
        masterUntil = 0L
    }

    // ---- Interfaz ----

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun pinDialog(a: Activity, onOk: () -> Unit, onCancel: () -> Unit, master: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now < lockedOutUntil) {
            Toast.makeText(a, a.getString(R.string.access_wait, ((lockedOutUntil - now) / 1000 + 1).toInt()), Toast.LENGTH_SHORT).show()
            onCancel()
            return
        }
        val input = EditText(a).apply {
            hint = a.getString(R.string.access_pin_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 8))
        }
        var done = false
        val dialog = AlertDialog.Builder(a)
            .setTitle(R.string.access_title)
            .setMessage(if (master) R.string.access_enter_master else R.string.access_enter_pin)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> done = true; onCancel() }
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cfg = config(a)
                val level = verify(a, input.text.toString().trim())
                if (level >= (if (master) 2 else 1)) {
                    failures = 0
                    val until = System.currentTimeMillis() + (cfg?.unlockMinutes ?: 10) * 60_000L
                    unlockedUntil = until
                    if (level == 2) masterUntil = until
                    done = true
                    dialog.dismiss()
                    onOk()
                } else {
                    failures++
                    input.setText("")
                    if (failures >= 5) {
                        failures = 0
                        lockedOutUntil = System.currentTimeMillis() + 30_000L
                        done = true
                        dialog.dismiss()
                        Toast.makeText(a, R.string.access_too_many, Toast.LENGTH_LONG).show()
                        onCancel()
                    } else {
                        input.error = a.getString(R.string.access_wrong)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun blockedDialog(a: Activity, reason: String, onClose: () -> Unit, onUnblocked: () -> Unit) {
        AlertDialog.Builder(a)
            .setTitle(R.string.access_title)
            .setMessage(reason)
            .setCancelable(false)
            .setPositiveButton(R.string.access_retry, null)
            .setNegativeButton(R.string.access_exit) { _, _ -> onClose() }
            .create().also { d ->
                d.setOnShowListener {
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        refreshAsync(a, force = true) { _ ->
                            if (a.isFinishing || a.isDestroyed) return@refreshAsync
                            val r = blockReason(a)
                            if (r == null) { d.dismiss(); onUnblocked() } else d.setMessage(r)
                        }
                    }
                }
                d.show()
            }
    }

    /**
     * Para pantallas enteras (Telegram, Cine, Web, mando): si el apartado está bloqueado, la pantalla se oculta
     * y pide el PIN; si se cancela, se cierra. Llamar justo después de setContentView.
     */
    fun guardActivity(a: Activity, section: String) {
        refreshAsync(a)
        if (!isLocked(a, section)) return
        val root = a.window.decorView
        root.visibility = View.INVISIBLE
        fun proceed() {
            if (a.isFinishing || a.isDestroyed) return
            val reason = blockReason(a)
            when {
                reason != null -> blockedDialog(a, reason, { a.finish() }, { proceed() })
                isLocked(a, section) -> pinDialog(a, onOk = { root.visibility = View.VISIBLE }, onCancel = { a.finish() })
                else -> root.visibility = View.VISIBLE
            }
        }
        main.post { proceed() }
    }

    /** Para una acción suelta (ajustes, copia de seguridad…): pide el PIN si hace falta y luego la ejecuta. */
    fun require(a: Activity, section: String, action: () -> Unit) {
        refreshAsync(a)
        if (!isLocked(a, section)) { action(); return }
        val reason = blockReason(a)
        if (reason != null) {
            AlertDialog.Builder(a).setTitle(R.string.access_title).setMessage(reason)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        pinDialog(a, onOk = action, onCancel = { })
    }

    /** Pantalla principal: si el administrador ha bloqueado toda la app (o caducó la copia), no se puede seguir. */
    fun guardMain(a: Activity) {
        refreshAsync(a)
        if (shownMain?.isShowing == true) return
        val reason = blockReason(a) ?: return
        val d = AlertDialog.Builder(a)
            .setTitle(R.string.access_title)
            .setMessage(reason)
            .setCancelable(false)
            .setPositiveButton(R.string.access_retry, null)
            .setNegativeButton(R.string.access_exit) { _, _ -> a.finishAffinity() }
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                refreshAsync(a, force = true) { _ ->
                    val r = if (a.isFinishing || a.isDestroyed) null else blockReason(a)
                    if (r == null) d.dismiss() else d.setMessage(r)
                }
            }
        }
        shownMain = d
        d.show()
    }

    /**
     * Diálogo de gestión (menú ⋮ > Control de acceso). Solo con el PIN MAESTRO del archivo. Si el archivo aún no
     * define un PIN maestro (o no se ha bajado), solo se ofrece el generador del archivo, que no cambia nada en la app.
     */
    fun showManage(a: Activity) {
        val cfg = config(a)
        val reason = blockReason(a)
        if (reason != null) {
            AlertDialog.Builder(a).setTitle(R.string.access_title).setMessage(reason)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        if (cfg == null || !hasMaster(cfg)) {
            makeFileDialog(a)
            return
        }
        if (System.currentTimeMillis() < masterUntil) manageDialog(a)
        else pinDialog(a, onOk = { manageDialog(a) }, onCancel = { }, master = true)
    }

    private fun manageDialog(a: Activity) {
        val url = url(a)
        val cfg = config(a)
        val status = StringBuilder()
        run {
            status.append(a.getString(R.string.access_status_url, url)).append('\n')
            val ok = lastOkAt(a)
            if (ok > 0) status.append(a.getString(R.string.access_status_checked, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(ok)))).append('\n')
            if (cfg != null) status.append(a.getString(R.string.access_status_locked, if (cfg.enabled) cfg.locked.joinToString(", ").ifEmpty { "—" } else a.getString(R.string.access_disabled)))
        }
        val items = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        items.add(a.getString(R.string.access_set_url)); acts.add { askUrl(a) }
        run {
            items.add(a.getString(R.string.access_refresh)); acts.add {
                refreshAsync(a, force = true) { ok -> Toast.makeText(a, if (ok) R.string.access_refreshed else R.string.access_refresh_fail, Toast.LENGTH_SHORT).show() }
            }
            items.add(a.getString(R.string.access_remove)); acts.add {
                clear(a)
                Toast.makeText(a, R.string.access_removed, Toast.LENGTH_SHORT).show()
            }
        }
        items.add(a.getString(R.string.access_make_file)); acts.add { makeFileDialog(a) }
        // Con setMessage() Android no enseña la lista de opciones: el estado va en un título propio.
        val header = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(a, 24), dp(a, 20), dp(a, 24), dp(a, 8))
            addView(android.widget.TextView(a).apply {
                setText(R.string.access_manage_title)
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(android.widget.TextView(a).apply {
                text = status.toString().trim()
                textSize = 13f
                setPadding(0, dp(a, 8), 0, 0)
            })
        }
        AlertDialog.Builder(a)
            .setCustomTitle(header)
            .setItems(items.toTypedArray()) { _, i -> acts[i]() }
            .setNegativeButton(R.string.channel_check_close, null)
            .show()
    }

    private fun askUrl(a: Activity) {
        val input = EditText(a).apply {
            hint = "https://raw.githubusercontent.com/…/acceso.json"
            setText(url(a))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 8))
        }
        AlertDialog.Builder(a)
            .setTitle(R.string.access_set_url)
            .setMessage(R.string.access_url_help)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val u = input.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                Toast.makeText(a, R.string.access_checking, Toast.LENGTH_SHORT).show()
                setUrl(a, u) { ok ->
                    Toast.makeText(a, if (ok) R.string.access_url_saved else R.string.access_url_bad, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Genera el JSON del archivo (con hashes) a partir de los dos PIN, y lo copia al portapapeles para pegarlo en GitHub. */
    private fun makeFileDialog(a: Activity) {
        fun field(hintRes: Int) = EditText(a).apply {
            hint = a.getString(hintRes)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        val userPin = field(R.string.access_pin_user_hint)
        val masterPin = field(R.string.access_pin_master_hint)
        val box = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(a, 20), dp(a, 12), dp(a, 20), dp(a, 4))
            addView(userPin)
            addView(masterPin)
        }
        AlertDialog.Builder(a)
            .setTitle(R.string.access_make_file)
            .setMessage(R.string.access_make_help)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = userPin.text.toString().trim()
                val master = masterPin.text.toString().trim()
                if (pin.length < 3 || master.length < 3) {
                    Toast.makeText(a, R.string.access_pin_short, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (pin == master) {
                    Toast.makeText(a, R.string.access_pin_same, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val salt = java.lang.Long.toHexString(java.security.SecureRandom().nextLong())
                val json = JSONObject()
                    .put("enabled", true)
                    .put("salt", salt)
                    .put("pin_hash", sha256(salt + pin))
                    .put("master_hash", sha256(salt + master))
                    .put("locked", JSONArray(listOf(S_TV, S_LIST2, S_CINE, S_TDT, S_RADIO, S_NOWLIVE, S_EPG, S_TELEGRAM, S_WEB, S_REMOTE, S_SETTINGS)))
                    .put("block_all", false)
                    .put("message", "")
                    .put("offline_days", 7)
                    .put("unlock_minutes", 10)
                    .toString(2)
                try {
                    (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("acceso.json", json))
                } catch (e: Exception) { }
                AlertDialog.Builder(a)
                    .setTitle(R.string.access_make_done)
                    .setMessage(json)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
