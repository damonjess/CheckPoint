package com.yourcompany.facesearch.network

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class FaceSearchRepository(
    private val apiService: SerpApiService = ApiClient.serpApiService,
    private val serpApiKey: String = ApiClient.API_KEY,
    private val faceCheckApi: FaceCheckApi = ApiClient.faceCheckApi,
    private val faceCheckApiKey: String = ApiClient.FACECHECK_API_KEY,
    private val apifyRepository: ApifyRepository = ApifyRepository()
) {

    private val stealthClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS) // Fast fail for discovery
        .readTimeout(60, TimeUnit.SECONDS)    // Allow 60s for Puppeteer to finish
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)      // We handle retries manually via BACKEND_URLS
        .build()

    // Smart discovery: tries local Termux and Emulator Host
    private val BACKEND_URLS = listOf(
        "http://localhost:3000/api/search",
        "http://127.0.0.1:3000/api/search",
        "http://10.0.2.2:3000/api/search"
    )

    suspend fun performFaceCheckSearch(
        bitmap: Bitmap,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> {
        if (faceCheckApiKey.isBlank() || faceCheckApiKey == "null") {
            onLog("⚠ FaceCheck token missing. Skipping biometric scan.")
            return emptyList()
        }
        try {
            onLog("PREPARING FACECHECK.ID UPLOAD...")
            
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val byteArray = stream.toByteArray()
            val requestBody = byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
            val multipartBody = MultipartBody.Part.createFormData("images", "face_probe.jpg", requestBody)

            val bearerToken = if (faceCheckApiKey.startsWith("Bearer ")) faceCheckApiKey else "Bearer $faceCheckApiKey"
            val uploadResponse = faceCheckApi.uploadPic(multipartBody, bearerToken)
            
            if (!uploadResponse.isSuccessful) {
                val errorMsg = uploadResponse.errorBody()?.string() ?: "Unknown error"
                if (uploadResponse.code() == 401 || errorMsg.contains("token", true)) {
                    onLog("⚠ FaceCheck: Invalid API Key or No Tokens left.")
                } else {
                    onLog("✗ UPLOAD FAILED: HTTP ${uploadResponse.code()}")
                }
                return emptyList()
            }
            
            val idSearch = uploadResponse.body()?.idSearch
            if (idSearch == null) {
                onLog("✗ UPLOAD FAILED: No Search ID returned.")
                return emptyList()
            }

            onLog("✓ UPLOAD SUCCESS. SEARCH ID: ${idSearch.take(8)}...")
            onLog("INITIATING ASYNCHRONOUS PROBE...")

            var retryCount = 0
            val maxRetries = 60 
            
            while (retryCount < maxRetries) {
                val searchResponse = faceCheckApi.search(
                    FaceCheckSearchRequest(idSearch = idSearch, demo = true),
                    bearerToken
                )
                
                if (searchResponse.isSuccessful) {
                    val body = searchResponse.body()
                    val progress = body?.progress ?: 0
                    val status = body?.message ?: body?.status ?: "searching"
                    val items = body?.output?.items ?: body?.items
                    
                    onLog("PROBING... $progress% [$status]")
                    
                    if (items != null && items.isNotEmpty()) {
                        onLog("✓ PROBE COMPLETE. FOUND ${items.size} TARGETS")
                        return items.map { match ->
                            SerpVisualMatch(
                                title = match.site ?: "Social Media Profile",
                                link = match.url,
                                source = match.site ?: "Public Profile",
                                thumbnail = if (match.base64 != null) "data:image/jpeg;base64,${match.base64}" else null,
                                score = (match.score ?: 0) * 100
                            )
                        }
                    }
                    if (progress >= 100 || status.contains("completed", ignoreCase = true)) break
                } else {
                    onLog("⚠ POLLING ERROR: HTTP ${searchResponse.code()}")
                }
                delay(2000)
                retryCount++
            }
            onLog("⚠ PROBE FINISHED: No results found.")
            return emptyList()
        } catch (e: Exception) {
            onLog("✗ FACECHECK ERROR: ${e.message}")
            return emptyList()
        }
    }

    suspend fun performFaceSearch(
        uploadedImageUrl: String,
        localImageUrl: String? = null,
        keywordHint: String? = null,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        val verifiedLeads = mutableListOf<SerpVisualMatch>()

        // 1. Stealth Scraper (Termux) - TRY THIS FIRST
        onLog("Connecting to Stealth Automation Cluster...")
        var success = false
        for (url in BACKEND_URLS) {
            if (success) break
            try {
                val label = if (url.contains("localhost") || url.contains("127.0.0.1")) "Local Termux" else "Emulator Host"
                onLog("Probing $label...")

                // Use localImageUrl for Termux if provided, otherwise fallback to public
                val targetImageUrl = if (label == "Local Termux" && localImageUrl != null) localImageUrl else uploadedImageUrl

                val jsonPayload = JSONObject().apply {
                    put("imageUrl", targetImageUrl)
                    put("keywordHint", keywordHint ?: "")
                }.toString()

                val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val request = Request.Builder().url(url).post(requestBody).build()

                stealthClient.newCall(request).execute().use { response ->
                    // If we got ANY response, we stop trying other URLs to avoid "double scanning"
                    success = true 
                    
                    if (response.isSuccessful) {
                        val responseData = response.body?.string() ?: ""
                        onLog("✓ DATA RECEIVED: ${responseData.length} bytes")

                        val matchesArray = try {
                            val trimmed = responseData.trim()
                            if (trimmed.startsWith("[")) {
                                org.json.JSONArray(trimmed)
                            } else if (trimmed.startsWith("{")) {
                                val json = JSONObject(trimmed)
                                if (json.has("matches")) json.getJSONArray("matches") else null
                            } else {
                                onLog("⚠ Unexpected response format: ${trimmed.take(50)}...")
                                null
                            }
                        } catch (e: Exception) {
                            onLog("✗ JSON PARSE ERROR: ${e.message}")
                            null
                        }

                        if (matchesArray != null && matchesArray.length() > 0) {
                            var validLinks = 0
                            for (i in 0 until matchesArray.length()) {
                                try {
                                    val item = matchesArray.getJSONObject(i)
                                    val link = item.optString("link")
                                    if (link.isNotBlank()) {
                                        validLinks++
                                        verifiedLeads.add(SerpVisualMatch(
                                            title = item.optString("title").ifBlank { "Visual Match" },
                                            link = link,
                                            source = item.optString("source", "Stealth Engine"),
                                            thumbnail = item.optString("thumbnail").ifBlank { null },
                                            score = 1000
                                        ))
                                    }
                                } catch (e: Exception) { /* Skip */ }
                            }
                            onLog("✓ Parsed $validLinks valid visual targets from cluster.")
                        } else if (matchesArray != null) {
                            onLog("ℹ Cluster returned 0 results for this probe.")
                        }
                    } else {
                        onLog("⚠ Cluster error: HTTP ${response.code}")
                        val errorBody = response.body?.string() ?: "No error body"
                        onLog("  -> ${errorBody.take(100)}")
                    }
                }
            } catch (e: java.io.IOException) {
                // Connection failed (Refused, Timeout, etc.) - Try next URL
                onLog("✗ Connection failed: $url")
            } catch (e: Exception) {
                onLog("✗ Unexpected error: ${e.message}")
                success = true // Stop on logic errors
            }
        }
        if (!success) onLog("✗ Cluster unreachable. Check Termux Port 3000.")

        // 2. Official Index (SerpApi) - ONLY IF Stealth fails
        if (verifiedLeads.isEmpty() && ApiClient.API_KEY.isNotBlank() && ApiClient.API_KEY != "null") {
            onLog("Engaging Official Visual Search Index (fallback)...")
            try {
                val response = apiService.searchVisual(
                    engine = "google_lens",
                    googleImageUrl = uploadedImageUrl,
                    query = keywordHint,
                    apiKey = ApiClient.API_KEY
                )
                if (response.isSuccessful) {
                    response.body()?.let { body ->
                        val matches = body.visualMatches ?: body.visualSearchResults
                        matches?.let {
                            onLog("✓ Stable Index: Found ${it.size} high-fidelity matches")
                            verifiedLeads.addAll(it.map { m -> m.copy(source = m.source ?: "Google Lens") })
                        }
                    }
                }
            } catch (e: Exception) {
                onLog("⚠ Stable Index error: ${e.message}")
            }
        }

        // Final Processing & Scoring
        verifiedLeads
            .map { match ->
                var score = match.score
                val link = match.link?.lowercase() ?: ""
                if (link.contains("linkedin.com")) score += 1500
                if (link.contains("instagram.com") || link.contains("facebook.com")) score += 800
                if (!keywordHint.isNullOrBlank() && match.title?.lowercase()?.contains(keywordHint.lowercase()) == true) {
                    score += 2000
                }
                match.copy(score = score)
            }
            .sortedByDescending { it.score }
            .distinctBy { it.link }
    }
}
