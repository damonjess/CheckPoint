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

        // Scoring for matches
        return allResults
            .map { match ->
                var score = 0
                val title = match.title?.lowercase() ?: ""
                val link = match.link?.lowercase() ?: ""

                // Hint matching is a huge boost
                if (cleanHint.isNotEmpty() && title.contains(cleanHint.lowercase())) {
                    score += 5000
                }
                
                // Social media priority
                if (link.contains("linkedin.com") || link.contains("facebook.com") || 
                    link.contains("instagram.com") || link.contains("twitter.com") || 
                    link.contains("x.com") || link.contains("github.com") ||
                    link.contains("pinterest.com") || link.contains("youtube.com")) {
                    score += 3000
                }
                
                // Profile page indicators
                if (link.contains("profile") || link.contains("/in/") || link.contains("/user/") || link.contains("people")) {
                    score += 1500
                }
                
                // Base score for any visual match found by engines
                score += 500

                match.copy(score = score)
            }
            .sortedByDescending { it.score }
            .filter { it.link != null || it.title != null || it.thumbnail != null }
            .distinctBy { it.link ?: it.title ?: it.thumbnail }
    }
}
