package com.yourcompany.facesearch.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FaceSearchRepository(private val context: Context) {

    // 90s read timeout to match server sequential scraping
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val freeHost = FreeImageHost()

    // Physical phone: only localhost is needed. Emulator uses 10.0.2.2.
    private val backendUrl = "http://localhost:3000/api/search"

    // Prevent concurrent searches
    private val searchMutex = Mutex()

    suspend fun performFaceSearch(
        bitmap: android.graphics.Bitmap,
        keywordHint: String? = null,
        imageUrl: String? = null, // Accept pre-uploaded URL
        deepCrawl: Boolean = false,
        searchMode: String = "PRECISION",
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = searchMutex.withLock {
        withContext(Dispatchers.IO) {

            val allResults = mutableListOf<SerpVisualMatch>()

            // STEP 1: HOST IMAGE (if not already hosted)
            val publicUrl = if (imageUrl != null) {
                imageUrl
            } else {
                onLog("Uploading to free hosting...")
                freeHost.upload(bitmap, onLog)
            }

            if (publicUrl == null) {
                onLog("✗ All hosts failed.")
                return@withContext emptyList()
            }
            if (imageUrl == null) {
                onLog("✓ Probe live: ${publicUrl.take(45)}...")
            }

            // STEP 2: SINGLE REQUEST TO TERMUX
            onLog("Connecting to Termux...")
            val payload = JSONObject().apply {
                put("imageUrl", publicUrl)
                put("keywordHint", keywordHint ?: "")
                put("deepCrawl", deepCrawl)
                put("searchMode", searchMode)
            }.toString()

            val request = Request.Builder()
                .url(backendUrl)
                .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            try {
                client.newCall(request).execute().use { res ->
                    if (res.isSuccessful) {
                        val data = res.body?.string() ?: ""
                        val matches = parseMatches(data, onLog)
                        allResults.addAll(matches)
                        onLog("✓ Termux returned ${matches.size} results")
                    } else {
                        onLog("⚠ Termux HTTP ${res.code}")
                    }
                }
            } catch (e: Exception) {
                onLog("✗ Termux unreachable: ${e.message}")
            }

            // STEP 3: WEBVIEW FALLBACK (only if Termux truly failed)
            if (allResults.isEmpty()) {
                onLog("Activating WebView fallback...")
                try {
                    val scraper = WebViewScraper.create(context)
                    val webResults = scraper.scrapeYandex(publicUrl)
                    allResults.addAll(webResults)
                    onLog("✓ WebView: ${webResults.size} results")
                    scraper.destroy()
                } catch (e: Exception) {
                    onLog("⚠ WebView failed: ${e.message}")
                }
            }

            // Deduplicate & sort
            allResults.distinctBy { it.link }.sortedByDescending { it.score }
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
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(SerpVisualMatch(
                    title = obj.optString("title", "Match").ifBlank { "Visual Match" },
                    link = obj.optString("link"),
                    source = obj.optString("source", "Free Engine"),
                    thumbnail = obj.optString("thumbnail").ifBlank { null },
                    score = obj.optInt("score", 100)
                ))
            }
            list
        } catch (e: Exception) {
            onLog("✗ JSON parse error: ${e.message}")
            emptyList()
        }
    }
}
