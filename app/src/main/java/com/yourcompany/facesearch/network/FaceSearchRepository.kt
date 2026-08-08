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
        .connectTimeout(15, TimeUnit.SECONDS) // Reduced from 30s
        .readTimeout(120, TimeUnit.SECONDS)   // Reduced from 300s
        .writeTimeout(15, TimeUnit.SECONDS)
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

    // Physical phone: only localhost is needed. Emulator uses 10.0.2.2.
    private val potentialBackends = listOf(
        "http://localhost:3000/api/search",
        "http://127.0.0.1:3000/api/search",
        "http://10.0.2.2:3000/api/search" // For emulators
    )

    private var activeBackend: String? = null

    // Prevent concurrent searches
    private val searchMutex = Mutex()

    suspend fun performFaceSearch(
        bitmap: android.graphics.Bitmap,
        faceBitmap: android.graphics.Bitmap? = null,
        keywordHint: String? = null,
        imageUrl: String? = null, // Accept pre-uploaded URL
        deepCrawl: Boolean = false,
        searchMode: String = "PRECISION",
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = searchMutex.withLock {
        withContext(Dispatchers.IO) {
            val allResults = java.util.Collections.synchronizedList(mutableListOf<SerpVisualMatch>())

            // STEP 1: HOST IMAGE (if not already hosted)
            val visualProbe = faceBitmap ?: bitmap
            val publicUrl = if (imageUrl != null) {
                imageUrl
            } else {
                onLog("Uploading face probe to free hosting...")
                freeHost.upload(visualProbe, onLog)
            }

            if (publicUrl == null) {
                onLog("✗ All hosts failed.")
                return@withContext emptyList()
            }
            if (imageUrl == null) {
                onLog("✓ Probe live: ${publicUrl.take(45)}...")
            }

            // WebView-only pipeline — no Termux required
            coroutineScope {
                val webViewJob = async {
                    onLog("Starting internal WebView engines...")
                    try {
                        val scraper = WebViewScraper.create(context)
                        
                        val y = scraper.scrapeYandex(publicUrl)
                        val b = scraper.scrapeBing(publicUrl)
                        val g = scraper.scrapeGoogle(publicUrl)
                        val ba = scraper.scrapeBaidu(publicUrl)
                        
                        allResults.addAll(y + b + g + ba)
                        onLog("✓ Visual engines: ${y.size + b.size + g.size + ba.size} hits")
                        
                        if (!keywordHint.isNullOrBlank()) {
                            onLog("Scanning 10 adult platforms (WebView)...")
                            val adultHits = scraper.scrapeAdultSites(keywordHint)
                            allResults.addAll(adultHits)
                            onLog("✓ Adult scan: ${adultHits.size} hits")

                            onLog("Running social dorks...")
                            allResults.addAll(scraper.scrapeSocialDork("instagram.com", keywordHint))
                            allResults.addAll(scraper.scrapeSocialDork("facebook.com", keywordHint))
                            
                            if (searchMode == "AGGRESSIVE" || searchMode == "DEEP_CRAWL") {
                                onLog("Deep social scan...")
                                allResults.addAll(scraper.scrapeSocialDork("twitter.com", keywordHint))
                                allResults.addAll(scraper.scrapeSocialDork("tiktok.com", keywordHint))
                                allResults.addAll(scraper.scrapeSocialDork("reddit.com", keywordHint))
                            }
                        }
                        
                        onLog("✓ WebView engines complete")
                        scraper.destroy()
                    } catch (e: Exception) {
                        onLog("⚠ WebView failed: ${e.message}")
                    }
                }

                webViewJob.await()
            }

            // Deduplicate & sort
            allResults.distinctBy { it.link }.sortedByDescending { it.score }
        }
    }

    suspend fun performLocalServerSearch(
        bitmap: android.graphics.Bitmap,
        faceBitmap: android.graphics.Bitmap? = null,
        keywordHint: String? = null,
        searchMode: String = "HYPER",
        onLog: (String) -> Unit = {}
    ): ServerSearchResponse {
        Log.e("CheckIn", "!!! REPO LOG !!! performLocalServerSearch started. Mode: $searchMode")
        // 1. Upload the FACE image if possible, otherwise full
        val probe = faceBitmap ?: bitmap
        val publicUrl = freeHost.upload(probe) { log -> 
            onLog(log)
            Log.e("CheckIn", "REPO_UPLOAD_LOG: $log")
        }

        if (publicUrl == null) {
            onLog("✗ Upload failed")
            Log.e("CheckIn", "Upload failed - no public URL")
            return ServerSearchResponse(
                success = false,
                error = "Failed to upload image to any host"
            )
        }

        onLog("✓ Hosted: ${publicUrl.take(30)}...")
        Log.e("CheckIn", "REPO LOG: Image uploaded: $publicUrl. Calling Termux...")

        // 2. Call your Termux server
        onLog("Connecting to Termux ($searchMode)...")
        val request = ServerSearchRequest(
            imageUrl = publicUrl,
            keywordHint = keywordHint,
            searchMode = searchMode,
            localBypassUrl = "http://127.0.0.1:8080/probe.jpg",
            localFaceUrl = "http://127.0.0.1:8080/face.jpg",
            searchTarget = "FACE"
        )
        return try {
            val response = RetrofitClient.instance.searchByImage(request)
            Log.e("CheckIn", "REPO LOG: Termux response received. Success: ${response.success}")
            if (response.success) {
                onLog("✓ Termux returned ${response.matches?.size ?: 0} matches")
            } else {
                onLog("⚠ Termux: ${response.error}")
            }
            response
        } catch (e: Exception) {
            val errorMsg = when {
                e.message?.contains("ECONNREFUSED") == true -> "Termux Server not responding on port 3000"
                e.message?.contains("timeout") == true -> "Termux timed out (Scraping limit reached)"
                else -> e.message ?: "Network error"
            }
            onLog("✗ $errorMsg")
            Log.e("CheckIn", "REPO LOG: Termux call failed: $errorMsg", e)
            ServerSearchResponse(
                success = false,
                error = errorMsg
            )
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
