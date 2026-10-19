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

    private const val PREFS = "access_ctl"
    private const val K_URL = "url"
    private const val K_JSON = "json"
    private const val K_OK_AT = "ok_at"
    private const val MIN_REFRESH_MS = 2 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var unlockedUntil = 0L
    @Volatile private var lastTry = 0L
    private var failures = 0
    private var lockedOutUntil = 0L
    private var shownMain: AlertDialog? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun url(c: Context): String = prefs(c).getString(K_URL, "").orEmpty()
    fun lastOkAt(c: Context): Long = prefs(c).getLong(K_OK_AT, 0L)

    class Config(
        val enabled: Boolean, val salt: String, val pinHash: String, val pinPlain: String,
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

    private fun verify(c: Context, pin: String): Boolean {
        val cfg = config(c) ?: return false
        if (cfg.pinHash.isNotEmpty()) return sha256(cfg.salt + pin) == cfg.pinHash
        return cfg.pinPlain.isNotEmpty() && cfg.pinPlain == pin
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
        if (cfg.enabled && cfg.pinHash.isEmpty() && cfg.pinPlain.isEmpty()) return false
        val p = prefs(c)
        val old = p.getString(K_JSON, null)?.let { parse(it) }
        // Si cambió el PIN o la lista de bloqueos, hay que volver a pedirlo.
        if (old == null || old.pinHash != cfg.pinHash || old.pinPlain != cfg.pinPlain || old.locked != cfg.locked || old.salt != cfg.salt) unlockedUntil = 0L
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
    }

    // ---- Interfaz ----

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun pinDialog(a: Activity, onOk: () -> Unit, onCancel: () -> Unit) {
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
            .setMessage(R.string.access_enter_pin)
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
                if (verify(a, input.text.toString().trim())) {
                    failures = 0
                    unlockedUntil = System.currentTimeMillis() + (cfg?.unlockMinutes ?: 10) * 60_000L
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

    /** Diálogo de gestión (menú ⋮ > Control de acceso). Si ya hay control activo, pide el PIN antes. */
    fun showManage(a: Activity) {
        if (isConfigured(a)) {
            // Siempre con PIN, esté o no el apartado en la lista; sin PIN válido en el archivo no hay forma de entrar.
            val cfg = config(a)
            if (cfg != null && cfg.enabled && blockReason(a) == null && System.currentTimeMillis() >= unlockedUntil) {
                pinDialog(a, onOk = { manageDialog(a) }, onCancel = { })
                return
            }
        }
        manageDialog(a)
    }

    private fun manageDialog(a: Activity) {
        val url = url(a)
        val cfg = config(a)
        val status = StringBuilder()
        if (url.isBlank()) status.append(a.getString(R.string.access_status_off))
        else {
            status.append(a.getString(R.string.access_status_url, url)).append('\n')
            val ok = lastOkAt(a)
            if (ok > 0) status.append(a.getString(R.string.access_status_checked, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(ok)))).append('\n')
            if (cfg != null) status.append(a.getString(R.string.access_status_locked, if (cfg.enabled) cfg.locked.joinToString(", ").ifEmpty { "—" } else a.getString(R.string.access_disabled)))
        }
        val items = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        items.add(a.getString(R.string.access_set_url)); acts.add { askUrl(a) }
        if (url.isNotBlank()) {
            items.add(a.getString(R.string.access_refresh)); acts.add {
                refreshAsync(a, force = true) { ok -> Toast.makeText(a, if (ok) R.string.access_refreshed else R.string.access_refresh_fail, Toast.LENGTH_SHORT).show() }
            }
            items.add(a.getString(R.string.access_remove)); acts.add {
                clear(a)
                Toast.makeText(a, R.string.access_removed, Toast.LENGTH_SHORT).show()
            }
        }
        items.add(a.getString(R.string.access_make_file)); acts.add { makeFileDialog(a) }
        AlertDialog.Builder(a)
            .setTitle(R.string.access_manage_title)
            .setMessage(status.toString())
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

    /** Genera el JSON del archivo (con hash) a partir de un PIN, y lo copia al portapapeles para pegarlo en GitHub. */
    private fun makeFileDialog(a: Activity) {
        val input = EditText(a).apply {
            hint = a.getString(R.string.access_pin_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 8))
        }
        AlertDialog.Builder(a)
            .setTitle(R.string.access_make_file)
            .setMessage(R.string.access_make_help)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length < 3) {
                    Toast.makeText(a, R.string.access_pin_short, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val salt = java.lang.Long.toHexString(java.security.SecureRandom().nextLong())
                val json = JSONObject()
                    .put("enabled", true)
                    .put("salt", salt)
                    .put("pin_hash", sha256(salt + pin))
                    .put("locked", JSONArray(listOf(S_TELEGRAM, S_CINE, S_WEB, S_REMOTE, S_SETTINGS)))
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
