package com.yourcompany.facesearch.network

class FaceSearchRepository(
    private val apiService: SerpApiService = ApiClient.serpApiService,
    private val serpApiKey: String = ApiClient.API_KEY
) {

    suspend fun performFaceSearch(
        uploadedImageUrl: String,
        keywordHint: String? = null,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> {
        val allResults = mutableListOf<SerpVisualMatch>()
        val cleanHint = keywordHint?.trim() ?: ""

        // Prioritized order for better accuracy (Bing and others temporarily disabled for speed/stability)
        val engines = listOf("google_lens", "yandex_images")

        for (currentEngine in engines) {
            try {
                val readable = currentEngine.replace("_", " ").uppercase()
                onLog("PROBING $readable...")
                android.util.Log.d("FaceSearch", "Probing $currentEngine for $cleanHint")

                if (currentEngine != engines.first()) {
                    kotlinx.coroutines.delay(1500)
                }

                val response = when (currentEngine) {
                    "google" -> {
                        if (cleanHint.isBlank()) continue
                        apiService.searchVisual(engine = "google", query = "$cleanHint profile", apiKey = serpApiKey)
                    }
                    "google_reverse_image" -> {
                        apiService.searchVisual(engine = "google_reverse_image", googleImageUrl = uploadedImageUrl, apiKey = serpApiKey)
                    }
                    else -> {
                        apiService.searchVisual(
                            engine = currentEngine,
                            imageUrl = uploadedImageUrl,
                            query = if (currentEngine == "google_lens" && cleanHint.isNotEmpty()) cleanHint else null,
                            apiKey = serpApiKey
                        )
                    }
                }
                
                onLog("HTTP Response: ${response.code()}")

                if (response.isSuccessful) {
                    val body = response.body()
                    android.util.Log.d("FaceSearch", "Response Body for $currentEngine: $body")
                    val engineResults = mutableListOf<SerpVisualMatch>()

                    // Prioritize visual_matches (Google Lens specific)
                    body?.visualMatches?.let { engineResults.addAll(it) }
                    
                    // Add secondary result fields
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
                        onLog("Found ${engineResults.size} leads from $readable")
                    } else {
                        onLog("No visual matches in $readable")
                    }
                } else {
                    val error = response.errorBody()?.string() ?: "Unknown error"
                    onLog("API ERROR on $readable: $error")
                    android.util.Log.e("FaceSearch", "API Error on $currentEngine: $error")
                }
            } catch (e: Exception) {
                onLog("COMM ERROR on $currentEngine: ${e.message}")
                android.util.Log.e("FaceSearch", "Error probing $currentEngine", e)
                e.printStackTrace()
            }
        }

        if (allResults.isEmpty()) {
            onLog("CRITICAL: Zero matches from all engines.")
            android.util.Log.e("FaceSearch", "TOTAL RESULTS: 0 (Search failed or empty payload)")
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

                // ===== FACTOR 1: KEYWORD MATCHING (2000 pts max) =====
                if (cleanHint.isNotEmpty()) {
                    val words = cleanHint.lowercase().split(" ").filter { it.length > 2 }
                    val titleWordMatches = words.count { title.contains(it) }
                    score += (titleWordMatches * 600).coerceAtMost(2000)
                }

                // ===== FACTOR 2: SOCIAL PLATFORM PRIORITY (2500 pts max) =====
                val platform = SocialMediaDetector.detectPlatform(link)
                score += platform.baseScore
                
                // Bonus for known profile-based platforms
                if (platform.isProfileBased) {
                    score += 400
                }

                // ===== FACTOR 3: URL PATTERN QUALITY (1200 pts max) =====
                score += SocialMediaDetector.scoreUrlPattern(link)

                // ===== FACTOR 4: PROFILE INDICATORS (1000 pts max) =====
                if (SocialMediaDetector.isProfileUrl(link)) {
                    score += 800
                }
                
                // Username extraction bonus (indicates real profile)
                val username = SocialMediaDetector.extractUsername(link, platform)
                if (username != null && username.length > 2 && username.length < 50) {
                    score += 200
                }

                // ===== FACTOR 5: CONTENT INDICATORS (800 pts max) =====
                // Has a thumbnail (actual profile image or content)
                if (match.thumbnail != null) {
                    score += 300
                }
                
                // Title suggests personal profile
                if (title.contains("profile") || title.contains("account") || 
                    title.contains("about") || title.contains("bio")) {
                    score += 200
                }

                // ===== FACTOR 6: FILTERING (Penalties) =====
                // Heavy penalty for generic results
                if (link.contains("search") || link.contains("explore") || 
                    link.contains("discover") || link.contains("feed")) {
                    score -= 1500
                }
                
                // Penalty for likely spammy content
                if (source?.contains("spam") == true || source?.contains("ad") == true) {
                    score -= 2000
                }

                // Base score for any match
                score += 300

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
