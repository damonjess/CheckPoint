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
        listOf("yandex_images", "google_lens", "bing_visual_search").forEach {
            if (!waterfall.contains(it)) waterfall.add(it)
        }

        val cleanHint = keywordHint?.trim() ?: ""
        
        for (currentEngine in waterfall) {
            try {
                val readableEngine = currentEngine.replace("_", " ").uppercase()
                onLog("PROBING $readableEngine...")
                
                // --- STEP 2: EXECUTE REQUEST ---
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
                    }
                } else {
                    onLog("Node $readableEngine busy. Redirecting...")
                }
            } catch (e: Exception) {
                val readableEngine = currentEngine.replace("_", " ").uppercase()
                onLog("COMM ERROR: $readableEngine unreachable.")
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
            val combined = "$title $link"

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
                           source.contains("instagram")
            
            if (isSocial) score += 800
            
            // 4. Specific profile patterns (Avoid generic group pages)
            if (link.contains("people/") || link.contains("profile.php") || link.contains("/in/")) score += 400
            
            // 5. Filter out historical/unrelated "On This Day" or news noise
            if (title.contains("on this day") || title.contains("history")) score -= 1500

            match.copy(score = score)
        }
        .filter { it.score > 0 }
        .sortedByDescending { it.score }
        .distinctBy { it.link }
    }
}
