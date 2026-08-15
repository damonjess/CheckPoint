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
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val freeHost = FreeImageHost()

    // The helper binds to loopback only. The emulator bridge is retained for
    // Android-emulator development; physical devices use localhost/127.0.0.1.
    private val potentialBackends: List<String> = listOf(
        "http://127.0.0.1:3000/api/search",
        "http://localhost:3000/api/search",
        "http://10.0.2.2:3000/api/search"
    )

    private var activeBackend: String? = null

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
        skipVisualEngines: Boolean = false,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = searchMutex.withLock {
        withContext(Dispatchers.IO) {
            val allResults = java.util.Collections.synchronizedList(mutableListOf<SerpVisualMatch>())

            // STEP 1: HOST IMAGE (if not already hosted)
            val visualProbe = faceBitmap ?: bitmap
            val publicUrl = if (imageUrl != null && imageUrl.startsWith("http") && !imageUrl.contains("127.0.0.1") && !imageUrl.contains("localhost")) {
                imageUrl
            } else {
                onLog("Uploading face probe to free hosting...")
                freeHost.upload(visualProbe, onLog)
            }

            if (publicUrl == null) {
                onLog("✗ All hosts failed.")
                return@withContext emptyList()
            }
            if (imageUrl == null || imageUrl != publicUrl) {
                onLog("✓ Probe live: ${publicUrl.take(45)}...")
            }

            // Parallelized OSINT Pipeline
            coroutineScope {
                // Visual Engines (Parallel with Dedicated Scrapers)
                val visualJobs = if (!skipVisualEngines) {
                    val jobs = mutableListOf(
                        async { 
                            val s = WebViewScraper.create(context)
                            try { s.scrapeYandex(publicUrl).also { allResults.addAll(it) } } 
                            finally { s.destroy() }
                        },
                        async { 
                            val s = WebViewScraper.create(context)
                            try { s.scrapeBing(publicUrl).also { allResults.addAll(it) } } 
                            finally { s.destroy() }
                        },
                        async { 
                            val s = WebViewScraper.create(context)
                            try { s.scrapeGoogle(publicUrl).also { allResults.addAll(it) } } 
                            finally { s.destroy() }
                        },
                        async { 
                            val s = WebViewScraper.create(context)
                            try { s.scrapeBaidu(publicUrl).also { allResults.addAll(it) } } 
                            finally { s.destroy() }
                        }
                    )
                    
                    // Add SerpApi if key is available
                    if (com.yourcompany.facesearch.BuildConfig.SERP_API_KEY.isNotBlank()) {
                        jobs.add(async { 
                            performSerpApiSearch(publicUrl, onLog).also { allResults.addAll(it) }
                        })
                    }
                    
                    jobs
                } else {
                    onLog("Termux handling visual engines, skipping local WebView...")
                    emptyList()
                }

                // Dorking & Social Scan (Parallel)
                val dorkJobs = mutableListOf<Deferred<*>>()
                if (!keywordHint.isNullOrBlank()) {
                    dorkJobs.add(async { 
                        onLog("Running social dorks...")
                        val s = WebViewScraper.create(context)
                        try {
                            allResults.addAll(s.scrapeSocialDork("instagram.com", keywordHint))
                            allResults.addAll(s.scrapeSocialDork("facebook.com", keywordHint))
                        } finally { s.destroy() }
                    })
                    
                    if (searchMode == "AGGRESSIVE" || searchMode == "DEEP_CRAWL") {
                        dorkJobs.add(async { 
                            val s = WebViewScraper.create(context)
                            try {
                                allResults.addAll(s.scrapeSocialDork("twitter.com", keywordHint))
                                allResults.addAll(s.scrapeSocialDork("tiktok.com", keywordHint))
                                allResults.addAll(s.scrapeSocialDork("reddit.com", keywordHint))
                            } finally { s.destroy() }
                        })
                    }
                }

                // Wait for everything
                (visualJobs + dorkJobs).awaitAll()
                onLog("✓ Global pipeline complete")
            }

            // Deduplicate & sort
            allResults.distinctBy { it.link }.sortedByDescending { it.score }
        }
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
        val publicUrl = if (imageUrl != null && imageUrl.startsWith("http") && !imageUrl.contains("127.0.0.1") && !imageUrl.contains("localhost")) {
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
            onLog("✗ Upload failed")
            Log.e("CheckIn", "Upload failed - no public URL")
            return ServerSearchResponse(
                success = false,
                error = "Failed to upload image to any host"
            )
        }

        if (imageUrl == null || imageUrl != publicUrl) {
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
            imageUrl = publicUrl,
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
        val backends = potentialBackends
        Log.e("CheckIn", "DEBUG: Probing ${backends.size} backends in parallel...")
        
        val jobs = backends.map { backend ->
            async {
                try {
                    val pingUrl = if (backend.endsWith("/api/search"))
                        backend.replace("/api/search", "/api/ping")
                    else
                        backend

                    val req = Request.Builder().url(pingUrl).build()
                    fastClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            Log.e("CheckIn", "✓ Found Termux at $backend")
                            activeBackend = backend.removeSuffix("/api/search")
                            return@async true
                        }
                    }
                } catch (e: Exception) {
                    // Log.v("CheckIn", "Probe failed for $backend: ${e.message}")
                }
                false
            }
        }
        
        val results = awaitAll(*jobs.toTypedArray())
        val found = results.any { it }
        if (!found) {
            Log.e("CheckIn", "✗ No Termux found after probing ${backends.size} endpoints")
        }
        found
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
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        val apiKey = com.yourcompany.facesearch.BuildConfig.SERP_API_KEY
        if (apiKey.isBlank()) return@withContext emptyList()

        onLog("Requesting Google Lens via SerpApi...")
        try {
            val response = RetrofitClient.getSerpApi().googleLensSearch(url = imageUrl, apiKey = apiKey)
            val results = response.visualMatches?.map {
                SerpVisualMatch(
                    title = it.title,
                    link = it.link,
                    source = it.source,
                    thumbnail = it.thumbnail,
                    score = 800 // High baseline for paid API results
                )
            } ?: emptyList()
            
            onLog("✓ SerpApi found ${results.size} matches")
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



