package com.yourcompany.facesearch.network

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import org.json.JSONArray
import java.net.URLEncoder
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
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true

                val newUa = settings.userAgentString.replace("Mobile", "eliboM").replace("Android", "diordnA")
                settings.userAgentString = newUa
            }
            val handler = Handler(Looper.getMainLooper())
            WebViewScraper(webView, handler)
        }

        /**
         * Clicks common EU/cookie/consent banners as early as possible so the
         * results page actually renders instead of stalling on an interstitial.
         * Only standard CSS selectors are used so querySelectorAll never throws.
         */
        private const val CONSENT_JS = """
            (function(){
                var s = [
                    '#L2AGLb', '#bnp_btn_accept', '#accept-all', '#acceptAll',
                    'a#adlt_set_off', '.bnp_btn_accept', '.js-accept',
                    'div[role="dialog"] button', '.CybotCookiebotDialogBodyButton',
                    '.consent-accept', '.cookie-consent-accept',
                    '[data-testid="cookie-policy-dialog-accept-button"]',
                    'button[aria-label*="Accept"]', 'button[aria-label*="Agree"]',
                    'button[aria-label*="accept"]', 'button[aria-label*="agree"]'
                ];
                for (var i = 0; i < s.length; i++) {
                    try {
                        document.querySelectorAll(s[i]).forEach(function(el){
                            try { el.click(); } catch(e){}
                        });
                    } catch(e){}
                }
            })();
        """

        /**
         * Selector-resilient extractor.
         *
         * Key fixes vs. the old version:
         *  - Unwraps Google (/url?q=, /goto?url=, /imgres?imgurl=), Bing
         *    (imgurl=) and Yandex (url=/rurl=) redirect wrappers so the real
         *    target page is kept instead of being discarded as "internal".
         *  - Skips the uploaded probe image itself (the page's own url=/imgurl=
         *    param) so the user's own upload is never shown as a "match".
         *  - Runs engine-specific selectors first, then a generic
         *    image-bearing-anchor sweep as a fallback so results still appear
         *    when providers rename their CSS classes.
         *  - Parses Bing's .iusc "m" JSON fully (purl/murl/turl/t).
         */
        val VISUAL_EXTRACT_JS = """
            (function(){
                try {
                    var s = ['#L2AGLb','#bnp_btn_accept','#accept-all','#acceptAll','a#adlt_set_off','.bnp_btn_accept','div[role="dialog"] button','button[aria-label*="Accept"]','button[aria-label*="Agree"]','button[aria-label*="accept"]','button[aria-label*="agree"]'];
                    for (var i = 0; i < s.length; i++) { try { document.querySelectorAll(s[i]).forEach(function(el){ try { el.click(); } catch(e){} }); } catch(e){} }
                } catch(e){}

                function unwrap(href){
                    if(!href || href.indexOf('http') !== 0) return null;
                    try {
                        var u = new URL(href, window.location.origin);
                        var h = (u.hostname || '').toLowerCase();
                        if(h.indexOf('google.') >= 0 || h.indexOf('lens.') >= 0){
                            var q = u.searchParams.get('q'); if(q) return q;
                            var g = u.searchParams.get('url'); if(g) return g;
                            var iu = u.searchParams.get('imgurl'); if(iu) return iu;
                        }
                        if(h.indexOf('bing.com') >= 0){
                            var bi = u.searchParams.get('imgurl'); if(bi) return bi;
                        }
                        if(h.indexOf('yandex.') >= 0){
                            var y = u.searchParams.get('url'); if(y) return y;
                            var yr = u.searchParams.get('rurl'); if(yr) return yr;
                        }
                        return href;
                    } catch(e){ return href; }
                }

                function isInternal(href){
                    if(!href) return true;
                    try {
                        var h = new URL(href).hostname.toLowerCase();
                        if(h.indexOf('google.') >= 0) return true;
                        if(h.indexOf('lens.') >= 0) return true;
                        if(h.indexOf('bing.com') >= 0) return true;
                        if(h.indexOf('yandex.') >= 0) return true;
                        if(h.indexOf('tineye.com') >= 0) return true;
                        if(h.indexOf('sogou.com') >= 0) return true;
                        return false;
                    } catch(e){ return true; }
                }

                var hostname = window.location.hostname || '';

                // The uploaded probe image is passed as url=/imgurl= on the
                // search page; never present it back as a match.
                var probeUrl = null;
                try {
                    var pageUrl = new URL(window.location.href);
                    probeUrl = pageUrl.searchParams.get('url') || pageUrl.searchParams.get('imgurl');
                } catch(e){}

                function canonical(u){
                    try {
                        var x = new URL(u);
                        x.hash = '';
                        return x.href.replace(/\/$/, '');
                    } catch(e){ return u; }
                }

                function isProbe(href){
                    if(!probeUrl) return false;
                    try { return canonical(href) === canonical(probeUrl); } catch(e){ return false; }
                }

                var items = [];
                var seen = new Set();

                function imgOf(a){
                    var img = a.querySelector('img');
                    if(!img){
                        var d = a.closest('div, li, article, figure, td');
                        if(d) img = d.querySelector('img');
                    }
                    if(!img) return null;
                    return img.currentSrc || img.src || img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || img.getAttribute('src');
                }

                function addItem(title, link, thumb){
                    var real = unwrap(link);
                    if(!real || real.indexOf('http') !== 0) return;
                    real = real.split('#')[0];
                    if(isInternal(real)) return;
                    if(isProbe(real)) return;
                    if(seen.has(real)) return;
                    if(!thumb || thumb.length < 15) return;
                    if(thumb.indexOf('data:image/gif') === 0) return;
                    seen.add(real);
                    items.push({
                        title: (title || 'Visual Candidate').replace(/\s+/g,' ').trim().substring(0, 120),
                        link: real,
                        thumbnail: thumb,
                        score: 100
                    });
                }

                if(hostname.indexOf('google.') >= 0){
                    document.querySelectorAll('a[href], div[role="link"], div[role="article"]').forEach(function(a){
                        var href = a.href || a.getAttribute('href');
                        var thumb = imgOf(a);
                        var title = a.innerText || a.getAttribute('aria-label') || '';
                        if(href && thumb) addItem(title, href, thumb);
                    });
                } else if(hostname.indexOf('bing.com') >= 0){
                    document.querySelectorAll('.imgpt a, .iusc, .richImgLnk, .imgpt, a.inflnk, .mimg').forEach(function(a){
                        var href = a.href || a.getAttribute('href') || '';
                        var thumb = imgOf(a);
                        var title = a.innerText || a.getAttribute('aria-label') || '';
                        var meta = a.getAttribute('m');
                        if(meta && meta.indexOf('{') === 0){
                            try {
                                var data = JSON.parse(meta);
                                href = data.purl || data.murl || href;
                                thumb = data.turl || data.murl || thumb;
                                title = data.t || title;
                            } catch(e){}
                        }
                        if(href && thumb) addItem(title, href, thumb);
                    });
                } else if(hostname.indexOf('yandex.') >= 0){
                    document.querySelectorAll('.CbirItem-Title a, .serp-item__link, .serp-item a, .other-sites a, .item a').forEach(function(a){
                        var thumb = imgOf(a);
                        var title = a.innerText || '';
                        addItem(title, a.href, thumb);
                    });
                } else if(hostname.indexOf('tineye.com') >= 0){
                    document.querySelectorAll('.match, .result, .match-thumb, div[class*="match"], div[class*="result"], .image-result').forEach(function(el){
                        var a = el.querySelector('a[href^="http"]');
                        var img = el.querySelector('img');
                        if(a){
                            var thumb = img ? (img.src || img.getAttribute('data-src') || img.getAttribute('src')) : null;
                            addItem(a.innerText || a.title || 'Visual Candidate', a.href, thumb);
                        }
                    });
                }

                // Generic fallback: sweep ALL image-bearing anchors so we still
                // capture results when a provider renames its CSS classes.
                if(items.length < 3){
                    document.querySelectorAll('a[href]').forEach(function(a){
                        var real = unwrap(a.href);
                        if(!real || real.indexOf('http') !== 0 || isInternal(real)) return;
                        var thumb = imgOf(a);
                        var title = a.innerText || a.getAttribute('aria-label') || a.title || '';
                        addItem(title, a.href, thumb);
                    });
                }

                Native.onResults(JSON.stringify(items));
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
        url = "https://lens.google.com/uploadbyurl?url=${URLEncoder.encode(imageUrl, "UTF-8")}",
        engineName = "Google",
        delayMs = 7000
    )

    suspend fun scrapeBing(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://www.bing.com/images/searchbyimage?cbir=sbi&imgurl=${URLEncoder.encode(imageUrl, "UTF-8")}&adlt=off",
        engineName = "Bing",
        delayMs = 9000
    )

    suspend fun scrapeTinEye(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://tineye.com/search?url=${URLEncoder.encode(imageUrl, "UTF-8")}",
        engineName = "TinEye",
        delayMs = 6000
    )

    suspend fun scrapeYandex(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://yandex.com/images/search?rpt=imageview&url=${URLEncoder.encode(imageUrl, "UTF-8")}&family=no",
        engineName = "Yandex",
        delayMs = 9000
    )

    suspend fun scrapeSocialDork(
        site: String,
        keyword: String,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> {
        // Split terms so Name and City/Location are quoted separately
        val tokens = keyword.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val formattedQuery = if (tokens.size > 1) {
            tokens.joinToString(" ") { "\"$it\"" }
        } else {
            "\"$keyword\""
        }

        val encodedQuery = URLEncoder.encode("site:$site $formattedQuery", "UTF-8")
        val targetUrl = "https://www.bing.com/search?q=$encodedQuery&adlt=off&safesearch=0"

        return scrapeEngine(
            url = targetUrl,
            engineName = AdultSiteConfig.labelFor(site),
            delayMs = 3000,
            extractJs = DORK_EXTRACT_JS,
            onLog = onLog
        )
    }

    suspend fun scrapeBatchedAdultDork(
        sites: List<String>,
        keyword: String,
        groupLabel: String,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> {
        val siteQuery = sites.joinToString(" OR ") { "site:$it" }
        val encodedQuery = URLEncoder.encode("($siteQuery) \"$keyword\"", "UTF-8")
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
        extractJs: String = VISUAL_EXTRACT_JS,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val accumulated = linkedMapOf<String, SerpVisualMatch>()
            val totalPasses = 4
            val maxAttempts = 2
            var passesDone = 0
            var attempt = 0
            // A generation counter guards against stale delayed callbacks
            // from a previous page load (redirects / reload) incrementing the
            // pass counter. Enough time for initial passes + a reload-retry.
            var generation = 0
            val maxTimeout = (((delayMs + 7000L) * maxAttempts) + 8000L)
                .coerceAtLeast(30000L)
                .coerceAtMost(45000L)

            val scheduled = mutableListOf<Runnable>()

            fun clearScheduled() {
                scheduled.forEach { handler.removeCallbacks(it) }
                scheduled.clear()
            }

            val timeoutRunnable = Runnable {
                if (continuation.isActive) {
                    if (accumulated.isNotEmpty()) onLog("$engineName: ${accumulated.size} candidate(s).")
                    else onLog("$engineName: no candidates (provider may have shown a verification page).")
                    clearScheduled()
                    continuation.resume(accumulated.values.toList())
                }
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
                    if (passesDone >= totalPasses) {
                        // Reload once if the provider initially served an
                        // interstitial that produced no candidates.
                        if (accumulated.isEmpty() && attempt < maxAttempts - 1 && continuation.isActive) {
                            attempt++
                            passesDone = 0
                            clearScheduled()
                            onLog("$engineName: no results yet, reloading once...")
                            handler.post { webView.reload() }
                        } else if (continuation.isActive) {
                            clearScheduled()
                            handler.removeCallbacks(timeoutRunnable)
                            continuation.resume(accumulated.values.toList())
                        }
                    }
                }
            }

            fun postExtract(delay: Long, gen: Int, block: () -> Unit) {
                val r = Runnable { if (gen == generation && continuation.isActive) block() }
                scheduled += r
                handler.postDelayed(r, delay)
            }

            webView.addJavascriptInterface(bridge, "Native")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    // Dismiss consent banners as early as possible so the real
                    // results page is allowed to render.
                    view?.evaluateJavascript(CONSENT_JS, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // A new page load invalidates any pending extraction passes
                    // from the previous (intermediate) page.
                    val gen = ++generation
                    passesDone = 0
                    clearScheduled()

                    view?.evaluateJavascript(CONSENT_JS, null)

                    postExtract(delayMs, gen) {
                        view?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight/3);", null)
                        view?.evaluateJavascript(extractJs, null)
                    }
                    postExtract(delayMs + 2000, gen) {
                        view?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
                        view?.evaluateJavascript(extractJs, null)
                    }
                    postExtract(delayMs + 4000, gen) {
                        view?.evaluateJavascript(extractJs, null)
                    }
                    postExtract(delayMs + 6500, gen) {
                        view?.evaluateJavascript(extractJs, null)
                    }
                }
            }
            webView.loadUrl(url)

            continuation.invokeOnCancellation {
                clearScheduled()
                handler.removeCallbacks(timeoutRunnable)
            }
        }
    }

    fun destroy() {
        handler.post { webView.destroy() }
    }
}
