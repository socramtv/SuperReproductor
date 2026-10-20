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
 *     "users": [                                      // más códigos, cada uno con su caducidad y apartados
 *       {"name":"Ana","pin_hash":"…","expires":"2026-12-31"},
 *       {"name":"Luis","pin_hash":"…","from":"2026-11-01","expires":"2026-11-30","sections":["tv","tdt"],"unlock_minutes":60}
 *     ],
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

    /** Nombres válidos para la lista "locked" del archivo (los que se ven en el menú de gestión). */
    val ALL_SECTIONS = listOf(S_TV, S_LIST2, S_CINE, S_TDT, S_RADIO, S_NOWLIVE, S_EPG, S_TELEGRAM, S_WEB, S_REMOTE, S_SETTINGS)

    private const val PREFS = "access_ctl"
    private const val K_URL = "url"
    private const val K_JSON = "json"
    private const val K_OK_AT = "ok_at"
    private const val K_UNL = "unlocked_until"
    private const val K_KEY = "unlocked_key"
    private const val MIN_REFRESH_MS = 2 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var activeUntil = 0L
    @Volatile private var activeKey = ""
    @Volatile private var lastTry = 0L
    private var failures = 0
    private var lockedOutUntil = 0L
    private var shownMain: AlertDialog? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Archivo de acceso incluido en la app: todas las instalaciones lo consultan sin tener que configurar nada. */
    const val DEFAULT_URL = "https://raw.githubusercontent.com/socramtv/m3u/refs/heads/main/acceso.json"

    fun url(c: Context): String = prefs(c).getString(K_URL, "").orEmpty().ifBlank { DEFAULT_URL }
    fun lastOkAt(c: Context): Long = prefs(c).getLong(K_OK_AT, 0L)

    /** Un código de acceso: el maestro, el PIN general o uno de la lista "users". */
    class Cred(
        val key: String, val name: String, val level: Int, val hash: String, val plain: String,
        val sections: Set<String>?, val fromAt: Long, val expiresAt: Long, val minutes: Int?
    )

    class Config(
        val enabled: Boolean, val salt: String, val creds: List<Cred>,
        val locked: Set<String>, val blockAll: Boolean, val message: String, val offlineDays: Int, val unlockMinutes: Int
    )

    private fun parseDay(text: String, endOfDay: Boolean): Long? {
        if (text.isBlank()) return 0L
        return try {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            f.isLenient = false
            val d = f.parse(text.trim()) ?: return null
            d.time + (if (endOfDay) 86_400_000L - 1 else 0L)
        } catch (e: Exception) {
            null
        }
    }

    private fun keyOf(prefix: String, hash: String, plain: String) = prefix + sha256(hash + "|" + plain).take(12)

    private fun parse(text: String): Config? = try {
        val o = JSONObject(text.trim())
        val arr: JSONArray = o.optJSONArray("locked") ?: JSONArray()
        val set = HashSet<String>()
        for (i in 0 until arr.length()) set.add(arr.optString(i).trim().lowercase())
        val creds = ArrayList<Cred>()
        val mh = o.optString("master_hash", "").lowercase()
        val mp = o.optString("master_pin", "")
        if (mh.isNotEmpty() || mp.isNotEmpty()) creds.add(Cred("master", "master", 2, mh, mp, null, 0L, 0L, null))
        val ph = o.optString("pin_hash", "").lowercase()
        val pp = o.optString("pin", "")
        if (ph.isNotEmpty() || pp.isNotEmpty()) creds.add(Cred("pin", "pin", 1, ph, pp, null, 0L, 0L, null))
        val users = o.optJSONArray("users") ?: JSONArray()
        for (i in 0 until users.length()) {
            val u = users.optJSONObject(i) ?: continue
            val uh = u.optString("pin_hash", "").lowercase()
            val up = u.optString("pin", "")
            if (uh.isEmpty() && up.isEmpty()) continue
            // Una fecha mal escrita deja ese código sin efecto (mejor que dejarlo sin caducidad).
            val from = parseDay(u.optString("from", ""), false) ?: continue
            val exp = parseDay(u.optString("expires", ""), true) ?: continue
            val secArr = u.optJSONArray("sections")
            var sec: Set<String>? = null
            if (secArr != null && secArr.length() > 0) {
                val hs = HashSet<String>()
                for (k in 0 until secArr.length()) hs.add(secArr.optString(k).trim().lowercase())
                if ("all" !in hs) sec = hs
            }
            val mins = if (u.has("unlock_minutes")) u.optInt("unlock_minutes", 10).coerceIn(1, 24 * 60 * 30) else null
            creds.add(Cred(keyOf("u:", uh, up), u.optString("name", "").ifBlank { "Código ${i + 1}" }, 1, uh, up, sec, from, exp, mins))
        }
        Config(
            enabled = o.optBoolean("enabled", true),
            salt = o.optString("salt", ""),
            creds = creds,
            locked = set,
            blockAll = o.optBoolean("block_all", false),
            message = o.optString("message", ""),
            offlineDays = o.optInt("offline_days", 7),
            unlockMinutes = o.optInt("unlock_minutes", 10).coerceIn(1, 24 * 60 * 30)
        )
    } catch (e: Exception) {
        null
    }

    private var unlockLoaded = false

    /** El desbloqueo sobrevive a cerrar la app: se recupera de las preferencias mientras ese código siga en el archivo. */
    private fun loadUnlock(c: Context) {
        if (unlockLoaded) return
        unlockLoaded = true
        val p = prefs(c)
        activeKey = p.getString(K_KEY, "").orEmpty()
        activeUntil = p.getLong(K_UNL, 0L)
    }

    private fun saveUnlock(c: Context) {
        prefs(c).edit().putString(K_KEY, activeKey).putLong(K_UNL, activeUntil).apply()
    }

    private fun clearUnlock(c: Context) {
        activeKey = ""
        activeUntil = 0L
        unlockLoaded = true
        prefs(c).edit().remove(K_KEY).remove(K_UNL).apply()
    }

    private fun credValidNow(cr: Cred, now: Long) = now >= cr.fromAt && (cr.expiresAt == 0L || now <= cr.expiresAt)

    /** El código con el que se desbloqueó, si sigue en el archivo, no ha caducado y el desbloqueo no ha terminado. */
    private fun activeCred(c: Context): Cred? {
        loadUnlock(c)
        val now = System.currentTimeMillis()
        if (activeKey.isEmpty() || now >= activeUntil) return null
        val cr = config(c)?.creds?.firstOrNull { it.key == activeKey } ?: return null
        return if (credValidNow(cr, now)) cr else null
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
        val ac = activeCred(c)
        if (ac != null && (ac.sections == null || section in ac.sections)) return false
        return "all" in cfg.locked || section in cfg.locked
    }

    private fun hasMaster(cfg: Config) = cfg.creds.any { it.level == 2 }

    private fun credMatches(cfg: Config, cr: Cred, pin: String): Boolean =
        if (cr.hash.isNotEmpty()) sha256(cfg.salt + pin) == cr.hash else cr.plain.isNotEmpty() && cr.plain == pin

    // Resultado de comprobar un PIN.
    private class Check(val cred: Cred?, val error: Int)

    /** [needMaster]: solo vale el maestro. [section]: el código tiene que dar acceso a ese apartado (null = cualquiera). */
    private fun check(c: Context, pin: String, needMaster: Boolean, section: String?): Check {
        val cfg = config(c) ?: return Check(null, R.string.access_wrong)
        if (pin.isEmpty()) return Check(null, R.string.access_wrong)
        val cr = cfg.creds.firstOrNull { credMatches(cfg, it, pin) } ?: return Check(null, R.string.access_wrong)
        val now = System.currentTimeMillis()
        if (now < cr.fromAt) return Check(null, R.string.access_not_yet)
        if (cr.expiresAt != 0L && now > cr.expiresAt) return Check(null, R.string.access_code_expired)
        if (needMaster && cr.level < 2) return Check(null, R.string.access_wrong)
        if (section != null && cr.sections != null && section !in cr.sections) return Check(null, R.string.access_not_allowed)
        return Check(cr, 0)
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
        if (cfg.enabled && cfg.creds.isEmpty()) return false
        val p = prefs(c)
        p.edit().putString(K_JSON, text).putLong(K_OK_AT, System.currentTimeMillis()).apply()
        return true
    }

    /** Texto del archivo de acceso que está guardado en este dispositivo (null si nunca se bajó). */
    fun cachedText(c: Context): String? = prefs(c).getString(K_JSON, null)

    /** Tras publicar cambios desde la pantalla de registro: este dispositivo usa ya el archivo nuevo, sin esperar a la caché de GitHub. */
    fun storeLocal(c: Context, text: String) {
        if (parse(text) == null) return
        prefs(c).edit().putString(K_JSON, text).putLong(K_OK_AT, System.currentTimeMillis()).apply()
    }

    /** ¿Se ha desbloqueado ahora mismo con el PIN maestro? */
    fun masterActive(c: Context): Boolean = (activeCred(c)?.level ?: 0) >= 2

    /** Pide el PIN maestro (si no está ya desbloqueado) y luego ejecuta [action]. */
    fun requireMaster(a: Activity, action: () -> Unit) {
        refreshAsync(a)
        val reason = blockReason(a)
        if (reason != null) {
            AlertDialog.Builder(a).setTitle(R.string.access_title).setMessage(reason).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val cfg = config(a)
        if (cfg == null || !hasMaster(cfg)) {
            AlertDialog.Builder(a).setTitle(R.string.access_title).setMessage(R.string.access_log_nomaster).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        if (masterActive(a)) action() else pinDialog(a, onOk = action, onCancel = { }, master = true)
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
        activeUntil = 0L
        activeKey = ""
    }

    // ---- Interfaz ----

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun pinDialog(a: Activity, onOk: () -> Unit, onCancel: () -> Unit, master: Boolean = false, section: String? = null) {
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
        val dialog = AlertDialog.Builder(a)
            .setTitle(R.string.access_title)
            .setMessage(if (master) R.string.access_enter_master else R.string.access_enter_pin)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            val okBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okBtn.setOnClickListener {
                // Antes de comprobar el PIN se baja el archivo otra vez (si hace más de unos segundos), para que un
                // PIN cambiado en GitHub valga al momento y el antiguo deje de valer.
                okBtn.isEnabled = false
                val fresh = System.currentTimeMillis() - lastTry > 15_000L
                refreshAsync(a, force = fresh) {
                    okBtn.isEnabled = true
                    if (!a.isFinishing && !a.isDestroyed && dialog.isShowing) attempt(a, dialog, input, master, section, onOk, onCancel)
                }
            }
        }
        dialog.show()
    }

    private fun attempt(a: Activity, dialog: AlertDialog, input: EditText, master: Boolean, section: String?, onOk: () -> Unit, onCancel: () -> Unit) {
        val cfg = config(a)
        val r = check(a, input.text.toString().trim(), master, section)
        val cr = r.cred
        if (cr != null) {
            failures = 0
            val now = System.currentTimeMillis()
            var until = now + (cr.minutes ?: cfg?.unlockMinutes ?: 10) * 60_000L
            if (cr.expiresAt != 0L) until = minOf(until, cr.expiresAt)
            activeKey = cr.key
            activeUntil = until
            unlockLoaded = true
            saveUnlock(a)
            dialog.dismiss()
            onOk()
        } else {
            // Un código válido pero caducado o sin permiso para este apartado no cuenta como intento fallido.
            if (r.error == R.string.access_wrong) failures++
            input.setText("")
            if (failures >= 5) {
                failures = 0
                lockedOutUntil = System.currentTimeMillis() + 30_000L
                dialog.dismiss()
                Toast.makeText(a, R.string.access_too_many, Toast.LENGTH_LONG).show()
                onCancel()
            } else {
                input.error = a.getString(r.error)
            }
        }
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
                isLocked(a, section) -> pinDialog(a, onOk = { root.visibility = View.VISIBLE }, onCancel = { a.finish() }, section = section)
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
        pinDialog(a, onOk = action, onCancel = { }, section = section)
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
        if ((activeCred(a)?.level ?: 0) >= 2) manageDialog(a)
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
            if (cfg != null) status.append(a.getString(R.string.access_status_codes, cfg.creds.count { it.level == 1 })).append('\n')
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
        items.add(a.getString(R.string.access_add_code)); acts.add { addCodeDialog(a) }
        items.add(a.getString(R.string.access_make_file)); acts.add { makeFileDialog(a) }
        status.append('\n').append(a.getString(R.string.access_names_help, ALL_SECTIONS.joinToString(", ")))
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

    /**
     * Genera la entrada de un código nuevo para la lista "users" del archivo (con su nombre, caducidad y apartados) y la copia
     * al portapapeles. Solo se usa con el PIN maestro; el hash lleva la misma sal que el archivo actual.
     */
    private fun addCodeDialog(a: Activity) {
        fun field(hint: String, number: Boolean) = EditText(a).apply {
            this.hint = hint
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val name = field(a.getString(R.string.access_code_name), false)
        val pin = field(a.getString(R.string.access_pin_hint), true)
        val from = field(a.getString(R.string.access_code_from), false)
        val exp = field(a.getString(R.string.access_code_expires), false)
        val sec = field(a.getString(R.string.access_code_sections), false)
        val box = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(a, 20), dp(a, 12), dp(a, 20), dp(a, 4))
            for (v in listOf(name, pin, from, exp, sec)) addView(v)
        }
        AlertDialog.Builder(a)
            .setTitle(R.string.access_add_code)
            .setMessage(R.string.access_add_help)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = pin.text.toString().trim()
                if (code.length < 3) {
                    Toast.makeText(a, R.string.access_pin_short, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val f = from.text.toString().trim()
                val e = exp.text.toString().trim()
                if ((f.isNotEmpty() && parseDay(f, false) == null) || (e.isNotEmpty() && parseDay(e, true) == null)) {
                    Toast.makeText(a, R.string.access_date_bad, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val salt = config(a)?.salt.orEmpty()
                val o = JSONObject()
                if (name.text.toString().isNotBlank()) o.put("name", name.text.toString().trim())
                o.put("pin_hash", sha256(salt + code))
                if (f.isNotEmpty()) o.put("from", f)
                if (e.isNotEmpty()) o.put("expires", e)
                val secs = sec.text.toString().split(',', ' ', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != "all" }
                if (secs.isNotEmpty()) o.put("sections", JSONArray(secs))
                val json = o.toString(2)
                try {
                    (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("codigo", json))
                } catch (ex: Exception) { }
                AlertDialog.Builder(a)
                    .setTitle(R.string.access_add_done)
                    .setMessage(json + "\n\n" + a.getString(R.string.access_add_where))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
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
                    .put("users", JSONArray())
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
