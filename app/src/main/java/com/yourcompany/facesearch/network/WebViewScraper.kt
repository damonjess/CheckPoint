package com.yourcompany.facesearch.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * FREE fallback scraper. Uses a headless WebView to extract Yandex results
 * when the Termux backend is not running. Zero API keys. Zero cost.
 */
class WebViewScraper(context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    private val webView = WebView(context.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val handler = Handler(Looper.getMainLooper())

    suspend fun scrapeYandex(imageUrl: String): List<SerpVisualMatch> = suspendCancellableCoroutine { continuation ->
        val timeout = Runnable { continuation.resume(emptyList()) }
        handler.postDelayed(timeout, 25000)

        val bridge = object {
            @JavascriptInterface
            fun onResults(json: String) {
                handler.removeCallbacks(timeout)
                try {
                    val arr = JSONArray(json)
                    val list = mutableListOf<SerpVisualMatch>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(SerpVisualMatch(
                            title = obj.optString("title", "Visual Match"),
                            link = obj.optString("link"),
                            source = obj.optString("source", "Yandex"),
                            thumbnail = obj.optString("thumbnail").ifBlank { null },
                            score = obj.optInt("score", 50)
                        ))
                    }
                    continuation.resume(list)
                } catch (_: Exception) { continuation.resume(emptyList()) }
            }
        }

        handler.post {
            webView.addJavascriptInterface(bridge, "Native")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    handler.postDelayed({
                        view?.evaluateJavascript(EXTRACT_JS, null)
                    }, 4000)
                }
            }
            webView.loadUrl("https://yandex.com/images/search?rpt=imageview&url=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}")
        }
    }

    fun destroy() = handler.post { webView.destroy() }

    companion object {
        private const val EXTRACT_JS = """
            (function(){
                var items=[],seen=new Set();
                document.querySelectorAll('.serp-item,.CbirSites-Item').forEach(function(el){
                    try{
                        var linkEl=el.tagName==='A'?el:el.querySelector('a[href^="http"]');
                        var href=linkEl?linkEl.href:'';
                        if(!href||seen.has(href)||href.includes('yandex.com'))return;
                        var imgEl=el.querySelector('img');
                        var imgSrc=imgEl?(imgEl.src||imgEl.getAttribute('data-src')||''):'';
                        var title=el.textContent.trim().slice(0,100)||'Visual Match';
                        var source='Yandex';
                        var domains={'instagram.com':'Instagram','facebook.com':'Facebook','linkedin.com':'LinkedIn','twitter.com':'Twitter','x.com':'Twitter','tiktok.com':'TikTok','vk.com':'VKontakte','github.com':'GitHub'};
                        for(var d in domains){if(href.includes(d)){source=domains[d];break;}}
                        seen.add(href);
                        items.push({title:title,link:href,thumbnail:imgSrc,source:source,score:source!=='Yandex'?120:40});
                    }catch(e){}
                });
                Native.onResults(JSON.stringify(items));
            })();
        """
    }
}
