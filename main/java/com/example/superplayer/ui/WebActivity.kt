package com.example.superplayer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.load
import com.example.superplayer.R
import com.example.superplayer.databinding.ActivityWebBinding
import com.example.superplayer.model.Stream
import com.example.superplayer.player.PlayerActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Navegador web dentro de la app. Además de ver páginas:
 *  - marcadores propios,
 *  - versión de escritorio,
 *  - detecta los vídeos que carga la página (.m3u8, .mpd, .mp4…) y los puede reproducir en el reproductor de la app
 *    (con el mismo Referer de la página, que muchas webs exigen),
 *  - vídeo de la página a pantalla completa y enlaces directos a vídeo abiertos en el reproductor.
 */
class WebActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebBinding
    private var isTv = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var desktop = false
    private var defaultUa = ""
    /** Página actual, guardada desde el hilo principal: shouldInterceptRequest corre en otro hilo y no puede preguntarle la URL al WebView. */
    @Volatile private var currentPage = ""
    private val detected: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(LinkedHashMap<String, String>()) // url -> página en la que apareció

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.example.superplayer.data.AccessControl.guardActivity(this, com.example.superplayer.data.AccessControl.S_WEB)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarInsets(top = binding.webBar, bottom = binding.webBody)
        title = getString(R.string.web_title)

        val wv = binding.webView
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.allowFileAccess = false
        defaultUa = s.userAgentString
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme ?: ""
                if (scheme == "http" || scheme == "https") {
                    if (isMediaUrl(url)) {
                        askPlay(url, view.url ?: url)
                        return true
                    }
                    return false
                }
                openExternalScheme(url)
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (isMediaUrl(url)) {
                    val page = currentPage.ifEmpty { url }
                    val added = synchronized(detected) {
                        if (detected.size < 30 && !detected.containsKey(url)) { detected[url] = page; true } else false
                    }
                    if (added) runOnUiThread { updateVideosButton() }
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                currentPage = url
                hideHome()
                detected.clear()
                updateVideosButton()
                binding.webProgress.visibility = View.VISIBLE
                if (!binding.webUrl.hasFocus()) binding.webUrl.setText(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                currentPage = url
                binding.webProgress.visibility = View.GONE
                if (!binding.webUrl.hasFocus()) binding.webUrl.setText(url)
                updateNavButtons()
                updateSaveButton()
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.webProgress.progress = newProgress
                binding.webProgress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                binding.webFullscreen.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                binding.webFullscreen.visibility = View.VISIBLE
                binding.webContent.visibility = View.GONE
                val c = WindowInsetsControllerCompat(window, binding.root)
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }
        wv.setDownloadListener { url, _, _, _, _ ->
            if (isMediaUrl(url)) askPlay(url, wv.url ?: url) else openUri(url)
        }

        binding.webBack.setOnClickListener { if (wv.canGoBack()) wv.goBack() }
        binding.webForward.setOnClickListener { if (wv.canGoForward()) wv.goForward() }
        binding.webReload.setOnClickListener { wv.reload() }
        binding.webVideos.setOnClickListener { showDetectedVideos() }
        binding.webMenu.setOnClickListener { showMenu(it) }
        binding.webFavsBtn.setOnClickListener { toggleFavStrip() }
        binding.webSaveBtn.setOnClickListener { toggleSaveCurrent() }
        binding.webHomeBtn.setOnClickListener { goHome() }
        binding.webShareBtn.setOnClickListener { shareCurrent() }
        binding.webHomeSearch.setOnClickListener {
            binding.webUrl.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(binding.webUrl, 0)
        }
        binding.webUrl.setOnEditorActionListener { v, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) {
                load(v.text.toString())
                v.clearFocus()
                wv.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
            }
            go
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    homeVisible() && wv.url != null -> hideHome()
                    homeVisible() -> finish()
                    binding.webFavScroll.visibility == View.VISIBLE -> binding.webFavScroll.visibility = View.GONE
                    wv.canGoBack() -> wv.goBack()
                    else -> finish()
                }
            }
        })

        updateNavButtons()
        isTv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        if (isTv) setupTv()
        updateSaveButton()
        val start = intent?.data?.toString() ?: savedInstanceState?.getString("url")
        if (start.isNullOrBlank()) goHome() else load(start)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.url?.let { outState.putString("url", it) }
    }

    override fun onResume() {
        super.onResume()
        if (!homeVisible()) binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("web_browser", Context.MODE_PRIVATE)

    // ---- Navegación ----

    private fun load(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        hideHome()
        val url = when {
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            Regex("^[\\w.-]+:\\d+(/.*)?$").matches(t) -> "http://$t"
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        binding.webView.loadUrl(url)
    }

    private fun updateNavButtons() {
        binding.webBack.alpha = if (binding.webView.canGoBack()) 1f else 0.35f
        binding.webForward.alpha = if (binding.webView.canGoForward()) 1f else 0.35f
    }

    private fun hideCustomView() {
        val v = customView ?: return
        binding.webFullscreen.removeView(v)
        binding.webFullscreen.visibility = View.GONE
        binding.webContent.visibility = View.VISIBLE
        WindowInsetsControllerCompat(window, binding.root).show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        customCallback?.onCustomViewHidden()
        customView = null
        customCallback = null
    }

    private fun openUri(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.web_cant_open, Toast.LENGTH_SHORT).show()
        }
    }

    /** Enlaces que no son web (intent://, tg://, mailto:…): se intentan abrir con la app que toque. */
    private fun openExternalScheme(url: String) {
        try {
            val intent = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.web_cant_open, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Vídeos de la página ----

    private fun isMediaUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return MEDIA_EXT.any { path.endsWith(it) }
    }

    private fun streamTypeFor(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> "HLS"
            path.endsWith(".mpd") -> "DASH"
            path.endsWith(".mp3") || path.endsWith(".aac") -> "MP3"
            else -> "MP4"
        }
    }

    private fun playInApp(url: String, page: String) {
        val name = (binding.webView.title?.takeIf { it.isNotBlank() } ?: Uri.parse(url).lastPathSegment ?: url).take(80)
        val headers = HashMap<String, String>()
        if (page.startsWith("http")) {
            headers["Referer"] = page
            try {
                val u = Uri.parse(page)
                headers["Origin"] = "${u.scheme}://${u.host}"
            } catch (e: Exception) { }
        }
        headers["User-Agent"] = binding.webView.settings.userAgentString
        PlayerActivity.pendingStream = Stream(name = name, type = streamTypeFor(url), url = url, icon = null, category = "Web", headers = headers)
        PlayerActivity.pendingChannelList = emptyList()
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun askPlay(url: String, page: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.web_play_title)
            .setMessage(url.take(160))
            .setPositiveButton(R.string.web_play_yes) { _, _ -> playInApp(url, page) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateVideosButton() {
        binding.webVideos.visibility = if (detected.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showDetectedVideos() {
        if (detected.isEmpty()) {
            Toast.makeText(this, R.string.web_no_videos, Toast.LENGTH_SHORT).show()
            return
        }
        val urls = synchronized(detected) { detected.keys.toList() }
        val labels = urls.map { u ->
            val file = Uri.parse(u).lastPathSegment ?: u
            "${streamTypeFor(u)} · ${Uri.parse(u).host.orEmpty()} · ${file.take(40)}"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.web_videos_title)
            .setItems(labels.toTypedArray()) { _, which -> playInApp(urls[which], detected[urls[which]] ?: urls[which]) }
            .setNegativeButton(R.string.channel_check_close, null)
            .show()
    }

    // ---- Marcadores ----

    private fun bookmarks(): MutableList<Pair<String, String>> {
        val p = prefs()
        if (!p.contains("bookmarks") && !p.getBoolean("seeded", false)) {
            // Primera vez: unos favoritos de ejemplo para que la página de inicio no esté vacía.
            saveBookmarks(listOf(
                "Google" to "https://www.google.com",
                "YouTube" to "https://m.youtube.com",
                "Wikipedia" to "https://es.wikipedia.org",
                "Maps" to "https://maps.google.com",
                "Marca" to "https://www.marca.com",
                "Telegram" to "https://web.telegram.org"
            ))
            p.edit().putBoolean("seeded", true).apply()
        }
        val arr = try { JSONArray(prefs().getString("bookmarks", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map { it.optString("t") to it.optString("u") }.filter { it.second.isNotEmpty() }.toMutableList()
    }

    private fun saveBookmarks(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        for ((t, u) in list) arr.put(JSONObject().put("t", t).put("u", u))
        prefs().edit().putString("bookmarks", arr.toString()).apply()
    }

    private fun addBookmark() {
        if (homeVisible()) { editBookmark(null); return }
        val url = binding.webView.url ?: return
        val list = bookmarks()
        if (list.any { it.second == url }) {
            Toast.makeText(this, R.string.web_bookmark_exists, Toast.LENGTH_SHORT).show()
            return
        }
        list.add(0, (binding.webView.title?.takeIf { it.isNotBlank() } ?: url) to url)
        saveBookmarks(list)
        Toast.makeText(this, R.string.web_bookmark_added, Toast.LENGTH_SHORT).show()
        refreshFavUi()
    }

    // ---- Favoritos: atajos, tira y página de inicio ----

    private fun homeVisible() = binding.webHome.visibility == View.VISIBLE

    private fun refreshFavUi() {
        updateSaveButton()
        if (binding.webFavScroll.visibility == View.VISIBLE) renderFavStrip()
        if (homeVisible()) renderHome()
    }

    private fun updateSaveButton() {
        val url = if (homeVisible()) null else binding.webView.url
        val saved = url != null && bookmarks().any { it.second == url }
        binding.webSaveBtn.setText(if (saved) R.string.web_saved_btn else R.string.web_save_btn)
    }

    private fun toggleSaveCurrent() {
        val url = if (homeVisible()) null else binding.webView.url
        if (url == null) { editBookmark(null); return }
        val list = bookmarks()
        if (list.any { it.second == url }) {
            saveBookmarks(list.filter { it.second != url })
            Toast.makeText(this, R.string.web_fav_removed, Toast.LENGTH_SHORT).show()
            refreshFavUi()
        } else addBookmark()
    }

    private fun toggleFavStrip() {
        val sc = binding.webFavScroll
        if (sc.visibility == View.VISIBLE) sc.visibility = View.GONE
        else { renderFavStrip(); sc.visibility = View.VISIBLE }
    }

    private fun renderFavStrip() {
        val strip = binding.webFavStrip
        strip.removeAllViews()
        val d = resources.displayMetrics.density
        val list = bookmarks()
        if (list.isEmpty()) {
            strip.addView(android.widget.TextView(this).apply {
                setText(R.string.web_no_favs)
                setTextColor(androidx.core.content.ContextCompat.getColor(this@WebActivity, R.color.on_surface_muted))
                setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            })
            return
        }
        for (b in list) {
            val chip = android.widget.TextView(this).apply {
                text = b.first.take(22)
                textSize = 13f
                maxLines = 1
                setTextColor(androidx.core.content.ContextCompat.getColor(this@WebActivity, R.color.on_background))
                setBackgroundResource(R.drawable.bg_web_chip)
                setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
                isFocusable = true
                isClickable = true
                setOnClickListener { binding.webFavScroll.visibility = View.GONE; load(b.second) }
                setOnLongClickListener { favOptions(b); true }
            }
            strip.addView(chip, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0) })
        }
    }

    private fun favOptions(b: Pair<String, String>) {
        AlertDialog.Builder(this)
            .setTitle(b.first.take(50))
            .setItems(arrayOf(getString(R.string.web_fav_edit_opt), getString(R.string.web_fav_delete_opt))) { _, which ->
                if (which == 0) editBookmark(b)
                else {
                    saveBookmarks(bookmarks().filter { it.second != b.second })
                    refreshFavUi()
                }
            }
            .show()
    }

    /** Añade un favorito a mano (old = null) o edita su nombre y dirección. */
    private fun editBookmark(old: Pair<String, String>?) {
        val d = resources.displayMetrics.density
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        val nameEt = android.widget.EditText(this).apply { hint = getString(R.string.web_fav_name); setText(old?.first ?: ""); setSingleLine() }
        val urlEt = android.widget.EditText(this).apply {
            hint = getString(R.string.web_fav_url)
            setText(old?.second ?: "")
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        col.addView(nameEt)
        col.addView(urlEt)
        AlertDialog.Builder(this)
            .setTitle(if (old == null) R.string.web_fav_add else R.string.web_fav_edit)
            .setView(col)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                var u = urlEt.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
                val n = nameEt.text.toString().trim().ifEmpty { Uri.parse(u).host?.removePrefix("www.") ?: u }
                val list = bookmarks()
                val i = if (old == null) -1 else list.indexOfFirst { it.second == old.second }
                if (i >= 0) list[i] = n to u else list.add(n to u)
                saveBookmarks(list)
                refreshFavUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareCurrent() {
        val url = (if (homeVisible()) null else binding.webView.url) ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, binding.webView.title ?: url)
            .putExtra(Intent.EXTRA_TEXT, url)
        try { startActivity(Intent.createChooser(send, null)) } catch (e: Exception) { }
    }

    // ---- Página de inicio ----

    private fun homeSetting(): String = prefs().getString("home", "") ?: ""

    /** Va a la página de inicio elegida: la cuadrícula de favoritos (por defecto) o la dirección que se haya puesto. */
    private fun goHome() {
        val h = homeSetting()
        if (h.isBlank()) showHome() else load(h)
    }

    private fun showHome() {
        binding.webFavScroll.visibility = View.GONE
        binding.webHome.visibility = View.VISIBLE
        binding.webView.visibility = View.INVISIBLE // así el mando no puede enfocar el navegador oculto
        binding.webView.onPause()
        binding.webUrl.setText("")
        binding.webUrl.clearFocus()
        renderHome()
        updateSaveButton()
        if (isTv) binding.webHome.post { binding.webHomeGrid.getChildAt(0)?.requestFocus() }
    }

    private fun hideHome() {
        if (!homeVisible()) return
        binding.webHome.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.webView.onResume()
        updateSaveButton()
        if (isTv) binding.webView.post { binding.webView.requestFocus() }
    }

    private fun tileColor(seed: String): Int {
        val colors = intArrayOf(0xFFE57373.toInt(), 0xFF64B5F6.toInt(), 0xFF81C784.toInt(), 0xFFFFB74D.toInt(),
            0xFFBA68C8.toInt(), 0xFF4DB6AC.toInt(), 0xFFF06292.toInt(), 0xFF7986CB.toInt())
        return colors[(seed.hashCode() and 0x7fffffff) % colors.size]
    }

    private fun renderHome() {
        val grid = binding.webHomeGrid
        grid.removeAllViews()
        val d = resources.displayMetrics.density
        val widthDp = resources.displayMetrics.widthPixels / d
        val cols = maxOf(3, minOf(8, ((widthDp - 32) / 92).toInt()))
        grid.columnCount = cols
        val items = bookmarks().map { it as Pair<String, String>? } + listOf<Pair<String, String>?>(null)
        for (b in items) {
            val tile = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.bg_web_chip)
            }
            val circle = android.widget.FrameLayout(this).apply {
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (b == null) 0xFF9E9E9E.toInt() else tileColor(b.second))
                }
                background = bg
                tag = bg
            }
            val letter = android.widget.TextView(this).apply {
                text = if (b == null) "+" else b.first.trim().take(1).uppercase().ifEmpty { "?" }
                textSize = 24f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
            }
            circle.addView(letter, android.widget.FrameLayout.LayoutParams(-1, -1))
            if (b != null) {
                val host = try { Uri.parse(b.second).host } catch (e: Exception) { null }
                if (!host.isNullOrEmpty()) {
                    val icon = android.widget.ImageView(this).apply {
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
                    }
                    circle.addView(icon, android.widget.FrameLayout.LayoutParams(-1, -1))
                    icon.load("https://www.google.com/s2/favicons?domain=$host&sz=128") {
                        listener(onSuccess = { _, _ ->
                            (circle.tag as? android.graphics.drawable.GradientDrawable)?.setColor(0xFFFFFFFF.toInt())
                            letter.visibility = View.GONE
                        })
                    }
                }
            }
            tile.addView(circle, android.widget.LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()))
            tile.addView(android.widget.TextView(this).apply {
                text = if (b == null) getString(R.string.web_fav_add) else b.first
                textSize = 12f
                maxLines = 2
                gravity = android.view.Gravity.CENTER
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(androidx.core.content.ContextCompat.getColor(this@WebActivity, R.color.on_background))
                setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), 0)
            }, android.widget.LinearLayout.LayoutParams(-1, -2))
            tile.setOnClickListener { if (b == null) editBookmark(null) else load(b.second) }
            if (b != null) tile.setOnLongClickListener { favOptions(b); true }
            val lp = android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                setMargins((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
            }
            grid.addView(tile, lp)
        }
    }

    /** Elegir qué se abre como página de inicio: la cuadrícula de favoritos, una web de la lista o la dirección que quieras. */
    private fun chooseHomePage() {
        val current = binding.webView.url?.takeIf { !homeVisible() }
        val labels = ArrayList<String>()
        labels.add(getString(R.string.web_home_grid))
        labels.add("Google")
        labels.add("DuckDuckGo")
        if (current != null) labels.add(getString(R.string.web_home_current))
        labels.add(getString(R.string.web_home_other))
        AlertDialog.Builder(this)
            .setTitle(R.string.web_home_pick)
            .setItems(labels.toTypedArray()) { _, which ->
                fun save(v: String) {
                    prefs().edit().putString("home", v).apply()
                    Toast.makeText(this, R.string.web_home_saved, Toast.LENGTH_SHORT).show()
                }
                when (labels[which]) {
                    getString(R.string.web_home_grid) -> save("")
                    "Google" -> save("https://www.google.com")
                    "DuckDuckGo" -> save("https://duckduckgo.com")
                    getString(R.string.web_home_current) -> save(current ?: "")
                    else -> {
                        val et = android.widget.EditText(this).apply {
                            hint = getString(R.string.web_fav_url)
                            setText(homeSetting())
                            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                            setSingleLine()
                        }
                        val box = android.widget.FrameLayout(this).apply {
                            val m = (20 * resources.displayMetrics.density).toInt()
                            setPadding(m, 8, m, 0)
                            addView(et)
                        }
                        AlertDialog.Builder(this)
                            .setTitle(R.string.web_home_other)
                            .setView(box)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                var u = et.text.toString().trim()
                                if (u.isNotEmpty() && !u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
                                save(u)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBookmarks() {
        val list = bookmarks()
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.web_no_bookmarks, Toast.LENGTH_LONG).show()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.web_bookmarks)
            .setItems(list.map { it.first.take(60) + "\n" + it.second.take(70) }.toTypedArray()) { _, which -> load(list[which].second) }
            .setNegativeButton(R.string.channel_check_close, null)
            .create()
        dialog.show()
        // Mantener pulsado un marcador lo borra.
        dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
            val item = list[position]
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.web_bookmark_delete, item.first.take(50)))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    saveBookmarks(bookmarks().filter { it.second != item.second })
                    dialog.dismiss()
                    showBookmarks()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    // ---- Menú ----

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.web_bookmarks)
        menu.menu.add(0, 2, 1, R.string.web_add_bookmark)
        menu.menu.add(0, 3, 2, getString(R.string.web_videos_menu, detected.size))
        menu.menu.add(0, 4, 3, R.string.web_desktop).apply { isCheckable = true; isChecked = desktop }
        menu.menu.add(0, 5, 4, R.string.web_home)
        menu.menu.add(0, 6, 5, R.string.web_open_external)
        menu.menu.add(0, 8, 6, R.string.web_home_set)
        if (isTv) menu.menu.add(0, 9, 8, R.string.web_cursor_opt).apply { isCheckable = true; isChecked = cursorEnabled() }
        menu.menu.add(0, 7, 9, R.string.web_clear)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showBookmarks()
                2 -> addBookmark()
                3 -> showDetectedVideos()
                4 -> {
                    desktop = !desktop
                    binding.webView.settings.userAgentString = if (desktop) DESKTOP_UA else defaultUa
                    binding.webView.reload()
                    hideHome()
                }
                5 -> goHome()
                8 -> chooseHomePage()
                9 -> {
                    prefs().edit().putBoolean("cursor", !cursorEnabled()).apply()
                    if (cursorEnabled()) showCursor() else cursorView?.visibility = View.GONE
                }
                6 -> (if (homeVisible()) null else binding.webView.url)?.let { openUri(it) }
                7 -> AlertDialog.Builder(this)
                    .setMessage(R.string.web_clear_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        binding.webView.clearCache(true)
                        binding.webView.clearHistory()
                        CookieManager.getInstance().removeAllCookies(null)
                        WebStorage.getInstance().deleteAllData()
                        Toast.makeText(this, R.string.web_cleared, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            true
        }
        menu.show()
    }

    // -----------------------------------------------------------------
    // Mando de la tele: cursor que se mueve con las flechas
    //  - Flechas: mueven el cursor (cuanto más tiempo se mantienen, más rápido).
    //  - Cursor en el borde de arriba/abajo: la página se desplaza. Arriba del todo: salta a los atajos.
    //  - OK: clic (mantener: pulsación larga). Canal +/-: página arriba/abajo.
    //  - Rebobinar / Avanzar: atrás / adelante en el historial. Menú: abre el menú del navegador.
    // En los atajos, la tira de favoritos y la página de inicio se navega con las flechas normales.
    // -----------------------------------------------------------------

    private class CursorView(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAAFF6B4A.toInt()
        }
        private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.5f * d
        }
        private val dark = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 5f * d
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val c = width / 2f
            val r = c - 4f * d
            canvas.drawCircle(c, c, r, dark)
            canvas.drawCircle(c, c, r, fill)
            canvas.drawCircle(c, c, r, ring)
            canvas.drawCircle(c, c, 2.5f * d, ring)
        }
    }

    private var cursorView: CursorView? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var okDownAt = 0L
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val fadeCursor = Runnable { cursorView?.animate()?.alpha(0.4f)?.setDuration(300)?.start() }

    private fun cursorEnabled() = prefs().getBoolean("cursor", true)

    private fun setupTv() {
        val d = resources.displayMetrics.density
        val size = (44 * d).toInt()
        val cv = CursorView(this)
        cv.visibility = View.GONE
        cv.isFocusable = false
        binding.webBody.addView(cv, android.widget.FrameLayout.LayoutParams(size, size))
        cursorView = cv
        val wv = binding.webView
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.setOnFocusChangeListener { _, has ->
            if (has && cursorEnabled()) showCursor() else cv.visibility = View.GONE
        }
        binding.webUrl.setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(binding.webUrl, 0)
        }
        binding.webBody.post {
            cursorX = binding.webBody.width / 2f
            cursorY = binding.webBody.height / 2f
            placeCursor()
        }
        if (!prefs().getBoolean("tvhint", false)) {
            prefs().edit().putBoolean("tvhint", true).apply()
            Toast.makeText(this, R.string.web_cursor_hint, Toast.LENGTH_LONG).show()
        }
    }

    private fun placeCursor() {
        val cv = cursorView ?: return
        val w = binding.webBody.width.toFloat()
        val h = binding.webBody.height.toFloat()
        cursorX = cursorX.coerceIn(0f, maxOf(0f, w))
        cursorY = cursorY.coerceIn(0f, maxOf(0f, h))
        cv.translationX = cursorX - cv.width.coerceAtLeast(cv.layoutParams.width) / 2f
        cv.translationY = cursorY - cv.height.coerceAtLeast(cv.layoutParams.height) / 2f
    }

    private fun showCursor() {
        val cv = cursorView ?: return
        cv.visibility = View.VISIBLE
        cv.animate().cancel()
        cv.alpha = 1f
        placeCursor()
        uiHandler.removeCallbacks(fadeCursor)
        uiHandler.postDelayed(fadeCursor, 3500)
    }

    private fun scrollPage(dy: Int) {
        binding.webView.scrollBy(0, dy)
    }

    private fun moveCursorKey(code: Int, repeat: Int) {
        val d = resources.displayMetrics.density
        val step = (12f + minOf(repeat, 25) * 3.2f) * d
        val zone = 56f * d
        val w = binding.webBody.width.toFloat()
        val h = binding.webBody.height.toFloat()
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorX -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (cursorX >= w - 8f * d) binding.webView.scrollBy(step.toInt(), 0) else cursorX += step
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (cursorY >= h - zone) scrollPage((step * 1.6f).toInt()) else cursorY += step
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (cursorY <= zone) {
                    if (binding.webView.scrollY > 0) scrollPage(-(step * 1.6f).toInt())
                    else if (cursorY <= 10f * d) {
                        leaveToBars()
                        return
                    } else cursorY -= step
                } else cursorY -= step
            }
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && cursorX <= 0f && binding.webView.scrollX > 0) binding.webView.scrollBy(-step.toInt(), 0)
        showCursor()
    }

    /** Cursor en el borde de arriba y sin más página que subir: el foco pasa a los atajos, en la columna más cercana. */
    private fun leaveToBars() {
        val btns = listOf<View>(binding.webFavsBtn, binding.webSaveBtn, binding.webHomeBtn, binding.webShareBtn)
        val w = binding.webBody.width.coerceAtLeast(1)
        val i = (cursorX / w * btns.size).toInt().coerceIn(0, btns.size - 1)
        btns[i].requestFocus()
    }

    private fun clickAtCursor(long: Boolean) {
        val wv = binding.webView
        val x = cursorX
        val y = cursorY
        val t = SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        wv.dispatchTouchEvent(down)
        down.recycle()
        cursorView?.animate()?.scaleX(0.6f)?.scaleY(0.6f)?.setDuration(90)?.withEndAction {
            cursorView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
        }?.start()
        uiHandler.postDelayed({
            val t2 = SystemClock.uptimeMillis()
            val up = android.view.MotionEvent.obtain(t, t2, android.view.MotionEvent.ACTION_UP, x, y, 0)
            up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            wv.dispatchTouchEvent(up)
            up.recycle()
        }, if (long) 700L else 60L)
        showCursor()
    }

    private fun handleCursorKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) moveCursorKey(event.keyCode, event.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down) {
                    if (event.repeatCount == 0) okDownAt = SystemClock.uptimeMillis()
                } else {
                    clickAtCursor(SystemClock.uptimeMillis() - okDownAt >= 650L)
                }
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (down) scrollPage(-(binding.webView.height * 0.8f).toInt())
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (down) scrollPage((binding.webView.height * 0.8f).toInt())
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down && binding.webView.canGoBack()) binding.webView.goBack()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down && binding.webView.canGoForward()) binding.webView.goForward()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (down) showMenu(binding.webMenu)
                return true
            }
        }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTv && customView == null && !homeVisible() && cursorEnabled() &&
            binding.webView.hasFocus() && handleCursorKey(event)
        ) return true
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private val MEDIA_EXT = listOf(".m3u8", ".mpd", ".mp4", ".mkv", ".m4v", ".mp3", ".aac", ".ts")
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
