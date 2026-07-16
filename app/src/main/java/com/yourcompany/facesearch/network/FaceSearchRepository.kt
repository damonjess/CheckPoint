package com.yourcompany.facesearch.network

import android.graphics.Bitmap
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class FaceSearchRepository(
    private val apiService: SerpApiService = ApiClient.serpApiService,
    private val serpApiKey: String = ApiClient.API_KEY,
    private val faceCheckApi: FaceCheckApi = ApiClient.faceCheckApi,
    private val faceCheckApiKey: String = ApiClient.FACECHECK_API_KEY
) {

    suspend fun performFaceCheckSearch(
        bitmap: Bitmap,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> {
        try {
            onLog("PREPARING FACECHECK.ID UPLOAD...")
            
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val byteArray = stream.toByteArray()
            val requestBody = byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
            val multipartBody = MultipartBody.Part.createFormData("images", "face_probe.jpg", requestBody)

            val uploadResponse = faceCheckApi.uploadPic(multipartBody, faceCheckApiKey)
            
            if (!uploadResponse.isSuccessful) {
                onLog("✗ UPLOAD FAILED: HTTP ${uploadResponse.code()}")
                return emptyList()
            }
            
            val idSearch = uploadResponse.body()?.idSearch
            if (idSearch == null) {
                onLog("✗ UPLOAD FAILED: No Search ID returned")
                return emptyList()
            }

            onLog("✓ UPLOAD SUCCESS. SEARCH ID: ${idSearch.take(8)}...")
            onLog("INITIATING ASYNCHRONOUS PROBE...")

            var retryCount = 0
            val maxRetries = 30 // Approx 1 minute with 2s delay
            
            while (retryCount < maxRetries) {
                val searchResponse = faceCheckApi.search(
                    FaceCheckSearchRequest(
                        idSearch = idSearch, 
                        demo = false // Using live mode
                    ),
                    faceCheckApiKey
                )
                
                if (searchResponse.isSuccessful) {
                    val body = searchResponse.body()
                    val progress = body?.progress ?: 0
                    val status = body?.status ?: "searching"
                    
                    onLog("PROBING... $progress% [$status]")
                    
                    if (body?.items != null && body.items.isNotEmpty()) {
                        onLog("✓ PROBE COMPLETE. FOUND ${body.items.size} TARGETS")
                        return body.items.map { match ->
                            SerpVisualMatch(
                                title = match.site ?: "Social Media Profile",
                                link = match.url,
                                source = match.site ?: "Public Profile",
                                thumbnail = if (match.base64 != null) "data:image/jpeg;base64,${match.base64}" else null,
                                score = (match.score ?: 0) * 100 // Scale to match SerpApi scores
                            )
                        }
                    }
                    
                    if (status.contains("done", ignoreCase = true) || progress >= 100) {
                        break
                    }
                } else {
                    onLog("⚠ POLLING ERROR: HTTP ${searchResponse.code()}")
                }
                
                kotlinx.coroutines.delay(2000)
                retryCount++
            }
            
            onLog("⚠ PROBE TIMEOUT: No results found in the allotted time.")
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
    ): List<SerpVisualMatch> {
        val allResults = mutableListOf<SerpVisualMatch>()
        val cleanHint = keywordHint?.trim() ?: ""

        // ===== STRICT GLOBAL WATERFALL =====
        // Completely removed Yandex and Baidu to eliminate Russian/Chinese bias
        val engineWaterfall = listOf(
            "google_lens",
            "bing_visual_search",
            "google_reverse_image"
        )

        for (currentEngine in engineWaterfall) {
            val readable = currentEngine.replace("_", " ").uppercase()
            try {
                onLog("PROBING $readable...")

                // Rate limiting between engines
                if (currentEngine != engineWaterfall.first()) {
                    kotlinx.coroutines.delay(800)
                }

                val response = try {
                    apiService.searchVisual(
                        engine = currentEngine,
                        imageUrl = uploadedImageUrl,
                        query = if (currentEngine == "google_lens" && cleanHint.isNotEmpty()) cleanHint else null,
                        apiKey = serpApiKey
                    )
                } catch (e: java.net.SocketTimeoutException) {
                    onLog("⚠ $readable timeout - retrying...")
                    kotlinx.coroutines.delay(1000)
                    try {
                        apiService.searchVisual(
                            engine = currentEngine,
                            imageUrl = uploadedImageUrl,
                            query = null,
                            apiKey = serpApiKey
                        )
                    } catch (e: Exception) {
                        null
                    }
                } catch (e: Exception) {
                    onLog("⚠ $readable failed - skipping...")
                    null
                }

                if (response == null) {
                    onLog("$readable: No response - continuing...")
                    continue
                }

                if (response.isSuccessful) {
                    val body = response.body()
                    val engineResults = mutableListOf<SerpVisualMatch>()

                    // Collect ALL result types
                    body?.visualMatches?.let { engineResults.addAll(it) }
                    body?.visualSearchResults?.let { engineResults.addAll(it) }
                    body?.imageResults?.let { results ->
                        engineResults.addAll(results.map {
                            SerpVisualMatch(title = it.title, link = it.link, source = it.source, thumbnail = it.thumbnail)
                        })
                    }
                    body?.organicResults?.let { results ->
                        engineResults.addAll(results.map {
                            SerpVisualMatch(
                                title = it.title, 
                                link = it.link, 
                                source = it.source, 
                                thumbnail = it.thumbnail ?: it.richSnippet?.top?.thumbnail ?: it.favicon
                            )
                        })
                    }
                    body?.inlineImages?.let { results ->
                        engineResults.addAll(results.map {
                            SerpVisualMatch(title = null, link = null, source = it.source, thumbnail = it.thumbnail)
                        })
                    }

                    if (engineResults.isNotEmpty()) {
                        allResults.addAll(engineResults)
                        onLog("✓ $readable: Found ${engineResults.size} leads")
                        
                        // If we found good results, continue probing other engines too
                        if (allResults.size >= 8) {
                            onLog("Threshold reached - continuing secondary scans...")
                        }
                    } else {
                        onLog("$readable: No results")
                    }
                } else {
                    val errorCode = response.code()
                    when (errorCode) {
                        429 -> onLog("$readable: Rate limited - backoff...")
                        else -> onLog("$readable: HTTP $errorCode")
                    }
                }
            } catch (e: Exception) {
                onLog("✗ $readable error: ${e.javaClass.simpleName}")
            }
        }

        // ===== PLATFORM-SPECIFIC TARGETED SEARCHES =====
        if (cleanHint.isNotEmpty() && allResults.size < 10) {
            onLog("Engaging platform-specific deep scans...")
            val platformSearches = listOf(
                "instagram.com $cleanHint profile" to "instagram",
                "tiktok.com @$cleanHint" to "tiktok",
                "linkedin.com/in/$cleanHint" to "linkedin",
                "facebook.com $cleanHint" to "facebook",
                "twitter.com $cleanHint" to "twitter",
                "snapchat.com $cleanHint" to "snapchat",
                "reddit.com/u/$cleanHint" to "reddit",
                "youtube.com $cleanHint channel" to "youtube"
            )
            
            for ((query, platform) in platformSearches) {
                try {
                    onLog("Scanning $platform...")
                    kotlinx.coroutines.delay(500)
                    
                    val response = apiService.searchVisual(
                        engine = "google_lens",
                        imageUrl = uploadedImageUrl,
                        query = query,
                        apiKey = serpApiKey
                    )
                    
                    if (response.isSuccessful) {
                        val body = response.body()
                        val results = mutableListOf<SerpVisualMatch>()
                        
                        body?.visualMatches?.let { results.addAll(it) }
                        body?.imageResults?.let { results.addAll(it.map { SerpVisualMatch(title = it.title, link = it.link, source = it.source, thumbnail = it.thumbnail) }) }
                        
                        if (results.isNotEmpty()) {
                            allResults.addAll(results)
                            onLog("✓ Found ${results.size} on $platform")
                        }
                    }
                } catch (e: Exception) {
                    // Skip failed platform searches
                }
            }
        }

        // ===== BYPASS TECHNIQUES =====
        if (allResults.size < 5) {
            onLog("Low results - engaging bypass protocols...")
            try {
                // Retry primary engine without keyword hint (bypass content filters)
                onLog("Bypass #1: Raw image re-scan...")
                val bypassResponse = apiService.searchVisual(
                    engine = "yandex_images",
                    imageUrl = uploadedImageUrl,
                    query = null,
                    apiKey = serpApiKey
                )
                
                if (bypassResponse.isSuccessful) {
                    bypassResponse.body()?.visualMatches?.let { allResults.addAll(it) }
                    bypassResponse.body()?.imageResults?.let { 
                        allResults.addAll(it.map { SerpVisualMatch(title = it.title, link = it.link, source = it.source, thumbnail = it.thumbnail) })
                    }
                }
            } catch (e: Exception) {
                // Continue if bypass fails
            }
        }

        if (allResults.isEmpty()) {
            onLog("⚠ No profiles found - verify image quality or try different search mode")
        } else {
            android.util.Log.d("FaceSearch", "TOTAL RESULTS: ${allResults.size} raw matches found.")
        }

        // ===== IMPROVED MULTI-FACTOR SCORING SYSTEM =====
        return allResults
            .map { match ->
                var score = 0
                val title = match.title?.lowercase() ?: ""
                val link = match.link?.lowercase() ?: ""
                val source = match.source?.lowercase() ?: ""

                // ===== FACTOR 1: SOCIAL PLATFORM PRIORITY (PRIMARY - 2500 pts max) =====
                // Platform detection is most important for finding actual profiles
                val platform = SocialMediaDetector.detectPlatform(link)
                score += platform.baseScore
                
                // Bonus for known profile-based platforms
                if (platform.isProfileBased) {
                    score += 600  // Increased from 400
                }

                // ===== FACTOR 2: PROFILE INDICATORS (1000 pts max) =====
                if (SocialMediaDetector.isProfileUrl(link)) {
                    score += 900  // Increased from 800
                }
                
                // Username extraction bonus (indicates real profile)
                val username = SocialMediaDetector.extractUsername(link, platform)
                if (username != null && username.length > 2 && username.length < 50) {
                    score += 300  // Increased from 200
                }

                // ===== FACTOR 3: URL PATTERN QUALITY (1200 pts max) =====
                score += SocialMediaDetector.scoreUrlPattern(link)

                // ===== FACTOR 4: KEYWORD MATCHING (Secondary - 1000 pts max) =====
                // Only 1000 pts because profiles often don't have names visible
                score += SocialMediaDetector.scoreNameMatch(cleanHint, title, link)

                // ===== FACTOR 5: CONTENT INDICATORS (800 pts max) =====
                // Has a thumbnail (actual profile image or content)
                if (match.thumbnail != null) {
                    score += 400  // Increased from 300
                }
                
                // Title suggests personal profile
                if (title.contains("profile") || title.contains("account") || 
                    title.contains("about") || title.contains("bio")) {
                    score += 400
                }

                // ===== FACTOR 6: FILTERING (Penalties) =====
                // Negative Keywords (False Positives)
                val negativeKeywords = listOf("meme", "joke", "funny", "skibidi", "toilet", "parody", "fan", "club")
                if (negativeKeywords.any { title.contains(it) }) {
                    score -= 3000
                }

                // Heavy penalty for generic results
                if (link.contains("search") || link.contains("explore") || 
                    link.contains("discover") || link.contains("feed") || link.contains("tag")) {
                    score -= 2500
                }
                
                // Penalty for likely spammy content
                if (source?.contains("spam") == true || source?.contains("ad") == true) {
                    score -= 2500  // Increased penalty from -2000
                }

                // ===== FACTOR 7: REGIONAL FILTERING (HARD BLOCK) =====
                // Total exclusion of results that skew away from target Western regions
                val isRussian = link.contains(".ru") || link.contains(".рф") || 
                                source.contains("yandex") || source.contains("vkontakte") || 
                                source.contains("ok.ru") || source.contains("специалисты.рф")
                
                if (isRussian) {
                    score -= 10000 // Total exclusion penalty
                }
                
                if (link.contains(".cn") || source.contains("baidu")) {
                    score -= 5000 // Heavy penalty for Chinese results
                }

                // Base score for any match
                score += 500  // Increased from 300

                match.copy(score = score)
            }
            .sortedByDescending { it.score }
            .filter { it.link != null && !it.link.lowercase().contains("search") }
            .distinctBy { 
                // Deduplicate by link, or by username if available
                SocialMediaDetector.extractUsername(it.link, SocialMediaDetector.detectPlatform(it.link)) 
                    ?: (it.link?.substringAfter("://")?.substringBefore("/") ?: it.title)
            }
    }
}
