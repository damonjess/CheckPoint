package com.yourcompany.facesearch.network

import android.content.Context
import android.util.Base64
import com.yourcompany.facesearch.ui.SearchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
        searchMode: SearchMode = SearchMode.PRECISION,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {

        val allResults = mutableListOf<SerpVisualMatch>()
        
        // === STEP 1: CONVERT TO BASE64 (BYPASS EXTERNAL HOSTS) ===
        onLog("Encoding image for direct transmission...")
        val stream = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
        val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

        // === STEP 2: TERMUX BACKEND ===
        onLog("Sending probe directly to Cluster (Local-Only)...")
        var connected = false

        for (url in backendUrls) {
            if (connected) break
            try {
                val label = if (url.contains("10.0.2.2")) "Emulator Host" else "Local Termux"
                onLog("Probing $label...")

                val payload = JSONObject().apply {
                    put("imageBase64", base64Image)
                    put("keywordHint", keywordHint ?: "")
                    if (searchMode == SearchMode.DEEP_CRAWL) {
                        put("deepCrawl", true)
                    }
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

        // === STEP 3: FALLBACK TO WEBVIEW (Still uses hosting if termux fails) ===
        if (allResults.isEmpty()) {
            onLog("Termux cluster unreachable. Attempting legacy hosting fallback...")
            val publicUrl = freeHost.upload(bitmap) { onLog(it) }
            if (publicUrl != null) {
                try {
                    val scraper = WebViewScraper(context)
                    val webResults = scraper.scrapeYandex(publicUrl)
                    if (webResults.isNotEmpty()) {
                        allResults.addAll(webResults)
                        onLog("✓ WebView fallback: ${webResults.size} results")
                    }
                    scraper.destroy()
                } catch (e: Exception) {
                    onLog("⚠ WebView error: ${e.message}")
                }
            }
        }

        if (allResults.isEmpty()) {
            onLog("✗ No free engines returned results.")
        }

        // === STEP 4: FILTER, SCORE & DEDUPLICATE ===
        allResults
            .filter { !isGarbageResult(it, keywordHint) }
            .map { match ->
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

    private fun isGarbageResult(match: SerpVisualMatch, keywordHint: String?): Boolean {
        val title = match.title ?: ""
        val link = match.link ?: ""

        // Block dimension-only titles: "620×634", "600x600Андрей"
        if (Regex("^\\d+\\s*[×xX]\\s*\\d+").containsMatchIn(title) && title.length < 20) return true

        // Block known spam domains
        val spammy = listOf("znakomstva", "dating", "sex.", "escort", "bride", "dosug", "sintim")
        val combined = "$link $title".lowercase()
        if (spammy.any { combined.contains(it) }) return true

        // If hint is English/Latin, penalize heavy Cyrillic unless it's VK
        val hint = keywordHint?.lowercase()?.trim() ?: ""
        if (hint.isNotBlank() && hint.all { it in 'a'..'z' || it in 'A'..'Z' || it.isWhitespace() || it.isDigit() }) {
            val cyrillicCount = title.count { it in '\u0400'..'\u04FF' }
            if (cyrillicCount > 5 && !link.contains("vk.com")) return true
        }

        return false
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
