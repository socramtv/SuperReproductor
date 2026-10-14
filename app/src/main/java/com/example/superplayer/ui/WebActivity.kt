package com.example.superplayer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
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
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var desktop = false
    private var defaultUa = ""
    private val detected: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(LinkedHashMap<String, String>()) // url -> página en la que apareció

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarInsets(top = binding.webBar, bottom = binding.webView)
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
                    val page = view.url ?: url
                    val added = synchronized(detected) {
                        if (detected.size < 30 && !detected.containsKey(url)) { detected[url] = page; true } else false
                    }
                    if (added) runOnUiThread { updateVideosButton() }
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                detected.clear()
                updateVideosButton()
                binding.webProgress.visibility = View.VISIBLE
                if (!binding.webUrl.hasFocus()) binding.webUrl.setText(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.webProgress.visibility = View.GONE
                if (!binding.webUrl.hasFocus()) binding.webUrl.setText(url)
                updateNavButtons()
                prefs().edit().putString("last", url).apply()
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
                    wv.canGoBack() -> wv.goBack()
                    else -> finish()
                }
            }
        })

        updateNavButtons()
        val start = intent?.data?.toString() ?: savedInstanceState?.getString("url") ?: prefs().getString("last", null)
        if (start.isNullOrBlank()) {
            load(HOME)
        } else {
            load(start)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.url?.let { outState.putString("url", it) }
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
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
        val url = binding.webView.url ?: return
        val list = bookmarks()
        if (list.any { it.second == url }) {
            Toast.makeText(this, R.string.web_bookmark_exists, Toast.LENGTH_SHORT).show()
            return
        }
        list.add(0, (binding.webView.title?.takeIf { it.isNotBlank() } ?: url) to url)
        saveBookmarks(list)
        Toast.makeText(this, R.string.web_bookmark_added, Toast.LENGTH_SHORT).show()
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
        menu.menu.add(0, 7, 6, R.string.web_clear)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showBookmarks()
                2 -> addBookmark()
                3 -> showDetectedVideos()
                4 -> {
                    desktop = !desktop
                    binding.webView.settings.userAgentString = if (desktop) DESKTOP_UA else defaultUa
                    binding.webView.reload()
                }
                5 -> load(HOME)
                6 -> binding.webView.url?.let { openUri(it) }
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

    companion object {
        private const val HOME = "https://www.google.com"
        private val MEDIA_EXT = listOf(".m3u8", ".mpd", ".mp4", ".mkv", ".m4v", ".mp3", ".aac", ".ts")
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
