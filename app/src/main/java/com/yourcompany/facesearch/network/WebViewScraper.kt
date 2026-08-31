package com.yourcompany.facesearch.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import org.json.JSONArray
import kotlin.coroutines.resume

class WebViewScraper private constructor(
    private val webView: WebView,
    private val handler: Handler
) {
    companion object {
        suspend fun create(context: Context): WebViewScraper = withContext(Dispatchers.Main) {
            val appContext = context.applicationContext
            val webView = WebView(appContext).apply {
                @SuppressLint("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
            }
            val handler = Handler(Looper.getMainLooper())
            WebViewScraper(webView, handler)
        }

        private const val CONSENT_AND_EXTRACT_JS = """
            (function(){
                // 1. Auto-bypass cookie and consent dialogs
                var consentBtns = ['#L2AGLb', '#bnp_btn_accept', '#accept-all', 'button[aria-label*="Accept"]'];
                for (var i = 0; i < consentBtns.length; i++) {
                    var b = document.querySelector(consentBtns[i]);
                    if (b) { b.click(); }
                }

                function extract(){
                    var items = [], seen = new Set();
                    
                    // ==========================================
                    // TINEYE-SPECIFIC HIGH ACCURACY EXTRACTION
                    // ==========================================
                    if (window.location.hostname.indexOf('tineye.com') >= 0) {
                        var rows = document.querySelectorAll('.match-row, .match');
                        if (rows.length > 0) {
                            rows.forEach(function(row) {
                                var linkEl = row.querySelector('h4 a, p a, .match-details a');
                                // Specifically target the matched image, ignore site favicons
                                var imgEl = row.querySelector('.match-thumb img');
                                
                                if (linkEl && imgEl && linkEl.href && imgEl.src) {
                                    var href = linkEl.href.split('#')[0];
                                    if (seen.has(href)) return;
                                    seen.add(href);
                                    items.push({
                                        title: (linkEl.innerText || 'TinEye Match').replace(/\s+/g,' ').trim().slice(0, 100),
                                        link: href,
                                        thumbnail: imgEl.src,
                                        source: 'TinEye',
                                        score: 800
                                    });
                                }
                            });
                            return items;
                        }
                    }

                    // ==========================================
                    // GENERIC EXTRACTION (Google, Bing, Yandex)
                    // ==========================================
                    var badThumb = ['logo', 'icon', 'favicon', 'avatar', 'default', 'data:image', 'shutterstock', 'istock'];
                    
                    document.querySelectorAll('a[href^="http"]').forEach(function(a){
                        try {
                            var href = a.href;
                            
                            // Unpack Google redirects
                            if (href.indexOf('google.com/url?') >= 0) {
                                var match = href.match(/url\?q=([^&]+)/);
                                if (match) href = decodeURIComponent(match[1]);
                            }
                            
                            href = href.split('#')[0];
                            if(seen.has(href)) return;

                            var img = a.querySelector('img');
                            if (!img) {
                                var div = a.closest('div');
                                if (div) img = div.querySelector('img');
                            }
                            
                            var imgSrc = img ? (img.src || img.getAttribute('data-src')) : null;
                            if(!imgSrc || imgSrc.length < 15) return;
                            
                            // Strip out blank avatars and stock banners
                            var lowSrc = imgSrc.toLowerCase();
                            var isBad = false;
                            for(var j = 0; j < badThumb.length; j++) {
                                if(lowSrc.indexOf(badThumb[j]) >= 0) { isBad = true; break; }
                            }
                            if(isBad) return;

                            var title = (a.innerText || a.title || 'Visual Candidate').replace(/\s+/g,' ').trim().slice(0, 100);
                            
                            seen.add(href);
                            items.push({
                                title: title,
                                link: href,
                                thumbnail: imgSrc,
                                source: 'Web',
                                score: 100
                            });
                        } catch(e){}
                    });
                    return items;
                }
                Native.onResults(JSON.stringify(extract()));
            })();
        """

        private const val DORK_EXTRACT_JS = """
            (function(){
                function pickUrl(v){
                    if(!v || v.length < 8) return '';
                    if(v.indexOf('data:image') === 0 && v.length > 200) return v;
                    if(v.indexOf('http') === 0) return v;
                    if(v.indexOf('//') === 0) return 'https:' + v;
                    return '';
                }
                function imgFrom(el){
                    if(!el) return '';
                    var attrs = ['src', 'data-src', 'data-original', 'data-thumb'];
                    for(var i = 0; i < attrs.length; i++){
                        var picked = pickUrl(el.getAttribute(attrs[i]));
                        if(picked) return picked;
                    }
                    return pickUrl(el.src);
                }
                function extract(){
                    var items = [], seen = new Set();
                    var rows = document.querySelectorAll('li.b_algo, .b_algo, .result, .result__body, .g');
                    if(!rows.length) rows = document.querySelectorAll('a[href^="http"]');

                    rows.forEach(function(row){
                        try {
                            var a = row.tagName === 'A' ? row : row.querySelector('h2 a, .result__a, .b_title a, a[href^="http"]');
                            if(!a) return;
                            var href = a.href || a.getAttribute('href');
                            if(!href || href.indexOf('http') !== 0) return;
                            href = href.split('#')[0];
                            if(seen.has(href)) return;

                            var title = (a.innerText || a.textContent || '').replace(/\s+/g,' ').trim();
                            if(title.length < 3) return;

                            var img = row.querySelector('img');
                            var thumb = imgFrom(img);

                            seen.add(href);
                            items.push({
                                title: title.slice(0, 150),
                                link: href,
                                thumbnail: thumb,
                                source: 'Dork',
                                score: thumb ? 350 : 250
                            });
                        } catch(e){}
                    });
                    return items;
                }
                Native.onResults(JSON.stringify(extract()));
            })();
        """
    }

    suspend fun scrapeGoogle(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://lens.google.com/uploadbyurl?url=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}",
        engineName = "Google",
        delayMs = 7000
    )

    suspend fun scrapeBing(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://www.bing.com/images/searchbyimage?cbir=sbi&imgurl=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}",
        engineName = "Bing",
        delayMs = 7000
    )

    suspend fun scrapeTinEye(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://tineye.com/search?url=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}",
        engineName = "TinEye",
        delayMs = 6000
    )

    suspend fun scrapeYandex(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://yandex.com/images/search?rpt=imageview&url=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}",
        engineName = "Yandex",
        delayMs = 7000
    )

    suspend fun scrapeSogou(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://pic.sogou.com/ris?query=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}&flag=1",
        engineName = "Sogou",
        delayMs = 6000
    )

    suspend fun scrapeSocialDork(
        site: String, 
        keyword: String,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = scrapeEngine(
        url = "https://www.bing.com/search?q=site:${site}+%22${java.net.URLEncoder.encode(keyword, "UTF-8")}%22",
        engineName = AdultSiteConfig.labelFor(site),
        delayMs = 3000,
        extractJs = DORK_EXTRACT_JS,
        onLog = onLog
    )

    private suspend fun scrapeEngine(
        url: String,
        engineName: String,
        delayMs: Long,
        extractJs: String = CONSENT_AND_EXTRACT_JS,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val accumulated = linkedMapOf<String, SerpVisualMatch>()
            var passesDone = 0
            val totalPasses = 4
            val maxTimeout = 18000L

            val timeoutRunnable = Runnable {
                if (continuation.isActive) continuation.resume(accumulated.values.toList())
            }
            handler.postDelayed(timeoutRunnable, maxTimeout)
            onLog("Loading $engineName visual engine...")

            val bridge = object {
                @JavascriptInterface
                fun onResults(json: String) {
                    try {
                        val arr = JSONArray(json)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val link = obj.optString("link")
                            if (link.isBlank() || accumulated.containsKey(link)) continue

                            accumulated[link] = SerpVisualMatch(
                                title = obj.optString("title", "Visual Match"),
                                link = link,
                                source = engineName,
                                thumbnail = ThumbnailUtils.normalize(obj.optString("thumbnail")),
                                score = obj.optInt("score", 100)
                            )
                        }
                    } catch (_: Exception) {}

                    passesDone++
                    if (passesDone >= totalPasses && continuation.isActive) {
                        handler.removeCallbacks(timeoutRunnable)
                        continuation.resume(accumulated.values.toList())
                    }
                }
            }

            webView.addJavascriptInterface(bridge, "Native")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    handler.postDelayed({
                        view?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight/3);", null)
                        view?.evaluateJavascript(extractJs, null)
                    }, delayMs)

                    handler.postDelayed({
                        view?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
                        view?.evaluateJavascript(extractJs, null)
                    }, delayMs + 2500)

                    handler.postDelayed({
                        view?.evaluateJavascript(extractJs, null)
                    }, delayMs + 5000)

                    handler.postDelayed({
                        view?.evaluateJavascript(extractJs, null)
                    }, delayMs + 7500)
                }
            }
            webView.loadUrl(url)
        }
    }

    fun destroy() {
        handler.post { webView.destroy() }
    }
}
