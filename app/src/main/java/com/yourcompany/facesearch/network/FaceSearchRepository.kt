package com.yourcompany.facesearch.network

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.yourcompany.facesearch.network.model.ServerSearchRequest
import com.yourcompany.facesearch.network.model.ServerSearchResponse
import android.util.Log
import java.util.concurrent.TimeUnit
import com.yourcompany.facesearch.network.ThumbnailUtils

class FaceSearchRepository(private val context: Context) {

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgents.random())
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .build()
            chain.proceed(request)
        }
        .build()

    // Faster client for local discovery
    private val fastClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val freeHost = FreeImageHost()


    var activeBackend: String? = null
        private set

    private fun mapModeForBackend(mode: String): String = when (mode) {
        "DEEP_CRAWL" -> "DEEP"
        "SOCIAL_OPTIMIZED" -> "SOCIAL"
        else -> mode
    }

    // Prevent concurrent searches
    private val searchMutex = Mutex()

    suspend fun performFaceSearch(
        bitmap: android.graphics.Bitmap,
        faceBitmap: android.graphics.Bitmap? = null,
        keywordHint: String? = null,
        imageUrl: String? = null,
        deepCrawl: Boolean = false,
        searchMode: String = "PRECISION",
        includeExactLensMatches: Boolean = false,
        skipVisualEngines: Boolean = false,
        enginesToSkip: Set<String> = emptySet(),
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = searchMutex.withLock {
        withContext(Dispatchers.IO) {
            val allResults = java.util.Collections.synchronizedList(mutableListOf<SerpVisualMatch>())

            // STEP 1: HOST IMAGE (if not already hosted)
            val visualProbe = faceBitmap ?: bitmap
            val publicUrl = if (imageUrl != null && imageUrl.startsWith("http")) {
                imageUrl
            } else {
                onLog("Uploading face probe to free hosting...")
                freeHost.upload(visualProbe, onLog)
            }

            if (publicUrl == null) {
                onLog("✗ All free hosts failed. Visual search will be skipped.")
            } else {
                if (imageUrl == null || imageUrl != publicUrl) {
                    onLog("✓ Probe live: ${publicUrl.take(45)}...")
                }
            }

            // Parallelized OSINT Pipeline
            coroutineScope {
                // Deep Crawl: Search mirrored image as well for asymmetric matchers
                if (deepCrawl && publicUrl != null) {
                    onLog("Deep Crawl: Generating asymmetric mirrored probe...")
                    // Logic handled by caller or we can do a mirror upload here if needed
                }
                // We no longer auto-skip WebView engines just because Termux is available;
                // the caller (CheckInViewModel) manages the balance between local and offloaded engines.
                val visualJobs = if (!skipVisualEngines && publicUrl != null) {
                    val jobs = mutableListOf<Deferred<Unit>>()
                    
                    if ("Yandex" !in enginesToSkip) {
                        jobs.add(async { 
                            val s = WebViewScraper.create(context)
                            try {
                                val matches: List<SerpVisualMatch> = s.scrapeYandex(publicUrl)
                                onLog("Yandex found ${matches.size} candidate(s)")
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }

                    if ("Bing" !in enginesToSkip) {
                        jobs.add(async { 
                            val s = WebViewScraper.create(context)
                            try {
                                val matches: List<SerpVisualMatch> = s.scrapeBing(publicUrl)
                                onLog("Bing found ${matches.size} candidate(s)")
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }

                    if ("Google" !in enginesToSkip) {
                        jobs.add(async { 
                            val s = WebViewScraper.create(context)
                            try {
                                val matches: List<SerpVisualMatch> = s.scrapeGoogle(publicUrl)
                                onLog("Google found ${matches.size} candidate(s)")
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }

                    if ("Baidu" !in enginesToSkip) {
                        jobs.add(async { 
                            val s = WebViewScraper.create(context)
                            try {
                                val matches: List<SerpVisualMatch> = s.scrapeBaidu(publicUrl)
                                onLog("Baidu found ${matches.size} candidate(s)")
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }
                    
                    if ("TinEye" !in enginesToSkip) {
                        jobs.add(async {
                            val s = WebViewScraper.create(context)
                            try {
                                val matches: List<SerpVisualMatch> = s.scrapeTinEye(publicUrl)
                                onLog("TinEye found ${matches.size} candidate(s)")
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }

                    // Add SerpApi if key is available
                    if (com.yourcompany.facesearch.BuildConfig.SERP_API_KEY.isNotBlank()) {
                        jobs.add(async { 
                            val matches: List<SerpVisualMatch> = performSerpApiSearch(
                                publicUrl,
                                includeExactLensMatches,
                                onLog
                            )
                            allResults.addAll(matches)
                            Unit
                        })
                    }

                    jobs
                } else {
                    onLog("Termux handling visual engines, skipping local WebView...")
                    emptyList()
                }

                // Wait for visual jobs first to potentially extract hints for social dorking
                visualJobs.awaitAll()
                
                // If keywordHint is missing, try to harvest names from visual matches for a follow-up social scan
                val harvestedHints = if (keywordHint.isNullOrBlank()) {
                    harvestSearchHints(allResults.toList())
                } else emptyList()

                val effectiveHint = keywordHint ?: harvestedHints.firstOrNull()

                // Dorking & Social Scan (Parallel)
                val dorkJobs = mutableListOf<Deferred<*>>()
                if (!effectiveHint.isNullOrBlank()) {
                    if (searchMode != "ADULT") {
                        dorkJobs.add(async {
                            onLog("Running core social profile queries for '$effectiveHint'...")
                            val s = WebViewScraper.create(context)
                            try {
                                allResults.addAll(s.scrapeSocialDork("instagram.com", effectiveHint))
                                allResults.addAll(s.scrapeSocialDork("facebook.com", effectiveHint))
                                allResults.addAll(s.scrapeSocialDork("twitter.com", effectiveHint))
                                allResults.addAll(s.scrapeDuckDuckGo(effectiveHint))
                            } finally { s.destroy() }
                        })
                    }

                    // A reverse-image engine often exposes several spellings of a
                    // person's name or username. Query the additional hints too;
                    // otherwise the no-name flow stops after the first weak lead.
                    if (keywordHint.isNullOrBlank()) {
                        harvestedHints.drop(1).forEach { additionalHint ->
                            dorkJobs.add(async {
                                onLog("Checking another public identity hint: '$additionalHint'...")
                                val s = WebViewScraper.create(context)
                                try {
                                    allResults.addAll(s.scrapeSocialDork("instagram.com", additionalHint))
                                    allResults.addAll(s.scrapeSocialDork("facebook.com", additionalHint))
                                    allResults.addAll(s.scrapeSocialDork("tiktok.com", additionalHint))
                                    allResults.addAll(s.scrapeSocialDork("linkedin.com", additionalHint))
                                } finally { s.destroy() }
                            })
                        }
                    }

                    val expandedSocialMode = searchMode in setOf(
                        "SOCIAL", "SOCIAL_OPTIMIZED", "HYPER", "AGGRESSIVE", "DEEP_CRAWL"
                    )
                    if (expandedSocialMode) {
                        dorkJobs.add(async {
                            onLog("Expanding social coverage for '$effectiveHint' across LinkedIn, X, TikTok, Reddit, Pinterest, Threads, and YouTube...")
                            val s = WebViewScraper.create(context)
                            try {
                                val expandedSites = listOf(
                                    "linkedin.com", "x.com", "tiktok.com", "reddit.com",
                                    "pinterest.com", "threads.net", "youtube.com", "onlyfans.com", 
                                    "fansly.com", "snapchat.com", "vsco.co", "tumblr.com", 
                                    "flickr.com", "quora.com", "medium.com", "vk.com", 
                                    "ok.ru", "github.com", "weibo.com", "t.me"
                                )
                                expandedSites.forEach { site ->
                                    allResults.addAll(s.scrapeSocialDork(site, effectiveHint))
                                }
                            } finally { s.destroy() }
                        })
                    }

                    if (searchMode == "AGGRESSIVE" || searchMode == "DEEP_CRAWL" || searchMode == "ADULT") {
                        dorkJobs.add(async {
                            onLog(
                                if (searchMode == "ADULT") {
                                    "Running targeted adult platform scan for '$effectiveHint'..."
                                } else {
                                    "Running expanded public-web and adult-platform coverage for '$effectiveHint'..."
                                }
                            )

                            // Prefer Termux for adult scans if available (better bypassing)
                            if (isLocalBackendAvailable()) {
                                onLog("Offloading deep platform scan to Termux helper...")
                                val termuxHits = performTermuxDorkSearch(effectiveHint, AdultSiteConfig.SITES, onLog)
                                allResults.addAll(termuxHits)
                            } else {
                                val s = WebViewScraper.create(context)
                                try {
                                    allResults.addAll(s.scrapeAdultSites(effectiveHint, onLog))
                                } finally { s.destroy() }
                            }
                        })
                    }
                } else if (searchMode == "ADULT") {
                    onLog("⚠ Adult scan requires a name hint for deep platform dorking.")
                }

                // Wait for social dorking
                dorkJobs.awaitAll()
                onLog("✓ Global pipeline complete")
            }

            // Deduplicate & sort
            allResults.distinctBy { it.link }.sortedByDescending { it.score }
        }
    }

    /**
     * Harvests potential names and usernames from the most relevant visual matches.
     * This allows the "social" search to function even when the user
     * provides no initial name hint.
     */
    private fun harvestSearchHints(matches: List<SerpVisualMatch>): List<String> {
        val candidates = matches
            .filter { !it.title.isNullOrBlank() && it.title != "Visual Match" }
            .filter { it.score > 80 } // Lowered threshold to harvest more leads
            .take(100)

        if (candidates.isEmpty()) return emptyList()
        
        val hints = mutableSetOf<String>()
        val stopWords = setOf(
            "image", "photo", "profile", "picture", "social", "media", "results", "found", "match", "visual",
            "tiktok", "instagram", "facebook", "twitter", "reddit", "youtube", "threads", "linkedin", 
            "snapchat", "pinterest", "onlyfans", "fansly", "the", "and", "for", "with", "from", "via",
            "user", "account", "profile", "official", "page", "public", "private", "follow", "subscriber",
            "yandex", "bing", "google", "lens", "tineye", "baidu", "view", "visit", "lacoste", "asos",
            "clothing", "fashion", "stock", "model", "portrait", "person", "human", "face", "search", "engine"
        )
        
        candidates.forEach { match ->
            // Try to extract usernames from URLs (e.g. instagram.com/username)
            runCatching {
                val uri = android.net.Uri.parse(match.link)
                val path = uri.path.orEmpty().trim('/')
                if (path.isNotEmpty() && !path.contains('/')) {
                    val candidate = path.lowercase().replace(Regex("[^a-z0-9_]"), "")
                    if (candidate.length > 3 && candidate !in stopWords) {
                        hints.add(path) // Original case for username
                    }
                }
            }

            // Extract multi-word names from titles
            val words = match.title?.split(Regex("\\s+")).orEmpty()
                .map { it.trim().replace(Regex("[^a-zA-Z]"), "") }
                .filter { it.length > 2 && it.lowercase() !in stopWords }
            
            if (words.size >= 2) {
                // Potential Full Name (e.g. "John Doe")
                hints.add("${words[0]} ${words[1]}")
            }
            if (words.isNotEmpty()) {
                hints.add(words[0])
            }
        }
        
        return hints.toList().take(12)
    }

    suspend fun performLocalServerSearch(
        bitmap: android.graphics.Bitmap,
        faceBitmap: android.graphics.Bitmap? = null,
        keywordHint: String? = null,
        imageUrl: String? = null,
        searchMode: String = "PRECISION",
        onLog: (String) -> Unit = {}
    ): ServerSearchResponse {
        Log.e("CheckIn", "!!! REPO LOG !!! performLocalServerSearch started. Mode: $searchMode")

        // 1. Use existing URL or upload the FACE image if possible, otherwise full
        val publicUrl = if (imageUrl != null && imageUrl.startsWith("http")) {
            imageUrl
        } else {
            val probe = faceBitmap ?: bitmap
            onLog("Uploading face probe to Termux host...")
            freeHost.upload(probe) { log ->
                onLog(log)
                Log.e("CheckIn", "REPO_UPLOAD_LOG: $log")
            }
        }

        if (publicUrl == null) {
            onLog("⚠ Public upload failed. Termux will use internal loopback probe.")
            Log.e("CheckIn", "Public upload failed - will rely on localFaceUrl")
        } else if (imageUrl == null || imageUrl != publicUrl) {
            onLog("✓ Hosted: ${publicUrl.take(30)}...")
        }
        Log.e("CheckIn", "REPO LOG: Image ready: $publicUrl. Calling Termux...")

        // 2. Determine the correct backend URL
        val backendBase = activeBackend ?: "http://127.0.0.1:3000"
        onLog("Connecting to Termux at $backendBase...")
        Log.e("CheckIn", "Using Termux backend: $backendBase")

        val backendMode = mapModeForBackend(searchMode)
        onLog("Starting local helper search ($backendMode)...")

        val request = ServerSearchRequest(
            imageUrl = publicUrl ?: "http://127.0.0.1:8080/face.jpg",
            keywordHint = keywordHint,
            searchMode = backendMode,
            localBypassUrl = "http://127.0.0.1:8080/probe.jpg",
            localFaceUrl = "http://127.0.0.1:8080/face.jpg",
            searchTarget = "FACE"
        )

        return try {
            // Use the discovered backend instead of hardcoded 127.0.0.1
            val api = RetrofitClient.getInstance(backendBase)
            val response = api.searchByImage(request)

            val total = response.matches?.size ?: 0
            val allZeroEngines = response.meta?.engines?.values?.all { it.count == 0 } ?: false
            if (response.success && total == 0 && allZeroEngines) {
                val blocked = response.meta?.blockedEngines.orEmpty()
                if (blocked.isNotEmpty()) {
                    onLog("⚠ ${blocked.joinToString()} requested an access challenge.")
                } else {
                    onLog("No visual candidates were returned by the local helper.")
                }
                return response
            }

            if (response.success) {
                val engines = response.meta?.engines?.keys?.joinToString(", ") ?: "Multiple"
                onLog("✓ Termux SUCCESS: $total matches via $engines")
            } else {
                onLog("⚠ Termux error: ${response.error}")
            }
            response
        } catch (e: Exception) {
            val errorMsg = when {
                e.message?.contains("ECONNREFUSED") == true -> "Termux Server disconnected"
                e.message?.contains("timeout") == true -> "Termux helper timed out"
                e.message?.contains("CLEARTEXT") == true -> "HTTP blocked by Android (fix: usesCleartextTraffic)"
                else -> "Termux Error: ${e.message ?: "Unknown network failure"}"
            }
            onLog("✗ $errorMsg")
            Log.e("CheckIn", "Termux call failed: ${e.message}", e)
            ServerSearchResponse(
                success = false,
                error = errorMsg
            )
        }
    }

    suspend fun isLocalBackendAvailable(): Boolean = withContext(Dispatchers.IO) {
        val backendBase = "http://127.0.0.1:3000"
        val pingUrl = "$backendBase/api/ping"

        try {
            val request = Request.Builder()
                .url(pingUrl)
                .get()
                .build()

            fastClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    activeBackend = backendBase
                    Log.e("CheckIn", "✓ Found Termux backend at $backendBase")
                    return@withContext true
                }

                Log.w("CheckIn", "✗ Termux ping returned HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("CheckIn", "✗ Termux probe failed: ${e.message}", e)
        }

        false
    }

    suspend fun extractHighResMedia(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val backendBase = activeBackend ?: "http://127.0.0.1:3000"
            val api = RetrofitClient.getInstance(backendBase)
            val response = api.extractMedia(mapOf("url" to url))
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    return@withContext body.highResUrl
                }
            }
        } catch (e: Exception) {
            Log.e("CheckIn", "Extraction error: ${e.message}")
        }
        null
    }

    suspend fun performTermuxDorkSearch(
        keyword: String,
        sites: List<String> = AdultSiteConfig.SITES,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        onLog("Requesting Termux Dork Scan...")
        try {
            val request = com.yourcompany.facesearch.network.model.DorkSearchRequest(
                keyword = keyword,
                sites = sites
            )
            val backendBase = activeBackend ?: "http://127.0.0.1:3000"
            val api = RetrofitClient.getInstance(backendBase)
            val response = api.dorkSearch(request)
            if (response.success) {
                onLog("✓ Termux Dork found ${response.matches?.size ?: 0} hits")
                return@withContext response.matches?.map {
                    SerpVisualMatch(
                        title = it.title,
                        link = it.link,
                        source = it.source,
                        thumbnail = it.thumbnail,
                        score = it.score
                    )
                } ?: emptyList()
            }
        } catch (e: Exception) {
            onLog("⚠ Termux Dork failed: ${e.message}")
        }
        emptyList()
    }

    suspend fun performSerpApiSearch(
        imageUrl: String,
        includeExactMatches: Boolean = false,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        val apiKey = com.yourcompany.facesearch.BuildConfig.SERP_API_KEY
        if (apiKey.isBlank()) return@withContext emptyList()

        onLog(if (includeExactMatches) {
            "Requesting Google Lens visual and exact matches via SerpApi..."
        } else {
            "Requesting Google Lens visual matches via SerpApi..."
        })
        val api = RetrofitClient.getSerpApi()
        try {
            val visualResponse = runCatching {
                api.googleLensSearch(url = imageUrl, type = "visual_matches", apiKey = apiKey)
            }.getOrElse { error ->
                onLog("⚠ Lens visual-match request failed: ${error.message}")
                null
            }
            val exactResponse = if (includeExactMatches) {
                runCatching {
                    api.googleLensSearch(url = imageUrl, type = "exact_matches", apiKey = apiKey)
                }.getOrElse { error ->
                    onLog("⚠ Lens exact-match request failed: ${error.message}")
                    null
                }
            } else {
                null
            }

            val visualMatches = visualResponse?.visualMatches.orEmpty().map { match ->
                SerpVisualMatch(
                    title = match.title,
                    link = match.link,
                    source = "Google Lens visual · ${match.source ?: "web"}",
                    thumbnail = match.thumbnail,
                    score = 800
                )
            }
            val exactMatches = (exactResponse?.exactMatches ?: exactResponse?.visualMatches).orEmpty().map { match ->
                SerpVisualMatch(
                    title = match.title,
                    link = match.link,
                    source = "Google Lens exact · ${match.source ?: "web"}",
                    thumbnail = match.thumbnail,
                    score = 1_000
                )
            }
            val results = (exactMatches + visualMatches)
                .distinctBy { match -> ThumbnailUtils.canonicalKey(match.thumbnail) ?: match.link }

            onLog(
                if (includeExactMatches) {
                    "✓ SerpApi found ${visualMatches.size} visual and ${exactMatches.size} exact candidate(s)"
                } else {
                    "✓ SerpApi found ${visualMatches.size} visual candidate(s)"
                }
            )
            results
        } catch (e: Exception) {
            onLog("⚠ SerpApi error: ${e.message}")
            emptyList()
        }
    }

    suspend fun extractMetadataThumbnail(url: String): String? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgents.first()) // Use a stable one for generic sites
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val html = response.body?.string() ?: return@withContext null
                
                // Fast regex for og:image
                val ogMatch = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']").find(html)
                    ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']").find(html)
                
                val rawUrl = ogMatch?.groupValues?.get(1)
                ThumbnailUtils.normalize(rawUrl)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseMatches(jsonData: String, onLog: (String) -> Unit): List<SerpVisualMatch> {
        return try {
            val json = JSONObject(jsonData)
            if (!json.optBoolean("success", false)) {
                onLog("⚠ Backend reported failure")
                return emptyList()
            }
            val arr = json.getJSONArray("matches")
            val list = mutableListOf<SerpVisualMatch>()
            val noisePatterns = listOf("privacy", "terms", "cookie", "ads", "protection", "policy", "feedback")
            
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val rawTitle = obj.optString("title", "Match").ifBlank { "Visual Match" }
                val link = obj.optString("link")
                
                // Client-side noise suppression
                if (noisePatterns.any { rawTitle.contains(it, ignoreCase = true) }) continue
                
                // Title Cleaning
                val cleanTitle = rawTitle
                    .replace(Regex("(?i)[|\\-].*(facebook|instagram|twitter|x|linkedin|onlyfans|fansly).*"), "")
                    .trim()

                var score = obj.optInt("score", 100)
                val source = obj.optString("source", "Free Engine")
                
                // Boost Social Scores
                if (source == "Bing" || source == "DuckDuckGo" || source == "Social Dork") {
                    score += 500
                }

                list.add(SerpVisualMatch(
                    title = cleanTitle,
                    link = link,
                    source = source,
                    thumbnail = ThumbnailUtils.normalize(obj.optString("thumbnail")),
                    score = score
                ))
            }
            list
        } catch (e: Exception) {
            onLog("✗ JSON parse error: ${e.message}")
            emptyList()
        }
    }
}
