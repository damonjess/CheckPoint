package com.yourcompany.facesearch.network

class FaceSearchRepository(
    private val apiService: SerpApiService = ApiClient.serpApiService,
    private val serpApiKey: String = ApiClient.API_KEY
) {
    suspend fun performFaceSearch(
        uploadedImageUrl: String, 
        engine: String = "google_lens",
        keywordHint: String? = null,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> {
        val allResults = mutableListOf<SerpVisualMatch>()
        
        // --- STEP 1: DEFINE ENGINE WATERFALL ---
        // We prioritize the engine selected by the user, then fallback to others.
        val waterfall = mutableListOf(engine)
        listOf("bing_visual_search", "google_lens", "yandex_images").forEach {
            if (!waterfall.contains(it)) waterfall.add(it)
        }

        val cleanHint = keywordHint?.trim() ?: ""
        
        for (currentEngine in waterfall) {
            try {
                val readableEngine = currentEngine.replace("_", " ").uppercase()
                onLog("PROBING $readableEngine...")
                
                // --- STEP 2: EXECUTE REQUEST ---
                // Add a small delay between engines to avoid rate limiting
                if (currentEngine != waterfall.first()) {
                    kotlinx.coroutines.delay(800)
                }

                val response = apiService.searchVisual(
                    engine = currentEngine,
                    imageUrl = uploadedImageUrl,
                    query = if (cleanHint.isNotEmpty()) cleanHint else null,
                    apiKey = serpApiKey
                )
                
                if (response.isSuccessful) {
                    val body = response.body()
                    val engineResults = mutableListOf<SerpVisualMatch>()
                    
                    body?.visualMatches?.let { engineResults.addAll(it) }
                    body?.imageResults?.map { 
                        SerpVisualMatch(title = it.title, link = it.link, source = it.source, thumbnail = it.thumbnail)
                    }?.let { engineResults.addAll(it) }
                    
                    if (engineResults.isNotEmpty()) {
                        allResults.addAll(engineResults)
                        onLog("Found ${engineResults.size} leads via $readableEngine.")
                        
                        // If we have strong social hits, we can stop the waterfall early
                        if (engineResults.count { match -> 
                            val l = match.link?.lowercase() ?: ""
                            l.contains("facebook.com") || l.contains("instagram.com") || l.contains("linkedin.com")
                        } >= 2) {
                            onLog("Multiple social markers detected. Finalizing.")
                            break
                        }
                    } else {
                        onLog("$readableEngine found no direct links.")
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: ""
                    if (errorBody.contains("Account limit reached", ignoreCase = true)) {
                        onLog("API LIMIT REACHED. Check credits.")
                    } else {
                        onLog("Node $readableEngine busy (Code: ${response.code()}).")
                    }
                }
            } catch (e: Exception) {
                val readableEngine = currentEngine.replace("_", " ").uppercase()
                onLog("COMM ERROR: $readableEngine timed out.")
            }
        }

        if (allResults.isEmpty()) return emptyList()

        // --- STEP 3: CYBER-SCORING ---
        val firstName = cleanHint.split(" ").getOrNull(0)?.lowercase() ?: ""
        val lastName = cleanHint.split(" ").getOrNull(1)?.lowercase() ?: ""
        val fullName = cleanHint.replace("facebook", "", true)
            .replace("instagram", "", true)
            .replace("linkedin", "", true).trim().lowercase()

        return allResults.map { match ->
            var score = 0
            val title = match.title?.lowercase() ?: ""
            val link = match.link?.lowercase() ?: ""
            val source = match.source?.lowercase() ?: ""

            // 1. Exact Name Matching (High Weight)
            if (fullName.length > 2 && title.contains(fullName)) score += 2000
            
            // 2. Individual Name Components
            if (firstName.isNotEmpty() && title.contains(firstName)) score += 500
            if (lastName.isNotEmpty() && title.contains(lastName)) score += 500
            
            // 3. Social Platform Verification
            val isSocial = link.contains("facebook.com") || 
                           link.contains("instagram.com") || 
                           link.contains("linkedin.com") || 
                           link.contains("twitter.com") || 
                           link.contains("x.com") ||
                           source.contains("facebook") ||
                           source.contains("instagram") ||
                           source.contains("linkedin")
            
            if (isSocial) score += 800
            
            // 4. Visual Similarities (Always score visually related hits even if name is missing)
            if (match.thumbnail != null) score += 300
            
            // 5. Specific profile patterns
            if (link.contains("people/") || link.contains("profile.php") || link.contains("/in/")) score += 400
            
            // 6. Filter out archival noise ONLY if we have a name to match against
            if (fullName.isNotEmpty()) {
                if (title.contains("on this day") || title.contains("history")) score -= 1000
            }

            match.copy(score = score)
        }
        .filter { it.score > 0 || (fullName.isEmpty() && it.link != null) }
        .sortedByDescending { it.score }
        .distinctBy { it.link }
    }
}
