package com.example.superplayer.player

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Cuando la página no enseña la dirección del vídeo en su código (la pide con JavaScript), se carga en un
 * navegador INVISIBLE dentro de la propia pantalla del reproductor, se «pulsa» el play y se espera a que la
 * página pida un .m3u8/.mpd: esa petición (con su Referer y User-Agent reales) es el vídeo. Después el
 * navegador se destruye. Todo en segundo plano, sin abrir el navegador de la app.
 */
object HiddenWebResolver {
    private val MEDIA = Regex("""\.(m3u8|mpd)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val CLICK_JS = """
        (function(){try{
          document.querySelectorAll('video').forEach(function(v){try{v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){})}catch(e){}});
          var sel=['.vjs-big-play-button','.jw-icon-display','.jw-display-icon-container','.plyr__control--overlaid','.play-button','.btn-play','#play','[class*=play-btn]','[aria-label*=lay]','button[title*=lay]','video'];
          for(var i=0;i<sel.length;i++){var e=document.querySelector(sel[i]);if(e){try{e.click()}catch(x){}break}}
        }catch(e){}})();
    """.trimIndent()

    /** [onResult] se llama UNA vez, en el hilo principal: con el vídeo encontrado o con null si no apareció a tiempo. */
    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(
        activity: Activity,
        pageUrl: String,
        headers: Map<String, String>,
        timeoutMs: Long = 45_000L,
        onResult: (PageStreamResolver.Result?) -> Unit
    ) {
        val main = Handler(Looper.getMainLooper())
        var done = false
        var wv: WebView? = null
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value?.takeIf { it.isNotBlank() } ?: PageStreamResolver.UA
        val userReferer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value?.takeIf { it.isNotBlank() }

        fun finish(res: PageStreamResolver.Result?) {
            if (done) return
            done = true
            main.removeCallbacksAndMessages(null)
            val v = wv
            wv = null
            if (v != null) {
                try {
                    v.stopLoading()
                    v.loadUrl("about:blank")
                    (v.parent as? ViewGroup)?.removeView(v)
                    v.destroy()
                } catch (e: Exception) { }
            }
            onResult(res)
        }

        try {
            val view = WebView(activity)
            wv = view
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = ua
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
            }
            view.translationX = -20_000f // fuera de la pantalla, pero con tamaño real para que el reproductor de la página arranque
            val host = try { android.net.Uri.parse(pageUrl).host } catch (e: Exception) { null }
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url.toString()
                    if (!done && MEDIA.containsMatchIn(u.lowercase())) {
                        val ref = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                        val refOrigin = (userReferer ?: ref ?: pageUrl).let {
                            try { val x = android.net.Uri.parse(it); x.scheme + "://" + x.host + "/" } catch (e: Exception) { it }
                        }
                        val result = PageStreamResolver.Result(u, refOrigin, ua)
                        main.post { finish(result) }
                    }
                    return null
                }

                // Se impide que la página se lleve el navegador a otro sitio (anuncios/redirecciones).
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val h = request.url.host
                    return request.isForMainFrame && h != null && host != null && h != host && request.url.toString() != pageUrl
                }

                override fun onPageFinished(v: WebView, url: String?) {
                    v.evaluateJavascript(CLICK_JS, null)
                }
            }
            val root = activity.window.decorView as ViewGroup
            root.addView(view, FrameLayout.LayoutParams(360 * 3, 640 * 3))

            val extra = HashMap<String, String>()
            userReferer?.let { extra["Referer"] = it }
            view.loadUrl(pageUrl, extra)

            // Pulsaciones de «play» (JS y toque real en el centro) a intervalos, hasta que salga el vídeo.
            for (delay in longArrayOf(1_500L, 3_500L, 6_000L, 9_000L, 13_000L)) {
                main.postDelayed({
                    val v = wv ?: return@postDelayed
                    if (done) return@postDelayed
                    v.evaluateJavascript(CLICK_JS, null)
                    val t = SystemClock.uptimeMillis()
                    val x = v.width / 2f
                    val y = v.height / 2f
                    v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
                    v.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
                }, delay)
            }
            main.postDelayed({ finish(null) }, timeoutMs)
        } catch (e: Exception) {
            finish(null)
        }
    }
}
