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

        // ===== AGGRESSIVE BYPASS WATERFALL =====
        // Multiple engines in priority order with built-in retries and fallbacks
        val engineWaterfall = listOf(
            // Primary visual search (most accurate)
            "google_lens",
            "yandex_images",
            "bing_visual_search",
            
            // Reverse image search specialists
            "google_reverse_image",
            
            // Fallback engines if primary fails
            "baidu_images"
        )

        for (currentEngine in engineWaterfall) {
            try {
                val readable = currentEngine.replace("_", " ").uppercase()
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
                if (cleanHint.isNotEmpty()) {
                    val words = cleanHint.lowercase().split(" ").filter { it.length > 2 }
                    val titleWordMatches = words.count { title.contains(it) }
                    if (titleWordMatches > 0) {
                        score += (titleWordMatches * 300).coerceAtMost(1000)
                    }
                }

                // ===== FACTOR 5: CONTENT INDICATORS (800 pts max) =====
                // Has a thumbnail (actual profile image or content)
                if (match.thumbnail != null) {
                    score += 400  // Increased from 300
                }
                
                // Title suggests personal profile
                if (title.contains("profile") || title.contains("account") || 
                    title.contains("about") || title.contains("bio")) {
                    score += 250  // Increased from 200
                }

                // ===== FACTOR 6: FILTERING (Penalties) =====
                // Heavy penalty for generic results
                if (link.contains("search") || link.contains("explore") || 
                    link.contains("discover") || link.contains("feed")) {
                    score -= 2000  // Increased penalty from -1500
                }
                
                // Penalty for likely spammy content
                if (source?.contains("spam") == true || source?.contains("ad") == true) {
                    score -= 2500  // Increased penalty from -2000
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
