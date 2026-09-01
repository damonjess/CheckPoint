package com.yourcompany.facesearch.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
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
            
            // Disable SafeSearch at the cookie level across all WebView search engines
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setCookie("https://www.bing.com", "SRCHHPGUSR=ADLT=OFF; domain=.bing.com; path=/")
            cookieManager.setCookie("https://www.bing.com", "_EDGE_V=1; domain=.bing.com; path=/")
            cookieManager.setCookie("https://www.bing.com", "MUID=1; domain=.bing.com; path=/")
            cookieManager.flush()

            val webView = WebView(appContext).apply {
                @SuppressLint("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                
                val newUa = settings.userAgentString.replace("Mobile", "eliboM").replace("Android", "diordnA")
                settings.userAgentString = newUa
            }
            val handler = Handler(Looper.getMainLooper())
            WebViewScraper(webView, handler)
        }

        private const val CONSENT_AND_EXTRACT_JS = """
            (function(){
                var consentBtns = ['#L2AGLb', '#bnp_btn_accept', '#accept-all', 'button[aria-label*="Accept"]', 'button[aria-label*="Agree"]'];
                for (var i = 0; i < consentBtns.length; i++) {
                    var b = document.querySelector(consentBtns[i]);
                    if (b) { b.click(); }
                }

                function extract(){
                    var items = [], seen = new Set();
                    var hostname = window.location.hostname || '';
                    
                    function addItem(title, link, thumb, source) {
                        if (!link || link.indexOf('http') !== 0) return;
                        var href = link.split('#')[0];
                        if (seen.has(href)) return;
                        
                        var lowHref = href.toLowerCase();
                        if (lowHref.indexOf('google.') >= 0 || lowHref.indexOf('bing.com') >= 0 || lowHref.indexOf('yandex.') >= 0 || lowHref.indexOf('tineye.com') >= 0) return;
                        if (!thumb || thumb.length < 15) return;
                        
                        var lowThumb = thumb.toLowerCase();
                        var badThumb = ['logo', 'icon', 'favicon', 'avatar', 'default', 'shutterstock', 'istock', 'data:image/gif'];
                        for (var i = 0; i < badThumb.length; i++) {
                            if (lowThumb.indexOf(badThumb[i]) >= 0) return;
                        }
                        
                        var score = 100;
                        var socialCDNs = ['cdninstagram.com', 'fbcdn.net', 'twimg.com', 'tiktokcdn.com'];
                        for (var i = 0; i < socialCDNs.length; i++) {
                            if (lowThumb.indexOf(socialCDNs[i]) >= 0) {
                                score = 500;
                                break;
                            }
                        }

                        var cleanTitle = (title || 'Visual Match').replace(/\s+/g,' ').trim().slice(0, 100);
                        if (cleanTitle.toLowerCase().indexOf('sign in') >= 0 || cleanTitle.length < 3) return;
                        
                        seen.add(href);
                        items.push({
                            title: cleanTitle,
                            link: href,
                            thumbnail: thumb,
                            source: source,
                            score: score
                        });
                    }

                    if (hostname.indexOf('tineye.com') >= 0) {
                        document.querySelectorAll('.match-row, .match, .result-row').forEach(function(row) {
                            var linkEl = row.querySelector('h4 a, p a, .match-details a, a[href^="http"]');
                            var imgEl = row.querySelector('.match-thumb img, .image img, img');
                            if (linkEl && imgEl) {
                                addItem(linkEl.innerText || 'TinEye Match', linkEl.href, imgEl.src, 'TinEye');
                            }
                        });
                        if (items.length > 0) return items;
                    }

                    if (hostname.indexOf('google.') >= 0) {
                        document.querySelectorAll('a.V6bBh, a.Luz2Q, a.G714Sc, .uaqyqd a, .G6S96 a, a.cspn0c').forEach(function(a) {
                            var img = a.querySelector('img') || a.closest('div')?.querySelector('img');
                            var imgSrc = img ? (img.src || img.getAttribute('data-src')) : null;
                            addItem(a.innerText || a.getAttribute('aria-label'), a.href, imgSrc, 'Google Lens');
                        });
                        if (items.length > 0) return items;
                    }

                    if (hostname.indexOf('bing.com') >= 0) {
                        document.querySelectorAll('.imgpt a, .iusc, .visual_search_results a, .richImgLnk, .infopt a').forEach(function(a) {
                            var href = a.href || a.getAttribute('m');
                            if (href && href.indexOf('{') === 0) {
                                try { var m = JSON.parse(href); href = m.purl || m.murl; } catch(e){}
                            }
                            var img = a.querySelector('img') || a.closest('.imgpt, .img_cont, .dg_u, div')?.querySelector('img');
                            var imgSrc = img ? (img.src || img.getAttribute('data-src')) : null;
                            addItem(a.innerText || a.getAttribute('aria-label'), href, imgSrc, 'Bing Visual');
                        });
                        if (items.length > 0) return items;
                    }

                    if (hostname.indexOf('yandex.') >= 0) {
                        document.querySelectorAll('.CbirItem-Title a, .serp-item__link, .CbirSites-ItemTitle a, .CbirItem-TitleLink').forEach(function(a) {
                            var img = a.closest('.CbirItem, .serp-item, .CbirSites-Item, div')?.querySelector('img');
                            var imgSrc = img ? (img.src || img.getAttribute('data-src') || img.getAttribute('src')) : null;
                            addItem(a.innerText, a.href, imgSrc, 'Yandex');
                        });
                        if (items.length > 0) return items;
                    }

                    document.querySelectorAll('a[href^="http"]').forEach(function(a){
                        try {
                            var href = a.href;
                            if (href.indexOf('google.com/url?') >= 0) {
                                var match = href.match(/url\?q=([^&]+)/);
                                if (match) href = decodeURIComponent(match[1]);
                            }
                            var img = a.querySelector('img') || a.closest('div')?.querySelector('img');
                            var imgSrc = img ? (img.src || img.getAttribute('data-src')) : null;
                            addItem(a.innerText || a.title, href, imgSrc, 'Web');
                        } catch(e){}
                    });

                    return items;
                }
                Native.onResults(JSON.stringify(extract()));
            })();
        """

        private const val DORK_EXTRACT_JS = """
            (function(){
                function extract(){
                    var items = [], seen = new Set();
                    var rows = document.querySelectorAll('li.b_algo, .b_algo, .result, .result__body, .g, .dg_u, .vr_items');
                    if(!rows.length) rows = document.querySelectorAll('a[href^="http"]');

                    rows.forEach(function(row){
                        try {
                            var a = row.tagName === 'A' ? row : row.querySelector('h2 a, .result__a, .b_title a, a[href^="http"]');
                            if(!a) a = row.closest('a') || row.querySelector('a');
                            if(!a) return;
                            var href = a.href.split('#')[0];
                            if(!href || href.indexOf('http') !== 0 || seen.has(href)) return;

                            var lowHref = href.toLowerCase();
                            if (lowHref.indexOf('bing.com') >= 0 || lowHref.indexOf('google.') >= 0 || lowHref.indexOf('microsoft.com') >= 0) return;

                            var title = (a.innerText || a.textContent || '').replace(/\s+/g,' ').trim();
                            if(title.length < 3) return;

                            var img = row.querySelector('img') || row.closest('div')?.querySelector('img');
                            var thumb = img ? (img.src || img.getAttribute('data-src') || img.getAttribute('src')) : null;

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
        url = "https://www.bing.com/images/searchbyimage?cbir=sbi&imgurl=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}&adlt=off",
        engineName = "Bing",
        delayMs = 9000
    )

    suspend fun scrapeTinEye(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://tineye.com/search?url=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}",
        engineName = "TinEye",
        delayMs = 6000
    )

    suspend fun scrapeYandex(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://yandex.com/images/search?rpt=imageview&url=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}&family=no",
        engineName = "Yandex",
        delayMs = 9000
    )

    suspend fun scrapeSocialDork(
        site: String, 
        keyword: String,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = scrapeEngine(
        url = "https://www.bing.com/search?q=site:${site}+%22${java.net.URLEncoder.encode(keyword, "UTF-8")}%22&adlt=off&safesearch=0",
        engineName = AdultSiteConfig.labelFor(site),
        delayMs = 3000,
        extractJs = DORK_EXTRACT_JS,
        onLog = onLog
    )

    suspend fun scrapeBatchedAdultDork(
        sites: List<String>,
        keyword: String,
        groupLabel: String,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> {
        val siteQuery = sites.joinToString(" OR ") { "site:$it" }
        val encodedQuery = java.net.URLEncoder.encode("($siteQuery) \"$keyword\"", "UTF-8")
        val targetUrl = "https://www.bing.com/search?q=$encodedQuery&adlt=off&safesearch=0"
        
        return scrapeEngine(
            url = targetUrl,
            engineName = groupLabel,
            delayMs = 3500,
            extractJs = DORK_EXTRACT_JS,
            onLog = onLog
        )
    }

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
            val maxTimeout = 16000L

            val timeoutRunnable = Runnable {
                if (continuation.isActive) continuation.resume(accumulated.values.toList())
            }
            handler.postDelayed(timeoutRunnable, maxTimeout)
            onLog("Scanning $engineName...")

            val bridge = object {
                @JavascriptInterface
                fun onResults(json: String) {
                    try {
                        val arr = JSONArray(json)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val link = obj.optString("link")
                            if (link.isBlank() || accumulated.containsKey(link)) continue

                            val itemSource = if (engineName.contains("Adult") || engineName.contains("Networks")) {
                                AdultSiteConfig.labelFor(link)
                            } else {
                                engineName
                            }

                            accumulated[link] = SerpVisualMatch(
                                title = obj.optString("title", "Visual Match"),
                                link = link,
                                source = itemSource,
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
                    }, delayMs + 2000)

                    handler.postDelayed({
                        view?.evaluateJavascript(extractJs, null)
                    }, delayMs + 4000)

                    handler.postDelayed({
                        view?.evaluateJavascript(extractJs, null)
                    }, delayMs + 6000)
                }
            }
            webView.loadUrl(url)
        }
    }

    fun destroy() {
        handler.post { webView.destroy() }
    }
}
