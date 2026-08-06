package com.yourcompany.facesearch.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FaceSearchRepository(
    private val context: Context
) {
    private val stealthClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val freeHost = FreeImageHost()
    private val backendUrls = listOf(
        "http://localhost:3000/api/search",
        "http://127.0.0.1:3000/api/search",
        "http://10.0.2.2:3000/api/search"
    )

    /**
     * FULL FREE PIPELINE:
     * 1. Try Termux backend (Yandex/Bing/TinEye/Baidu/Dorking)
     * 2. Fallback to in-app WebView scraper
     * 3. Return results — zero paid APIs used.
     */
    suspend fun performFaceSearch(
        bitmap: android.graphics.Bitmap,
        keywordHint: String? = null,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {

        val allResults = mutableListOf<SerpVisualMatch>()
        val logs = mutableListOf<String>()

        // === STEP 1: HOST IMAGE FOR FREE ===
        onLog("Staging probe on free hosting...")
        val publicUrl = freeHost.upload(bitmap) { msg ->
            logs.add(msg)
            onLog(msg)
        }

        if (publicUrl != null) {
            onLog("✓ Probe live: ${publicUrl.take(40)}...")
        } else {
            onLog("⚠ Hosting failed. Attempting local-only search...")
        }

        val imageUrl = publicUrl ?: ""

        // === STEP 2: TERMUX BACKEND ===
        onLog("Connecting to Stealth Automation Cluster...")
        var connected = false

        for (url in backendUrls) {
            if (connected) break
            try {
                val label = if (url.contains("10.0.2.2")) "Emulator Host" else "Local Termux"
                onLog("Probing $label...")

                val payload = JSONObject().apply {
                    put("imageUrl", imageUrl)
                    put("keywordHint", keywordHint ?: "")
                }.toString()

                val req = Request.Builder()
                    .url(url)
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                    .build()

                stealthClient.newCall(req).execute().use { res ->
                    connected = true
                    if (res.isSuccessful) {
                        val data = res.body?.string() ?: ""
                        val matches = parseMatches(data, onLog)
                        allResults.addAll(matches)
                        onLog("✓ Cluster returned ${matches.size} targets")
                    } else {
                        onLog("⚠ Cluster error: HTTP ${res.code}")
                    }
                }
            } catch (e: java.io.IOException) {
                onLog("✗ Connection failed: $url")
            } catch (e: Exception) {
                onLog("✗ Error: ${e.message}")
                connected = true
            }
        }

        // === STEP 3: IN-APP WEBVIEW FALLBACK ===
        if (allResults.isEmpty() && publicUrl != null) {
            onLog("Termux unreachable. Activating in-app WebView fallback...")
            try {
                val scraper = WebViewScraper(context)
                val webResults = scraper.scrapeYandex(publicUrl)
                if (webResults.isNotEmpty()) {
                    allResults.addAll(webResults)
                    onLog("✓ WebView fallback: ${webResults.size} results")
                } else {
                    onLog("⚠ WebView fallback returned 0 results")
                }
                scraper.destroy()
            } catch (e: Exception) {
                onLog("⚠ WebView error: ${e.message}")
            }
        }

        if (allResults.isEmpty()) {
            onLog("✗ No free engines returned results.")
        }

        // === STEP 4: SCORE & DEDUPLICATE ===
        allResults.map { match ->
            var score = match.score
            val link = match.link?.lowercase() ?: ""
            val title = match.title?.lowercase() ?: ""
            val hint = keywordHint?.lowercase()?.trim() ?: ""

            // Boost exact name matches
            if (hint.length > 2 && title.contains(hint)) score += 2000
            if (hint.split(" ").any { it.length > 2 && title.contains(it) }) score += 500

            // Boost social platforms
            val platform = SocialMediaDetector.detectPlatform(match.link)
            score += platform.baseScore

            // Boost profile URLs
            if (link.contains("/profile") || link.contains("/in/") || link.contains("/@") || link.contains("/user/")) score += 400

            match.copy(score = score)
        }
        .sortedByDescending { it.score }
        .distinctBy { it.link }
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
                    source = obj.optString("source", "Stealth Engine"),
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
