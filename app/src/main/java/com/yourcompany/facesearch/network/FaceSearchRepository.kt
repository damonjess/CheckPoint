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
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Replace with your actual deployed backend URL 
    // Use http://10.0.2.2:3000/api/search for Emulator
    // Use http://127.0.0.1:3000/api/search if using 'adb reverse tcp:3000 tcp:3000'
    private val BACKEND_URL = "http://127.0.0.1:3000/api/search"

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
            val maxRetries = 60 // 2 minutes
            
            while (retryCount < maxRetries) {
                val searchResponse = faceCheckApi.search(
                    FaceCheckSearchRequest(
                        idSearch = idSearch, 
                        demo = true 
                    ),
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
                    
                    if (progress >= 100 || status.contains("completed", ignoreCase = true)) {
                        break
                    }
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
        keywordHint: String? = null,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        val verifiedLeads = mutableListOf<SerpVisualMatch>()
        
        // 1. STABLE API ROUTE (SerpApi) - Recommended for Production
        if (serpApiKey.isNotBlank() && serpApiKey != "null") {
            onLog("Engaging Official Visual Search Index (SerpApi)...")
            try {
                val response = apiService.searchVisual(
                    engine = "google_lens",
                    googleImageUrl = uploadedImageUrl,
                    query = keywordHint,
                    apiKey = serpApiKey
                )
                
                if (response.isSuccessful) {
                    val apiMatches = response.body()?.visualMatches ?: response.body()?.visualSearchResults
                    apiMatches?.let { matches ->
                        onLog("✓ Stable Index: Found ${matches.size} high-fidelity matches")
                        verifiedLeads.addAll(matches.map { it.copy(source = it.source ?: "Google Lens") })
                    }
                } else {
                    onLog("⚠ Stable Index rejection: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                onLog("⚠ Stable Index error: ${e.message}")
            }
        }

        // 2. STEALTH AUTOMATION ROUTE (Node.js Scraper) - Fallback/Free Alternative
        if (verifiedLeads.isEmpty()) {
            onLog("Connecting to Stealth Automation Cluster...")
            try {
                val jsonPayload = JSONObject().apply {
                    put("imageUrl", uploadedImageUrl)
                    put("keywordHint", keywordHint ?: "")
                }.toString()

                val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val request = Request.Builder().url(BACKEND_URL).post(requestBody).build()

                onLog("Executing deep scraper via anti-detection context...")
                stealthClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseData = response.body?.string() ?: ""
                        if (responseData.isBlank()) {
                            onLog("⚠ Engine returned empty stream.")
                            return@use
                        }
                        
                        // Robust parsing: Handle both {success:true, matches:[]} and direct [...] array responses
                        val matchesArray = try {
                            if (responseData.trim().startsWith("[")) {
                                org.json.JSONArray(responseData)
                            } else {
                                val jsonResponse = JSONObject(responseData)
                                if (jsonResponse.has("matches")) {
                                    jsonResponse.getJSONArray("matches")
                                } else if (jsonResponse.optBoolean("success", false)) {
                                    org.json.JSONArray() // Success but no matches field?
                                } else {
                                    onLog("⚠ Engine reported failure: ${jsonResponse.optString("error", "Unknown")}")
                                    null
                                }
                            }
                        } catch (e: Exception) {
                            onLog("⚠ Parse error: ${e.message?.take(50)}...")
                            null
                        }

                        if (matchesArray != null) {
                            onLog("Crawl complete. Analyzing ${matchesArray.length()} potential visual hits...")
                            for (i in 0 until matchesArray.length()) {
                                val item = matchesArray.getJSONObject(i)
                                val title = item.optString("title").ifBlank { "Visual Match Profile" }
                                val link = item.optString("link")
                                if (link.isNotBlank()) {
                                    verifiedLeads.add(SerpVisualMatch(
                                        title = title,
                                        link = link,
                                        source = item.optString("source", "Stealth Engine"),
                                        thumbnail = item.optString("thumbnail").ifBlank { null },
                                        score = 1000
                                    ))
                                }
                            }
                        }
                    } else {
                        onLog("⚠ Stealth engine rejection: HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                onLog("⚠ Stealth connection error: ${e.localizedMessage}")
            }
        }

        // SCORING & DEDUPING
        return@withContext verifiedLeads
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
