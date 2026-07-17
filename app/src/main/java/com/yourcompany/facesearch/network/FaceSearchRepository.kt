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

            val bearerToken = if (faceCheckApiKey.startsWith("Bearer ")) faceCheckApiKey else "Bearer $faceCheckApiKey"
            val uploadResponse = faceCheckApi.uploadPic(multipartBody, bearerToken)
            
            if (!uploadResponse.isSuccessful) {
                val errorMsg = uploadResponse.errorBody()?.string() ?: "Unknown error"
                onLog("✗ UPLOAD FAILED: HTTP ${uploadResponse.code()} - $errorMsg")
                return emptyList()
            }
            
            val idSearch = uploadResponse.body()?.idSearch
            if (idSearch == null) {
                onLog("✗ UPLOAD FAILED: No Search ID returned - ${uploadResponse.body()?.error ?: "Check API Key"}")
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
                        demo = false
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
                                score = (match.score ?: 0) * 100 // Scale to match SerpApi scores
                            )
                        }
                    }
                    
                    if (progress >= 100 || status.contains("completed", ignoreCase = true)) {
                        break
                    }
                } else {
                    onLog("⚠ POLLING ERROR: HTTP ${searchResponse.code()}")
                }
                
                kotlinx.coroutines.delay(2000)
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
    ): List<SerpVisualMatch> {
        val allResults = mutableListOf<SerpVisualMatch>()
        val cleanHint = keywordHint?.trim() ?: ""

        val engineWaterfall = mutableListOf(
            "google_lens",
            "bing_visual_search",
            "yandex_images",           // Added - excellent for faces
            "google_reverse_image"
        )

        for (currentEngine in engineWaterfall) {
            val readable = currentEngine.replace("_", " ").uppercase()
            try {
                onLog("PROBING $readable...")

                if (currentEngine != engineWaterfall.first()) {
                    kotlinx.coroutines.delay(1000)
                }

                val response = apiService.searchVisual(
                    engine = currentEngine,
                    imageUrl = uploadedImageUrl,
                    query = if (currentEngine.contains("lens") && cleanHint.isNotEmpty()) cleanHint else null,
                    apiKey = serpApiKey
                )

                if (response.isSuccessful) {
                    val body = response.body()
                    val engineResults = mutableListOf<SerpVisualMatch>()

                    body?.visualMatches?.let { engineResults.addAll(it) }
                    body?.visualSearchResults?.let { engineResults.addAll(it) }
                    body?.imageResults?.let { results ->
                        engineResults.addAll(results.map {
                            SerpVisualMatch(title = it.title, link = it.link, source = it.source, thumbnail = it.thumbnail)
                        })
                    }

                    if (engineResults.isNotEmpty()) {
                        allResults.addAll(engineResults)
                        onLog("✓ $readable: Found ${engineResults.size} leads")
                    }
                } else {
                    onLog("$readable: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                onLog("✗ $readable error")
            }
        }

        // ===== OSINT CROSS-CORRELATION (Inspired by Social Mapper / Social Analyzer) =====
        val correlatedResults = mutableListOf<SerpVisualMatch>()
        val detectedUsernames = mutableSetOf<String>()

        // Extract potential usernames from high-scoring visual matches
        allResults.filter { it.link != null }.forEach { match ->
            val platform = SocialMediaDetector.detectPlatform(match.link)
            // Use baseScore from detector since local score is not yet calculated
            if (platform.baseScore > 1500) {
                val username = SocialMediaDetector.extractUsername(match.link, platform)
                if (username != null && username.length > 2) {
                    detectedUsernames.add(username)
                }
            }
        }

        if (detectedUsernames.isNotEmpty()) {
            onLog("CROSS-CORRELATION: Found ${detectedUsernames.size} potential handles...")
            for (username in detectedUsernames.take(2)) { // Limit to top 2 unique usernames to save API credits
                onLog("Engaging Social-Analyzer probe for '@$username'...")
                try {
                    val response = apiService.searchVisual(
                        engine = "google",
                        query = "\"$username\" (site:instagram.com OR site:facebook.com OR site:linkedin.com OR site:twitter.com OR site:tiktok.com)",
                        apiKey = serpApiKey
                    )

                    if (response.isSuccessful) {
                        val body = response.body()
                        body?.organicResults?.let { results ->
                            onLog("✓ Cross-Correlation: Found ${results.size} additional profiles for @$username")
                            correlatedResults.addAll(results.map {
                                SerpVisualMatch(
                                    title = it.title,
                                    link = it.link,
                                    source = it.source,
                                    thumbnail = it.thumbnail ?: it.favicon,
                                    score = 3500 // High score for cross-correlated results
                                )
                            })
                        }
                    }
                } catch (e: Exception) {
                    // Ignore cross-correlation failures
                }
            }
        }
        allResults.addAll(correlatedResults)

        // ===== PLATFORM-SPECIFIC TARGETED SEARCHES =====
        if (cleanHint.isNotEmpty()) {
            onLog("Engaging target-specific social probes...")
            val platformSearches = mutableListOf(
                "\"$cleanHint\" site:instagram.com" to "instagram",
                "\"$cleanHint\" site:facebook.com" to "facebook",
                "\"$cleanHint\" site:linkedin.com" to "linkedin",
                "\"$cleanHint\" site:twitter.com" to "twitter",
                "\"$cleanHint\" site:tiktok.com" to "tiktok",
                "\"$cleanHint\" profile" to "social_profile",
                "\"$cleanHint\" bio" to "bio_match"
            )

            // Add specific OSINT Dorks for better profile discovery
            if (cleanHint.split(" ").size >= 2) {
                platformSearches.add("intitle:\"$cleanHint\" (site:facebook.com OR site:instagram.com)" to "combined_social")
                platformSearches.add("\"$cleanHint\" inurl:profile" to "profile_dork")
            }

            for ((query, platform) in platformSearches) {
                try {
                    onLog("Direct probe: $platform...")
                    kotlinx.coroutines.delay(600)

                    val response = apiService.searchVisual(
                        engine = "google",
                        query = query,
                        apiKey = serpApiKey
                    )

                    if (response.isSuccessful) {
                        val body = response.body()
                        val results = mutableListOf<SerpVisualMatch>()

                        body?.organicResults?.let { organic ->
                            results.addAll(organic.map {
                                SerpVisualMatch(
                                    title = it.title,
                                    link = it.link,
                                    source = it.source,
                                    thumbnail = it.thumbnail ?: it.favicon,
                                    score = 4000 // Boost for direct keyword match
                                )
                            })
                        }

                        if (results.isNotEmpty()) {
                            allResults.addAll(results)
                            onLog("✓ Found ${results.size} verified leads on $platform")
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

        // Stronger final scoring
        return allResults
            .map { match ->
                var score = 800 // base

                val title = match.title?.lowercase() ?: ""
                val link = match.link?.lowercase() ?: ""

                val platform = SocialMediaDetector.detectPlatform(link)
                score += platform.baseScore * 2

                if (SocialMediaDetector.isProfileUrl(link)) score += 1200

                // Name boost
                if (cleanHint.isNotEmpty() && title.contains(cleanHint)) score += 1400

                // Face thumbnail bonus
                if (match.thumbnail != null) score += 600

                // Regional penalty
                if (link.contains(".ru") || link.contains(".рф") || link.contains("vk.com")) {
                    score -= 9000
                }

                match.copy(score = score)
            }
            .sortedByDescending { it.score }
            .filter { it.score > 1500 }  // Filter weak results
            .distinctBy { it.link }
    }
}
