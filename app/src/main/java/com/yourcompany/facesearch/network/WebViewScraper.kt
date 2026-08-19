package com.yourcompany.facesearch.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
                
                // Use a more modern, common User-Agent
                val uas = listOf(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                )
                settings.userAgentString = uas.random()
            }
            val handler = Handler(Looper.getMainLooper())
            WebViewScraper(webView, handler)
        }

        private const val EXTRACT_JS = """
            (function(){
                function pickUrl(v){
                    if(!v || v.length < 8) return '';
                    var low = v.toLowerCase();
                    // Keep most things unless they are obviously tiny junk icons
                    if(low.indexOf('icon') >= 0 || low.indexOf('logo') >= 0 || low.indexOf('favicon') >= 0) {
                       if(low.indexOf('profile') < 0 && low.indexOf('user') < 0) return '';
                    }
                    
                    if(v.indexOf('data:image') === 0 && v.length > 200) return v;
                    if(v.indexOf('http')===0) return v;
                    if(v.indexOf('//')===0) return 'https:' + v;
                    return '';
                }
                function getImgSrc(el){
                    if(!el)return'';
                    var attrs=['src','data-src','data-original','data-lazy-src','data-thumb','data-iurl','data-src-medium','data-src-large','data-zoom','data-actualsrc','data-url','data-imageurl'];
                    for(var i=0;i<attrs.length;i++){
                        var v=el.getAttribute(attrs[i]);
                        var picked = pickUrl(v);
                        if(picked) return picked;
                    }
                    var srcset = el.getAttribute('srcset');
                    if(srcset){
                        var parts = srcset.split(',');
                        for(var p=parts.length-1;p>=0;p--){
                            var u = parts[p].trim().split(/\s+/)[0];
                            var picked2 = pickUrl(u);
                            if(picked2) return picked2;
                        }
                    }
                    if(el.src && el.src.length > 8){
                        var picked3 = pickUrl(el.src);
                        if(picked3) return picked3;
                    }
                    return '';
                }
                function getBingThumb(a){
                    var node = a.closest('.iusc, .imgpt, .mimg') || a;
                    var m = node.getAttribute('m') || a.getAttribute('m');
                    if(!m) return '';
                    try{
                        var parsed = JSON.parse(m);
                        return pickUrl(parsed.turl || parsed.murl || parsed.purl || '');
                    }catch(e){
                        var match = m.match(/turl(?:\\u0022|"):(?:\\u0022|")([^"\\]+)/);
                        if(match) return pickUrl(match[1].replace(/\\u0026/g,'&'));
                    }
                    return '';
                }
                function findNearbyImg(a){
                    if(!a)return'';
                    var bing = getBingThumb(a);
                    if(bing) return bing;
                    var img=a.querySelector('img, picture source, [style*="background-image"]');
                    var src=getImgSrc(img);
                    if(!src && img && img.style && img.style.backgroundImage){
                        var bg = img.style.backgroundImage;
                        var m = bg.match(/url\(["']?(.*?)["']?\)/);
                        if(m) src = pickUrl(m[1]);
                    }
                    if(src)return src;
                    
                    var container = a.closest('.CbirItem, .serp-item, .mimg, .iusc, .G714Sc, .imgpt, .V6bBh, .Luz2Q');
                    if(container){
                        var cImg = container.querySelector('img');
                        src = getImgSrc(cImg);
                        if(src) return src;
                        bing = getBingThumb(container);
                        if(bing) return bing;
                    }

                    var node=a.parentElement;
                    for(var d=0;d<6&&node;d++){
                        bing = getBingThumb(node);
                        if(bing) return bing;
                        var imgs=node.querySelectorAll('img');
                        for(var j=0;j<imgs.length;j++){src=getImgSrc(imgs[j]);if(src)return src;}
                        node=node.parentElement;
                    }
                    return'';
                }
                function cleanText(el){
                    if(!el)return'';
                    var text = (el.innerText||el.textContent||el.getAttribute('aria-label')||el.title||'').replace(/\s+/g,' ').trim();
                    // Strip common UI junk
                    return text.replace(/^(View|Visit|Open|Link to|Image for)\s+/i, '');
                }
                function extract(){
                    var items=[],seen=new Set();
                    var blocked=['yandex.','bing.com','google.com','microsoft.com','gstatic.com','apple.com','baidu.com'];
                    
                    // If we are on a CAPTCHA page, Yandex often has 'checkbox-captcha' or 'smart-captcha'
                    if(document.querySelector('.checkbox-captcha, .smart-captcha, #captcha-form')){
                        console.log('Sherlock: Captcha detected');
                        return items;
                    }

                    // Target specific result containers for higher quality
                    var selectors = [
                        '.CbirItem-Title a', '.serp-item__link', '.iusc', 'a.mimg', 
                        '.G714Sc a', '.iJ41Ze a', '.Vd9M6 a', '.WpHeLc',
                        'a[href^="http"]'
                    ];
                    
                    document.querySelectorAll(selectors.join(',')).forEach(function(a){
                        try{
                            var href=a.href || a.getAttribute('href');
                            if(!href) return;
                            if(href.indexOf('http') !== 0) {
                                if(href.indexOf('//') === 0) href = 'https:' + href;
                                else if(href.indexOf('/') === 0) href = window.location.origin + href;
                                else return;
                            }
                            href = href.split('#')[0];
                            if(seen.has(href))return;
                            if(blocked.some(d => href.indexOf(d)>=0))return;
                            
                            var imgSrc = findNearbyImg(a);

                            // Prioritize Reddit direct images
                            if(window.location.hostname.indexOf('reddit.com') >= 0 || window.location.hostname.indexOf('redd.it') >= 0){
                                var redditImg = a.closest('._3Oa0THmZ3f5iZXAQ0hBJ0k, .STit0aL9CgXLNfU6nzuox, [data-click-id="image"]') || a;
                                var postImg = redditImg.querySelector('img[src*="i.redd.it"], img[src*="i.imgur.com"], img[src*="preview.redd.it"]');
                                if(postImg) imgSrc = getImgSrc(postImg);
                            }

                            var title = cleanText(a).slice(0, 150);
                            
                            if(!imgSrc && title.length < 5) return;
                            
                            seen.add(href);
                            var source = 'Web';
                            var domains = {
                                'instagram.com':'Instagram', 'facebook.com':'Facebook', 'linkedin.com':'LinkedIn',
                                'twitter.com':'Twitter', 'x.com':'Twitter', 'tiktok.com':'TikTok',
                                'vk.com':'VKontakte', 'github.com':'GitHub', 'onlyfans.com':'OnlyFans',
                                'fansly.com':'Fansly', 'pinterest.com':'Pinterest', 'reddit.com':'Reddit'
                            };
                            for(var d in domains){if(href.indexOf(d)>=0){source=domains[d];break;}}
                            
                            items.push({
                                title: title || 'Visual Match',
                                link: href,
                                thumbnail: imgSrc,
                                source: source,
                                score: (source !== 'Web' ? 300 : 50) + (imgSrc ? 100 : 0)
                            });
                        }catch(e){}
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
                    var low = v.toLowerCase();
                    if(low.indexOf('logo') >= 0 || low.indexOf('icon') >= 0 || low.indexOf('favicon') >= 0) return '';
                    if(v.indexOf('data:image') === 0 && v.length > 200) return v;
                    if(v.indexOf('http')===0) return v;
                    if(v.indexOf('//')===0) return 'https:' + v;
                    return '';
                }
                function imgFrom(el){
                    if(!el) return '';
                    var attrs=['src','data-src','data-original','data-thumb','data-iurl'];
                    for(var i=0;i<attrs.length;i++){
                        var picked = pickUrl(el.getAttribute(attrs[i]));
                        if(picked) return picked;
                    }
                    return pickUrl(el.src);
                }
                function extract(){
                    var items=[], seen=new Set();
                    var noise=['privacy','terms','cookie','help','feedback','legal','ads','support','about','protection','sign in','log in'];
                    var rows = document.querySelectorAll('li.b_algo, .b_algo, .result, .result__body, .g');
                    if(!rows.length) rows = document.querySelectorAll('a[href^="http"]');
                    rows.forEach(function(row){
                        try{
                            var a = row.tagName === 'A' ? row : row.querySelector('h2 a, .result__a, .b_title a, a[href^="http"]');
                            if(!a) return;
                            var href = a.href || a.getAttribute('href');
                            if(!href || href.indexOf('http') !== 0) return;
                            href = href.split('#')[0];
                            if(seen.has(href)) return;
                            var title = (a.innerText || a.textContent || '').replace(/\s+/g,' ').trim();
                            if(title.length < 3) return;
                            var low = title.toLowerCase();
                            if(noise.some(function(p){ return low.indexOf(p) >= 0; })) return;
                            var img = row.querySelector('img, .b_imagePair img, .result__image img, .thumb img, .b_attribution img');
                            var thumb = imgFrom(img);
                            seen.add(href);
                            items.push({
                                title: title.slice(0,150),
                                link: href,
                                thumbnail: thumb,
                                source: 'Dork',
                                score: thumb ? 350 : 250
                            });
                        }catch(e){}
                    });
                    return items;
                }
                Native.onResults(JSON.stringify(extract()));
            })();
        """
    }

    suspend fun scrapeYandex(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://yandex.com/images/search?rpt=imageview&url=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}",
        engineName = "Yandex",
        delayMs = 6000 
    )

    suspend fun scrapeBing(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://www.bing.com/visualsearch/Microsoft/Result?imgurl=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}",
        engineName = "Bing",
        delayMs = 5000 
    )

    suspend fun scrapeGoogle(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://lens.google.com/uploadbyurl?url=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}",
        engineName = "Google",
        delayMs = 7000
    )

    suspend fun scrapeBaidu(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://graph.baidu.com/pcpage/index?tpl_from=pc&image=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}",
        engineName = "Baidu",
        delayMs = 7000
    )

    suspend fun scrapeTinEye(imageUrl: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://tineye.com/search?url=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}",
        engineName = "TinEye",
        delayMs = 4000
    )

    /**
     * Performs a 'Dork' search on a specific social site using a search engine.
     */
    suspend fun scrapeSocialDork(site: String, keyword: String): List<SerpVisualMatch> = scrapeEngine(
        url = "https://www.bing.com/search?q=site:${site}+%22${java.net.URLEncoder.encode(keyword, "UTF-8")}%22",
        engineName = AdultSiteConfig.labelFor(site),
        delayMs = 3500,
        extractJs = DORK_EXTRACT_JS
    )

    /** Search all 10 adult platforms via in-app WebView — no Termux required. */
    suspend fun scrapeAdultSites(keyword: String): List<SerpVisualMatch> {
        val all = mutableListOf<SerpVisualMatch>()
        for (site in AdultSiteConfig.SITES) {
            all += scrapeSocialDork(site, keyword)
        }
        return all
    }

    private suspend fun scrapeEngine(
        url: String,
        engineName: String,
        delayMs: Long,
        extractJs: String = EXTRACT_JS
    ): List<SerpVisualMatch> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val accumulated = linkedMapOf<String, SerpVisualMatch>()
            var passesDone = 0
            val totalPasses = 12
            val maxTimeout = 120000L

            val timeoutRunnable = Runnable {
                if (continuation.isActive) continuation.resume(accumulated.values.toList())
            }
            handler.postDelayed(timeoutRunnable, maxTimeout)

            val bridge = object {
                @JavascriptInterface
                fun onResults(json: String) {
                    try {
                        val arr = JSONArray(json)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val link = obj.optString("link")
                            if (link.isBlank() || accumulated.containsKey(link)) continue
                            
                            var source = obj.optString("source", engineName)
                            if (source == "Search Engine") source = engineName

                            accumulated[link] = SerpVisualMatch(
                                title = obj.optString("title", "Visual Match"),
                                link = link,
                                source = source,
                                thumbnail = ThumbnailUtils.normalize(obj.optString("thumbnail")),
                                score = obj.optInt("score", 50)
                            )
                        }
                    } catch (_: Exception) { }

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
                    val random = java.util.Random()
                    val jitter1 = delayMs + random.nextInt(2000)
                    val jitter2 = jitter1 + 2500 + random.nextInt(2000)
                    val jitter3 = jitter2 + 3500 + random.nextInt(2000)
                    val jitter4 = jitter3 + 4500 + random.nextInt(2000)
                    
                    handler.postDelayed({ 
                        view?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight/6);", null)
                        view?.evaluateJavascript(extractJs, null) 
                    }, jitter1)
                    handler.postDelayed({ 
                        view?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight/3);", null)
                        view?.evaluateJavascript(extractJs, null) 
                    }, jitter2)
                    handler.postDelayed({ 
                        view?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight/2);", null)
                        view?.evaluateJavascript(extractJs, null) 
                    }, jitter3)
                    handler.postDelayed({ 
                        view?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight/1.5);", null)
                        view?.evaluateJavascript(extractJs, null) 
                    }, jitter4)
                    
                    // Extra passes for deeper extraction
                    handler.postDelayed({ 
                        view?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight/1.2);", null)
                        view?.evaluateJavascript(extractJs, null) 
                    }, jitter4 + 3000)
                    handler.postDelayed({ 
                        view?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
                        view?.evaluateJavascript(extractJs, null) 
                    }, jitter4 + 6000)
                    handler.postDelayed({ view?.evaluateJavascript(extractJs, null) }, jitter4 + 9000)
                    handler.postDelayed({ view?.evaluateJavascript(extractJs, null) }, jitter4 + 12000)
                    handler.postDelayed({ view?.evaluateJavascript(extractJs, null) }, jitter4 + 15000)
                    handler.postDelayed({ view?.evaluateJavascript(extractJs, null) }, jitter4 + 18000)
                    handler.postDelayed({ view?.evaluateJavascript(extractJs, null) }, jitter4 + 21000)
                    handler.postDelayed({ view?.evaluateJavascript(extractJs, null) }, jitter4 + 24000)
                }
            }
            webView.loadUrl(url)
        }
    }

    fun destroy() {
        handler.post { webView.destroy() }
    }
}



