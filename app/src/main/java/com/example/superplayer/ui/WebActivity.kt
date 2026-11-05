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
import android.webkit.JavascriptInterface
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
    /** Una pestaña: su WebView, la página actual (guardada desde el hilo principal: shouldInterceptRequest corre en otro hilo) y sus vídeos detectados. */
    private class Tab(val view: WebView) {
        @Volatile var page = ""
        /** Dirección que aún no se ha cargado (pestañas restauradas: se cargan al abrirlas). */
        var pending: String? = null
        var bannerShown = false
        /** Abierta por una ventana emergente pedida por la página: no es una pestaña en blanco. */
        var popup = false
        @Volatile var blocked = 0
        /** Últimas peticiones de la página (sin imágenes/estilos/fuentes ni anuncios), para el diagnóstico. */
        val requests: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
        @Volatile var lastTouch = 0L
        /** Cuerpos de respuestas de APIs de stream (diagnóstico), aparte para que el ruido no los desplace. */
        val resp: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
        /** Cabeceras reales (Referer/Origin) con que la página pidió cada vídeo. */
        val hdrs: MutableMap<String, Map<String, String>> = java.util.concurrent.ConcurrentHashMap()
        val detected: MutableMap<String, String> =
            java.util.Collections.synchronizedMap(LinkedHashMap<String, String>()) // url -> página en la que apareció
    }

    private val tabs = ArrayList<Tab>()
    private var curIdx = 0
    private val curTab: Tab get() = tabs[curIdx]
    private val webView: WebView get() = tabs[curIdx].view
    private val detected: MutableMap<String, String> get() = tabs[curIdx].detected

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.example.superplayer.data.AccessControl.guardActivity(this, com.example.superplayer.data.AccessControl.S_WEB)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarInsets(top = binding.webBar, bottom = binding.webBody)
        title = getString(R.string.web_title)

        isTv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

        // Peticiones que hacen los Service Workers de la página (algunos reproductores cargan el vídeo desde ahí).
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            try {
                android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        if (looksLikeMedia(url) && tabs.isNotEmpty()) addDetected(tabs[curIdx.coerceIn(0, tabs.size - 1)], url)
                        return null
                    }
                })
            } catch (e: Exception) { }
        }

        binding.webBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        binding.webForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        binding.webReload.setOnClickListener { webView.reload() }
        binding.webVideos.setOnClickListener { showDetectedVideos() }
        binding.webMenu.setOnClickListener { showMenu(it) }
        binding.webAppHome.setOnClickListener {
            // A la pantalla principal de la app de una vez (sin volver atrás paso a paso).
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
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
                webView.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
            }
            go
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    binding.webVideoBanner.visibility == View.VISIBLE -> hideBanner()
                    binding.webFindBar.visibility == View.VISIBLE -> hideFind()
                    binding.webTabsPanel.visibility == View.VISIBLE -> binding.webTabsPanel.visibility = View.GONE
                    homeVisible() && webView.url != null -> hideHome()
                    homeVisible() -> finish()
                    binding.webFavScroll.visibility == View.VISIBLE -> binding.webFavScroll.visibility = View.GONE
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })

        binding.webFind.setOnClickListener { if (binding.webFindBar.visibility == View.VISIBLE) hideFind() else showFind() }
        binding.webFindClose.setOnClickListener { hideFind() }
        binding.webFindNext.setOnClickListener { webView.findNext(true) }
        binding.webFindPrev.setOnClickListener { webView.findNext(false) }
        binding.webFindEt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(a: CharSequence?, st: Int, b: Int, c: Int) {
                val q = a?.toString().orEmpty()
                if (q.isEmpty()) { webView.clearMatches(); binding.webFindCount.text = "" } else webView.findAllAsync(q)
            }
            override fun afterTextChanged(e: android.text.Editable?) {}
        })
        binding.webFindEt.setOnEditorActionListener { _, _, _ -> webView.findNext(true); true }
        binding.webTabsBtn.setOnClickListener { toggleTabs() }
        binding.webVideoClose.setOnClickListener { hideBanner() }
        binding.webVideoPlay.setOnClickListener {
            hideBanner()
            val urls = synchronized(detected) { detected.keys.toList() }
            if (urls.size == 1) playInApp(urls[0], detected[urls[0]] ?: urls[0]) else showDetectedVideos()
        }

        if (isTv) setupTv()
        // Pestañas de la vez anterior (se cargan al abrirlas); si se sale (p. ej. con la casita 🏠 de la app) y se vuelve, sigue donde se estaba.
        val saved = loadSavedTabs()
        for (u in saved.first) newTab(u.ifBlank { null }, select = false)
        val explicit = sharedTarget(intent)
        if (!explicit.isNullOrBlank()) { newTab(null); load(explicit) }
        else if (tabs.isNotEmpty()) switchTo(saved.second.coerceIn(0, tabs.size - 1))
        else { newTab(null); goHome() }
        updateNavButtons()
        updateSaveButton()
    }

    // ---- Pestañas ----

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configure(t: Tab) {
        val wv = t.view
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
        if (defaultUa.isEmpty()) defaultUa = s.userAgentString
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.addJavascriptInterface(Scout(t), "SocramScout")
        if (isTv) {
            wv.isFocusable = true
            wv.isFocusableInTouchMode = true
            wv.setOnFocusChangeListener { _, has ->
                if (has && cursorEnabled()) showCursor() else cursorView?.visibility = View.GONE
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme ?: ""
                if (scheme == "http" || scheme == "https") {
                    if (request.isForMainFrame && shouldBlockNavigation(t, request)) return true
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
                if (adBlockOn() && !request.isForMainFrame && isAdHost(request.url.host)) {
                    t.blocked++
                    return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                if (!request.isForMainFrame && !STATIC_RE.containsMatchIn(url.substringBefore('?').lowercase()) &&
                    !NOISE_RE.containsMatchIn(url)) {
                    synchronized(t.requests) {
                        if (t.requests.size >= 150) t.requests.removeAt(0)
                        t.requests.add("${request.method} ${url.take(300)}" + (if (request.requestHeaders?.containsKey("Range") == true) "  [Range]" else ""))
                    }
                }
                if (looksLikeMedia(url)) {
                    request.requestHeaders?.let { h ->
                        val keep = HashMap<String, String>()
                        for ((k, v) in h) if (k.equals("Referer", true) || k.equals("Origin", true)) keep[k] = v
                        if (keep.isNotEmpty() && t.hdrs.size < 200) t.hdrs[url] = keep
                    }
                    addDetected(t, url)
                } else if (!request.isForMainFrame && request.requestHeaders?.containsKey("Range") == true &&
                    url.startsWith("http") && !STATIC_RE.containsMatchIn(url.substringBefore('?').lowercase())
                ) addDetected(t, url) // los reproductores piden el vídeo por tramos (cabecera Range) aunque la dirección no tenga extensión
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                t.page = url
                t.bannerShown = false
                t.popup = false
                t.detected.clear(); t.hdrs.clear()
                if (t === curTab) {
                    hideHome()
                    updateVideosButton()
                    hideBanner()
                    binding.webProgress.visibility = View.VISIBLE
                    if (!binding.webUrl.hasFocus()) binding.webUrl.setText(url)
                }
                view.evaluateJavascript(SCOUT_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                t.page = url
                view.evaluateJavascript(SCOUT_JS, null)
                if (adBlockOn()) view.evaluateJavascript(HIDE_ADS_JS, null)
                addHistory(url, view.title.orEmpty())
                if (t === curTab) {
                    binding.webProgress.visibility = View.GONE
                    if (!binding.webUrl.hasFocus()) binding.webUrl.setText(url)
                    updateNavButtons()
                    updateSaveButton()
                }
                refreshTabsUi()
            }
        }
        wv.settings.setSupportMultipleWindows(true)
        wv.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN || ev.action == android.view.MotionEvent.ACTION_UP) t.lastTouch = SystemClock.uptimeMillis()
            false
        }
        wv.webChromeClient = object : WebChromeClient() {
            /** window.open / target=_blank: con el bloqueador, solo si lo ha tocado el usuario (y se abre en una pestaña nueva). */
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (adBlockOn() && !isUserGesture) { t.blocked++; return false }
                val nt = newTab(null, popup = true) ?: return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = nt.view
                resultMsg.sendToTarget()
                return true
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (t !== curTab) return
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
        wv.setFindListener { active, total, _ ->
            if (t === curTab) binding.webFindCount.text = if (total == 0) "0/0" else "${active + 1}/$total"
        }
        // Pulsación larga en un enlace: abrirlo en otra pestaña o copiarlo.
        wv.setOnLongClickListener {
            val r = wv.hitTestResult
            val link = r.extra
            if ((r.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && !link.isNullOrBlank()) {
                AlertDialog.Builder(this)
                    .setTitle(link.take(80))
                    .setItems(arrayOf(getString(R.string.web_link_newtab), getString(R.string.web_link_here), getString(R.string.web_link_copy))) { _, i ->
                        when (i) {
                            0 -> newTab(link)
                            1 -> load(link)
                            else -> copyText(link, R.string.web_link_copied)
                        }
                    }.show()
                true
            } else false
        }
        applySavedAgent()
    }

    private fun copyText(text: String, msg: Int) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", text))
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun newTab(url: String?, select: Boolean = true, popup: Boolean = false): Tab? {
        if (tabs.size >= 20) { Toast.makeText(this, R.string.web_tabs_max, Toast.LENGTH_SHORT).show(); return null }
        val wv = WebView(this)
        wv.visibility = View.GONE
        binding.webBody.addView(wv, 0, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val t = Tab(wv)
        t.pending = url
        t.popup = popup
        tabs.add(t)
        configure(t)
        if (select) switchTo(tabs.size - 1) else refreshTabsUi()
        return t
    }

    private fun switchTo(i: Int) {
        if (i !in tabs.indices) return
        hideFind()
        hideBanner()
        tabs.forEachIndexed { j, x -> if (j != i) { x.view.visibility = View.GONE; x.view.onPause() } }
        curIdx = i
        val t = tabs[i]
        binding.webHome.visibility = View.GONE
        t.view.visibility = View.VISIBLE
        t.view.onResume()
        val p = t.pending
        t.pending = null
        if (p != null) t.view.loadUrl(p)
        if (t.view.url == null && p == null && !t.popup) showHome()
        else if (!binding.webUrl.hasFocus()) binding.webUrl.setText(t.view.url ?: p.orEmpty())
        binding.webProgress.visibility = View.GONE
        updateNavButtons()
        updateSaveButton()
        updateVideosButton()
        refreshTabsUi()
        if (isTv && binding.webTabsPanel.visibility != View.VISIBLE) t.view.post { t.view.requestFocus() }
    }

    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs.removeAt(i)
        binding.webBody.removeView(t.view)
        t.view.destroy()
        if (tabs.isEmpty()) { curIdx = 0; newTab(null); return }
        curIdx = curIdx.coerceIn(0, tabs.size - 1)
        val ni = when {
            i < curIdx -> curIdx - 1
            else -> minOf(i, tabs.size - 1).let { if (i == curIdx) it else curIdx }
        }
        switchTo(ni.coerceIn(0, tabs.size - 1))
    }

    private fun toggleTabs() {
        val sc = binding.webTabsPanel
        if (sc.visibility == View.VISIBLE) sc.visibility = View.GONE
        else { renderTabs(); sc.visibility = View.VISIBLE }
    }

    private fun refreshTabsUi() {
        binding.webTabsBtn.text = "🗂 ${tabs.size}"
        if (binding.webTabsPanel.visibility == View.VISIBLE) renderTabs()
    }

    private fun tabLabel(t: Tab): String {
        val u = t.view.url ?: t.pending
        if (u == null) return getString(R.string.web_tab_blank)
        return (t.view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: Uri.parse(u).host?.removePrefix("www.") ?: u).take(22)
    }

    private fun renderTabs() {
        val strip = binding.webTabsStrip
        strip.removeAllViews()
        val d = resources.displayMetrics.density
        val fg = androidx.core.content.ContextCompat.getColor(this, R.color.on_background)
        val accent = androidx.core.content.ContextCompat.getColor(this, R.color.primary)
        fun lp() = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0) }
        tabs.forEachIndexed { i, t ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(android.widget.TextView(this).apply {
                text = tabLabel(t)
                textSize = 13f
                maxLines = 1
                setTextColor(if (i == curIdx) accent else fg)
                if (i == curIdx) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_web_chip)
                setPadding((14 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
                isFocusable = true
                isClickable = true
                setOnClickListener { switchTo(i) }
            })
            row.addView(android.widget.TextView(this).apply {
                text = "✕"
                textSize = 13f
                setTextColor(fg)
                setBackgroundResource(R.drawable.bg_web_chip)
                setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
                isFocusable = true
                isClickable = true
                setOnClickListener { closeTab(i) }
            })
            strip.addView(row, lp())
        }
        strip.addView(android.widget.TextView(this).apply {
            setText(R.string.web_tab_new_chip)
            textSize = 13f
            setTextColor(accent)
            setBackgroundResource(R.drawable.bg_web_chip)
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            isFocusable = true
            isClickable = true
            setOnClickListener { newTab(null) }
        }, lp())
    }

    /** Guarda las pestañas abiertas para reabrirlas al volver a Web. */
    private fun saveTabs() {
        val arr = JSONArray()
        for (t in tabs) {
            val u = t.view.url ?: t.pending
            arr.put(if (!u.isNullOrBlank() && (u.startsWith("http://") || u.startsWith("https://"))) u else "")
        }
        val cur = tabs.getOrNull(curIdx)
        val keep = cur != null && !homeVisible() && !cur.view.url.isNullOrBlank()
        prefs().edit().putString("tabs", arr.toString()).putInt("tab_cur", curIdx)
            .putString("last_url", if (keep) cur!!.view.url else "").apply()
    }

    private fun loadSavedTabs(): Pair<List<String>, Int> {
        val list = ArrayList<String>()
        try {
            val arr = JSONArray(prefs().getString("tabs", "[]"))
            for (i in 0 until arr.length()) list.add(arr.optString(i))
        } catch (e: Exception) { }
        if (list.isEmpty()) prefs().getString("last_url", "")?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        return list.take(12) to prefs().getInt("tab_cur", 0)
    }

    /** Diagnóstico: qué pide la página (para ver de dónde sale el vídeo cuando no se detecta solo). */
    private fun showRequests() {
        val list = synchronized(curTab.resp) { curTab.resp.toList() } + synchronized(curTab.requests) { curTab.requests.toList() }
        val text = if (list.isEmpty()) getString(R.string.web_requests_none) else list.joinToString("\n\n")
        val tv = android.widget.TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            val d = resources.displayMetrics.density
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.web_requests_menu)
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton(R.string.web_requests_copy) { _, _ -> copyText(text, R.string.web_requests_copied) }
            .setNegativeButton(R.string.channel_check_close, null)
            .show()
    }

    // ---- Bloqueador de anuncios y ventanas emergentes ----

    private fun adBlockOn() = prefs().getBoolean("adblock", true)

    /** El anfitrión es (o cuelga de) uno de los dominios de anuncios/rastreo conocidos. */
    private fun isAdHost(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (true) {
            if (h in AD_HOSTS) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    private fun siteOf(host: String?): String {
        val parts = host.orEmpty().lowercase().split('.')
        return if (parts.size <= 2) parts.joinToString(".") else parts.takeLast(2).joinToString(".")
    }

    /** Navegaciones a las que la página nos lleva sola (popunders, redirecciones de anuncios): sin toque del usuario y a otro sitio. */
    private fun shouldBlockNavigation(t: Tab, request: WebResourceRequest): Boolean {
        if (!adBlockOn()) return false
        val host = request.url.host
        if (isAdHost(host)) { t.blocked++; return true }
        if (request.hasGesture() || request.isRedirect) return false
        val cur = Uri.parse(t.page.ifEmpty { return false }).host
        if (cur == null || siteOf(cur) == siteOf(host)) return false
        if (SystemClock.uptimeMillis() - t.lastTouch < 1500) return false
        t.blocked++
        return true
    }

    // ---- Buscar en la página ----

    private fun showFind() {
        binding.webFindBar.visibility = View.VISIBLE
        binding.webFindEt.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .showSoftInput(binding.webFindEt, 0)
    }

    private fun hideFind() {
        if (binding.webFindBar.visibility != View.VISIBLE) return
        if (tabs.isNotEmpty()) webView.clearMatches()
        binding.webFindBar.visibility = View.GONE
        binding.webFindCount.text = ""
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(binding.webFindEt.windowToken, 0)
    }

    /** Texto compartido desde otra app: el primer enlace que lleve, o si no, se busca el texto. */
    private fun sharedTarget(i: Intent?): String? {
        if (i == null) return null
        i.data?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (i.action != Intent.ACTION_SEND) return null
        val text = i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return null
        return Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', ']') ?: text.take(200)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = sharedTarget(intent) ?: return
        if (tabs.isEmpty()) return
        // Se abre en una pestaña nueva para no perder lo que se estuviera viendo.
        newTab(null) ?: return
        load(target)
        Toast.makeText(this, R.string.web_shared_opened, Toast.LENGTH_SHORT).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.url?.let { outState.putString("url", it) }
    }

    override fun onResume() {
        super.onResume()
        if (!homeVisible()) webView.onResume()
    }

    override fun onPause() {
        saveTabs()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        for (t in tabs) {
            (t.view.parent as? ViewGroup)?.removeView(t.view)
            t.view.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("web_browser", Context.MODE_PRIVATE)

    // ---- Última página y historial ----

    private class HistEntry(val url: String, val title: String, val at: Long)

    private fun history(): MutableList<HistEntry> = try {
        val arr = JSONArray(prefs().getString("history", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            HistEntry(o.optString("u"), o.optString("t"), o.optLong("at"))
        }.toMutableList()
    } catch (e: Exception) {
        mutableListOf()
    }

    private fun saveHistory(list: List<HistEntry>) {
        val arr = JSONArray()
        for (e in list.take(300)) arr.put(org.json.JSONObject().put("u", e.url).put("t", e.title).put("at", e.at))
        prefs().edit().putString("history", arr.toString()).apply()
    }

    private fun addHistory(url: String, title: String) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        val list = history()
        list.removeAll { it.url == url }
        list.add(0, HistEntry(url, title.ifBlank { url }, System.currentTimeMillis()))
        saveHistory(list)
    }

    private fun showHistory() {
        val list = history()
        if (list.isEmpty()) {
            AlertDialog.Builder(this).setTitle(R.string.web_history_title).setMessage(R.string.web_history_empty)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        val labels = list.map { e ->
            e.title.take(70) + "\n" + e.url.removePrefix("https://").removePrefix("http://").take(60) + " · " + df.format(java.util.Date(e.at))
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this).setTitle(R.string.web_history_title)
            .setItems(labels) { _, i -> load(list[i].url) }
            .setNeutralButton(R.string.web_history_clear) { _, _ ->
                AlertDialog.Builder(this).setMessage(R.string.web_history_clear_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ -> saveHistory(emptyList()) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            .setNegativeButton(R.string.channel_check_close, null).create()
        dialog.show()
        // Pulsación larga: quita esa página del historial.
        dialog.listView.setOnItemLongClickListener { _, _, pos, _ ->
            val cur = history()
            cur.removeAll { it.url == list[pos].url }
            saveHistory(cur)
            Toast.makeText(this, R.string.web_history_removed, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            showHistory()
            true
        }
    }

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
        webView.loadUrl(url)
    }

    private fun updateNavButtons() {
        binding.webBack.alpha = if (webView.canGoBack()) 1f else 0.35f
        binding.webForward.alpha = if (webView.canGoForward()) 1f else 0.35f
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

    /** Enlace directo a un vídeo/audio por su ruta: al tocarlo se ofrece reproducirlo en vez de descargarlo. */
    private fun isMediaUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return MEDIA_EXT.any { path.endsWith(it) }
    }

    /** Detección más amplia: extensión en cualquier parte de la dirección (también en parámetros), sin trozos .ts/.m4s de HLS. */
    private fun looksLikeMedia(url: String): Boolean {
        if (url.length > 2000) return false
        val u = url.lowercase()
        if (!u.startsWith("http")) return false
        return MEDIA_RE.containsMatchIn(u) || u.contains("m3u8") || u.contains("mime=video") || u.contains("mime_type=video")
    }

    private fun streamTypeFor(url: String): String {
        val l = url.lowercase()
        val path = l.substringBefore('?')
        return when {
            l.contains("m3u8") -> "HLS"
            path.endsWith(".mpd") || l.contains(".mpd?") || l.contains(".mpd/") -> "DASH"
            path.endsWith(".mp3") || path.endsWith(".aac") || path.endsWith(".m4a") || path.endsWith(".ogg") -> "MP3"
            else -> "MP4"
        }
    }

    private fun qualityOf(url: String): String =
        Regex("(2160|1440|1080|720|576|480|360|240)p?", RegexOption.IGNORE_CASE).find(url.substringAfterLast('/'))?.let { " · ${it.groupValues[1]}p" }.orEmpty()

    /** Los vídeos que encuentra el escáner de JavaScript (etiquetas video/source, fetch y XHR de la página). */
    private inner class Scout(private val tab: Tab) {
        /** Respuesta de una API de la página (para el diagnóstico de peticiones). */
        @JavascriptInterface
        fun log(u: String?, body: String?) {
            if (u == null || body == null) return
            synchronized(tab.requests) {
                if (tab.requests.size >= 150) tab.requests.removeAt(0)
                synchronized(tab.resp) { if (tab.resp.size >= 20) tab.resp.removeAt(0); tab.resp.add("↳ RESPUESTA ${u.take(200)}\n${body.take(1200)}") }
            }
        }

        @JavascriptInterface
        fun found(u: String?) {
            if (u == null || u.length > 2000 || !(u.startsWith("http://") || u.startsWith("https://"))) return
            addDetected(tab, u)
        }
    }

    private fun addDetected(tab: Tab, url: String) {
        val added = synchronized(tab.detected) {
            if (tab.detected.size < 40 && !tab.detected.containsKey(url)) { tab.detected[url] = tab.page.ifEmpty { url }; true } else false
        }
        if (added) runOnUiThread {
            if (isFinishing || isDestroyed || tab !== curTab) return@runOnUiThread
            updateVideosButton()
            onVideoDetected(tab, url)
        }
    }

    private fun alertMode() = prefs().getInt("video_alert", 0)

    private fun alertModeLabel() = getString(when (alertMode()) { 1 -> R.string.web_alert_short_auto; 2 -> R.string.web_alert_short_off; else -> R.string.web_alert_short_ask })

    private fun chooseAlertMode() {
        val names = arrayOf(getString(R.string.web_alert_ask), getString(R.string.web_alert_auto), getString(R.string.web_alert_off))
        AlertDialog.Builder(this).setTitle(R.string.web_alert_title)
            .setSingleChoiceItems(names, alertMode()) { d, i -> prefs().edit().putInt("video_alert", i).apply(); d.dismiss() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Primer vídeo de la página: aviso con botón para reproducirlo (o reproducción directa, según el ajuste). */
    private fun onVideoDetected(tab: Tab, url: String) {
        val mode = alertMode()
        if (mode == 2 || tab.bannerShown || customView != null) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        tab.bannerShown = true
        if (mode == 1) { playInApp(url, tab.detected[url] ?: url); return }
        binding.webVideoBanner.visibility = View.VISIBLE
        binding.webVideoBanner.removeCallbacks(hideBannerRunnable)
        binding.webVideoBanner.postDelayed(hideBannerRunnable, 15000)
        // En la tele el botón ▶ recibe el foco: con OK se reproduce; ✕ (derecha) o Atrás lo cierra.
        if (isTv) binding.webVideoPlay.post { binding.webVideoPlay.requestFocus() }
    }

    private val hideBannerRunnable = Runnable { hideBanner() }

    private fun hideBanner() {
        binding.webVideoBanner.removeCallbacks(hideBannerRunnable)
        val hadFocus = binding.webVideoBanner.hasFocus()
        binding.webVideoBanner.visibility = View.GONE
        if (isTv && hadFocus && tabs.isNotEmpty()) webView.requestFocus()
    }

    private fun playInApp(url: String, page: String) {
        val name = (webView.title?.takeIf { it.isNotBlank() } ?: Uri.parse(url).lastPathSegment ?: url).take(80)
        val headers = HashMap<String, String>()
        if (page.startsWith("http")) {
            headers["Referer"] = page
            try {
                val u = Uri.parse(page)
                headers["Origin"] = "${u.scheme}://${u.host}"
            } catch (e: Exception) { }
        }
        // Lo que de verdad envió la página (el reproductor suele ir en un iframe con otro origen) manda sobre lo supuesto.
        tabs.firstNotNullOfOrNull { it.hdrs[url] }?.forEach { (k, v) -> headers[if (k.equals("Origin", true)) "Origin" else "Referer"] = v }
        try {
            val ck = android.webkit.CookieManager.getInstance().getCookie(url)
            if (!ck.isNullOrEmpty()) headers["Cookie"] = ck
        } catch (e: Exception) { }
        headers["User-Agent"] = webView.settings.userAgentString
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
            "${streamTypeFor(u)}${qualityOf(u)} · ${Uri.parse(u).host.orEmpty()} · ${file.take(40)}"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.web_videos_title)
            .setItems(labels.toTypedArray()) { _, which -> playInApp(urls[which], detected[urls[which]] ?: urls[which]) }
            .setNegativeButton(R.string.channel_check_close, null)
            .create()
        dialog.show()
        // Pulsación larga: copia el enlace del vídeo.
        dialog.listView.setOnItemLongClickListener { _, _, pos, _ ->
            copyText(urls[pos], R.string.web_video_copied)
            true
        }
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
        val url = webView.url ?: return
        val list = bookmarks()
        if (list.any { it.second == url }) {
            Toast.makeText(this, R.string.web_bookmark_exists, Toast.LENGTH_SHORT).show()
            return
        }
        // Se pregunta el nombre para reconocerla más rápido (viene rellenado con el título de la página).
        editBookmark(null, (webView.title?.takeIf { it.isNotBlank() } ?: Uri.parse(url).host?.removePrefix("www.") ?: url) to url)
    }

    // ---- Favoritos: atajos, tira y página de inicio ----

    private fun homeVisible() = binding.webHome.visibility == View.VISIBLE

    private fun refreshFavUi() {
        updateSaveButton()
        if (binding.webFavScroll.visibility == View.VISIBLE) renderFavStrip()
        if (homeVisible()) renderHome()
    }

    private fun updateSaveButton() {
        val url = if (homeVisible()) null else webView.url
        val saved = url != null && bookmarks().any { it.second == url }
        binding.webSaveBtn.setText(if (saved) R.string.web_saved_btn else R.string.web_save_btn)
    }

    private fun toggleSaveCurrent() {
        val url = if (homeVisible()) null else webView.url
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
    private fun editBookmark(old: Pair<String, String>?, prefill: Pair<String, String>? = null) {
        val d = resources.displayMetrics.density
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        val nameEt = android.widget.EditText(this).apply { hint = getString(R.string.web_fav_name); setText(old?.first ?: prefill?.first ?: ""); setSingleLine(); selectAll() }
        val urlEt = android.widget.EditText(this).apply {
            hint = getString(R.string.web_fav_url)
            setText(old?.second ?: prefill?.second ?: "")
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        col.addView(nameEt)
        col.addView(urlEt)
        val favDialog = AlertDialog.Builder(this)
            .setTitle(if (old == null) R.string.web_fav_add else R.string.web_fav_edit)
            .setView(col)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                var u = urlEt.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
                val n = nameEt.text.toString().trim().ifEmpty { Uri.parse(u).host?.removePrefix("www.") ?: u }
                val list = bookmarks()
                val i = if (old == null) -1 else list.indexOfFirst { it.second == old.second }
                if (i >= 0) list[i] = n to u else list.add(0, n to u)
                saveBookmarks(list)
                refreshFavUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        favDialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        favDialog.show()
        nameEt.requestFocus()
    }

    private fun shareCurrent() {
        val url = (if (homeVisible()) null else webView.url) ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, webView.title ?: url)
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
        webView.visibility = View.INVISIBLE // así el mando no puede enfocar el navegador oculto
        webView.onPause()
        binding.webUrl.setText("")
        binding.webUrl.clearFocus()
        renderHome()
        updateSaveButton()
        if (isTv) binding.webHome.post { binding.webHomeGrid.getChildAt(0)?.requestFocus() }
    }

    private fun hideHome() {
        if (!homeVisible()) return
        binding.webHome.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.onResume()
        updateSaveButton()
        if (isTv) webView.post { webView.requestFocus() }
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
        val current = webView.url?.takeIf { !homeVisible() }
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
        menu.menu.add(0, 11, 1, R.string.web_history)
        menu.menu.add(0, 12, 1, R.string.web_new_tab)
        menu.menu.add(0, 13, 2, getString(R.string.web_alert_menu, alertModeLabel()))
        menu.menu.add(0, 15, 9, R.string.web_requests_menu)
        menu.menu.add(0, 14, 2, getString(if (adBlockOn()) R.string.web_adblock_on else R.string.web_adblock_off, curTab.blocked))
        menu.menu.add(0, 3, 2, getString(R.string.web_videos_menu, detected.size))
        menu.menu.add(0, 4, 3, R.string.web_desktop).apply { isCheckable = true; isChecked = desktop }
        menu.menu.add(0, 10, 4, getString(R.string.web_agent_menu, agentLabel()))
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
                11 -> showHistory()
                12 -> newTab(null)
                13 -> chooseAlertMode()
                15 -> showRequests()
                14 -> {
                    prefs().edit().putBoolean("adblock", !adBlockOn()).apply()
                    Toast.makeText(this, if (adBlockOn()) R.string.web_adblock_enabled else R.string.web_adblock_disabled, Toast.LENGTH_SHORT).show()
                    webView.reload()
                }
                4 -> {
                    setAgent(if (desktop) "" else "desktop", "")
                    webView.reload()
                    hideHome()
                }
                10 -> chooseAgent()
                5 -> goHome()
                8 -> chooseHomePage()
                9 -> {
                    prefs().edit().putBoolean("cursor", !cursorEnabled()).apply()
                    if (cursorEnabled()) showCursor() else cursorView?.visibility = View.GONE
                }
                6 -> (if (homeVisible()) null else webView.url)?.let { openUri(it) }
                7 -> AlertDialog.Builder(this)
                    .setMessage(R.string.web_clear_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        for (t in tabs) { t.view.clearCache(true); t.view.clearHistory() }
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

    // ---- Cambio de agente (User-Agent): cómo se presenta el navegador a las webs ----

    /** Agentes predefinidos: clave, nombre y cadena (vacía = el del propio móvil/tele). */
    private val agents = listOf(
        Triple("", "📱 Predeterminado (este dispositivo)", ""),
        Triple("desktop", "🖥 Escritorio · Chrome (Linux)", DESKTOP_UA),
        Triple("win", "🖥 Escritorio · Chrome (Windows)",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"),
        Triple("win120", "🖥 Escritorio · Chrome 120 (Windows)",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
        Triple("mac", "💻 Escritorio · Safari (Mac)",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"),
        Triple("ff", "🦊 Escritorio · Firefox",
            "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0"),
        Triple("iphone", "📱 iPhone · Safari",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"),
        Triple("ipad", "📱 iPad · Safari",
            "Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"),
        Triple("tv", "📺 Smart TV (Android TV)",
            "Mozilla/5.0 (Linux; Android 12; BRAVIA 4K UR3 Build/SR1A.211012.001) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 TV")
    )

    private fun agentKey() = prefs().getString("ua", "") ?: ""

    private fun agentLabel(): String {
        val k = agentKey()
        if (k == "custom") return getString(R.string.web_agent_custom_name)
        return (agents.firstOrNull { it.first == k } ?: agents[0]).second.substringAfter(' ')
    }

    private fun setAgent(key: String, custom: String) {
        prefs().edit().putString("ua", key).putString("ua_custom", custom).apply()
        applySavedAgent()
    }

    private fun applySavedAgent() {
        val k = agentKey()
        val ua = when {
            k == "custom" -> (prefs().getString("ua_custom", "") ?: "").ifBlank { defaultUa }
            k.isEmpty() -> defaultUa
            else -> agents.firstOrNull { it.first == k }?.third?.ifEmpty { defaultUa } ?: defaultUa
        }
        desktop = k.isNotEmpty() && k != "iphone" && k != "ipad" && k != "tv"
        for (t in tabs) t.view.settings.userAgentString = ua
    }

    private fun chooseAgent() {
        val names = ArrayList(agents.map { it.second })
        names.add("✏ " + getString(R.string.web_agent_custom))
        val cur = agentKey().let { k -> if (k == "custom") agents.size else agents.indexOfFirst { it.first == k }.coerceAtLeast(0) }
        AlertDialog.Builder(this).setTitle(R.string.web_agent_title)
            .setSingleChoiceItems(names.toTypedArray(), cur) { d, i ->
                d.dismiss()
                if (i < agents.size) {
                    setAgent(agents[i].first, "")
                    webView.reload()
                    hideHome()
                } else {
                    val et = android.widget.EditText(this).apply {
                        setText(prefs().getString("ua_custom", "") ?: "")
                        hint = "Mozilla/5.0 …"
                        setSingleLine(false)
                    }
                    AlertDialog.Builder(this).setTitle(R.string.web_agent_custom).setView(et)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val v = et.text.toString().trim()
                            setAgent(if (v.isEmpty()) "" else "custom", v)
                            webView.reload()
                            hideHome()
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun setupTv() {
        val d = resources.displayMetrics.density
        val size = (44 * d).toInt()
        val cv = CursorView(this)
        cv.visibility = View.GONE
        cv.isFocusable = false
        binding.webBody.addView(cv, android.widget.FrameLayout.LayoutParams(size, size))
        cursorView = cv
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
        webView.scrollBy(0, dy)
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
                if (cursorX >= w - 8f * d) webView.scrollBy(step.toInt(), 0) else cursorX += step
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (cursorY >= h - zone && binding.webVideoBanner.visibility == View.VISIBLE) {
                    binding.webVideoPlay.requestFocus()
                    return
                }
                if (cursorY >= h - zone) scrollPage((step * 1.6f).toInt()) else cursorY += step
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (cursorY <= zone) {
                    if (webView.scrollY > 0) scrollPage(-(step * 1.6f).toInt())
                    else if (cursorY <= 10f * d) {
                        leaveToBars()
                        return
                    } else cursorY -= step
                } else cursorY -= step
            }
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && cursorX <= 0f && webView.scrollX > 0) webView.scrollBy(-step.toInt(), 0)
        showCursor()
    }

    /** Cursor en el borde de arriba y sin más página que subir: el foco pasa a los atajos, en la columna más cercana. */
    private fun leaveToBars() {
        val btns = listOf<View>(binding.webTabsBtn, binding.webFavsBtn, binding.webSaveBtn, binding.webHomeBtn, binding.webShareBtn)
        val w = binding.webBody.width.coerceAtLeast(1)
        val i = (cursorX / w * btns.size).toInt().coerceIn(0, btns.size - 1)
        btns[i].requestFocus()
    }

    private fun clickAtCursor(long: Boolean) {
        val wv = webView
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
                if (down) scrollPage(-(webView.height * 0.8f).toInt())
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (down) scrollPage((webView.height * 0.8f).toInt())
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down && webView.canGoBack()) webView.goBack()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down && webView.canGoForward()) webView.goForward()
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
            webView.hasFocus() && handleCursorKey(event)
        ) return true
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private val MEDIA_EXT = listOf(".m3u8", ".mpd", ".mp4", ".mkv", ".m4v", ".mp3", ".aac", ".ts", ".webm", ".mov", ".m4a")
        private val NOISE_RE = Regex("googletagmanager|/api/match/|/api/common/|/api-cf/|logos\\d*\\.|/cgi-trace|/stats$")
        private val MEDIA_RE = Regex("\\.(m3u8|mpd|mp4|webm|mkv|m4v|mov|flv|avi|wmv|3gp|mp3|m4a|aac|ogg)(\\?|#|/|&|$)")
        /** Escáner dentro de la página: etiquetas video/audio/source, fetch y XMLHttpRequest, las direcciones de vídeo que vengan dentro de sus respuestas y, para el diagnóstico, el cuerpo de las respuestas de las APIs de "stream". */
        private const val SCOUT_JS = "(function(){if(window.__sc)return;window.__sc=1;var seen={};var sl={};" +
            "function rep(u){try{if(!u||typeof u!=='string'||u.indexOf('blob:')===0||u.indexOf('data:')===0)return;u=new URL(u,location.href).href;if(seen[u])return;seen[u]=1;SocramScout.found(u);}catch(e){}}" +
            "var ok=/\\.(m3u8|mpd|mp4|webm|mkv|m4v|mov|flv|mp3|m4a|aac|ogg)(\\?|#|\\/|&|\$)|m3u8/i;" +
            "function chk(u){try{if(u&&ok.test(String(u)))rep(String(u));}catch(e){}}" +
            "function hunt(t){try{if(!t||t.length>400000)return;t=t.replace(/\\\\\\//g,'/').replace(/\\\\u002f/gi,'/').replace(/\\\\u0026/gi,'&');var m=t.match(/https?:\\/\\/[^\\s'\"<>\\\\]+/g);if(m)for(var i=0;i<m.length;i++)chk(m[i]);}catch(e){}}" +
            "function slog(u,t){try{u=String(u);if(!t||t.length>30000)return;if(!(/stream/i.test(u)||/m3u8|\\.mpd|\\.flv|\\.mp4/i.test(t)))return;sl[u]=(sl[u]||0)+1;if(sl[u]>2)return;SocramScout.log(u,t.substring(0,1200));}catch(e){}}" +
            "var of=window.fetch;if(of)window.fetch=function(){var a=arguments[0];var u=a&&a.url?a.url:a;try{chk(u);}catch(e){}var p=of.apply(this,arguments);try{p.then(function(r){try{var ct=r.headers.get('content-type')||'';if(/json|text|mpegurl|xml/i.test(ct))r.clone().text().then(function(t){hunt(t);slog(u,t);}).catch(function(){});}catch(e){}}).catch(function(){});}catch(e){}return p;};" +
            "var oo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){try{chk(u);this.addEventListener('load',function(){try{var rt=this.responseType;if(rt===''||rt==='text'){var ct=this.getResponseHeader('content-type')||'';if(/json|text|mpegurl|xml/i.test(ct)){hunt(this.responseText);slog(u,this.responseText);}}}catch(e){}});}catch(e){}return oo.apply(this,arguments);};" +
            "function scan(){var n=document.querySelectorAll('video,audio');for(var i=0;i<n.length;i++){rep(n[i].currentSrc||n[i].src);}var q=document.querySelectorAll('source');for(var j=0;j<q.length;j++){chk(q[j].src);}}" +
            "scan();setInterval(scan,2500);})();"
        /** Oculta los huecos típicos de publicidad (el resto lo corta el bloqueo de peticiones). */
        private const val HIDE_ADS_JS = "(function(){if(window.__ab)return;window.__ab=1;var s=document.createElement('style');" +
            "s.textContent='ins.adsbygoogle,.adsbygoogle,iframe[src*=doubleclick],iframe[src*=googlesyndication],iframe[src*=adservice]," +
            "div[id^=google_ads],div[id^=taboola],div[id^=outbrain],[class*=popunder]{display:none!important}';" +
            "(document.head||document.documentElement).appendChild(s);})();"
        private val STATIC_RE = Regex("\\.(png|jpe?g|gif|webp|svg|ico|css|woff2?|ttf|otf|js|json|html?|map|pdf|zip|apk)$")
        private val AD_HOSTS = hashSetOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "pagead2.googlesyndication.com",
            "google-analytics.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net", "popcash.net", "propellerads.com",
            "onclickads.net", "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net", "adsterra.com", "hilltopads.net",
            "clickadu.com", "mgid.com", "revcontent.com", "zedo.com", "adform.net", "criteo.com", "criteo.net", "amazon-adsystem.com",
            "scorecardresearch.com", "quantserve.com", "moatads.com", "yieldmo.com", "smartadserver.com", "rubiconproject.com",
            "pubmatic.com", "openx.net", "adcolony.com", "popunder.net", "a-ads.com", "ad-maven.com", "admaven.com", "bidvertiser.com",
            "tsyndicate.com", "realsrv.com", "notix.io", "pushprofit.net", "adskeeper.com", "adspyglass.com", "ero-advertising.com",
            "trafficfactory.biz", "trafficstars.com", "tsyndicate.com", "richpush.co", "evadav.com", "monetag.com", "pushame.com",
            "adtng.com", "adxpansion.com", "cpmstar.com", "adcash.com", "adsco.re", "profitablegatecpm.com", "highperformanceformat.com",
            "mc.yandex.ru", "ads.yahoo.com", "advertising.com", "casalemedia.com", "contextweb.com", "sharethrough.com", "teads.tv",
            "smaato.net", "inmobi.com", "mopub.com", "applovin.com", "unityads.unity3d.com", "ads.twitter.com", "static.ads-twitter.com",
            "facebook.net", "connect.facebook.net", "hotjar.com", "mouseflow.com", "bat.bing.com", "ads.linkedin.com"
        )
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
