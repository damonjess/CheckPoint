package com.yourcompany.facesearch.network

class FaceSearchRepository(
    private val apiService: SerpApiService = ApiClient.serpApiService,
    private val serpApiKey: String = ApiClient.API_KEY
) {
    suspend fun performFaceSearch(
        uploadedImageUrl: String, 
        engine: String = "google_lens",
        keywordHint: String? = null,
        sourceEmbedding: FloatArray? = null,
        embedder: ((android.graphics.Bitmap) -> FloatArray?)? = null,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> {
        val allResults = mutableListOf<SerpVisualMatch>()
        val cleanHint = keywordHint?.trim() ?: ""
        
        // --- STEP 1: ENGINE WATERFALL ---
        val engines = listOf("google_lens", "bing_visual_search", "yandex_images")
        
        for (currentEngine in engines) {
            try {
                val readableEngine = currentEngine.replace("_", " ").uppercase()
                onLog("PROBING $readableEngine...")
                
                // Rate limiting delay between engines
                if (currentEngine != engines.first()) kotlinx.coroutines.delay(1000)

                // DORKING BYPASS: For Google Lens, we inject site-specific dorks into the query 
                // to force it to surface social results that are usually hidden behind "General results"
                val query = if (currentEngine == "google_lens" && cleanHint.isNotEmpty()) {
                    if (cleanHint.contains("bypass", ignoreCase = true)) {
                        "site:facebook.com OR site:instagram.com OR site:linkedin.com \"${cleanHint.replace("bypass", "", true).trim()}\""
                    } else {
                        cleanHint
                    }
                } else null

                val response = try {
                    apiService.searchVisual(
                        engine = currentEngine,
                        imageUrl = uploadedImageUrl,
                        query = query,
                        apiKey = serpApiKey
                    )
                } catch (e: Exception) {
                    onLog("COMM ERROR: $readableEngine failure.")
                    continue
                }

                if (response.isSuccessful) {
                    val body = response.body()
                    val engineResults = mutableListOf<SerpVisualMatch>()
                    
                    body?.visualMatches?.let { engineResults.addAll(it) }
                    body?.imageResults?.map { 
                        SerpVisualMatch(title = it.title, link = it.link, source = it.source, thumbnail = it.thumbnail)
                    }?.let { engineResults.addAll(it) }
                    body?.inlineImages?.map {
                        SerpVisualMatch(title = "Visual Match", link = it.source, source = it.source, thumbnail = it.thumbnail)
                    }?.let { engineResults.addAll(it) }
                    
                    if (engineResults.isNotEmpty()) {
                        allResults.addAll(engineResults)
                        onLog("Found ${engineResults.size} leads via $readableEngine.")
                        if (allResults.size > 20) break
                    }
                }
            } catch (e: Exception) {
                onLog("Error with engine: ${e.message}")
            }
        }

        if (allResults.isEmpty()) return emptyList()

        // --- STEP 2: STRONGER SCORING FOR TARGET PHOTOS ---
        val scoredResults = allResults.map { match ->
            var score = 0
            val title = match.title?.lowercase() ?: ""
            val link = match.link?.lowercase() ?: ""

            // Heavy boost for name matches
            if (cleanHint.isNotEmpty()) {
                val lowerHint = cleanHint.lowercase()
                if (title.contains(lowerHint) || link.contains(lowerHint)) score += 3000
                
                cleanHint.split(" ").forEach { part ->
                    if (part.length > 2 && title.contains(part.lowercase())) score += 700
                }
            }

            // Big boost for social media & profile pages
            if (link.contains("facebook.com") || link.contains("instagram.com") || 
                link.contains("linkedin.com") || link.contains("twitter.com") || 
                link.contains("x.com")) {
                score += 1500
            }

            if (link.contains("profile") || link.contains("/in/") || 
                link.contains("people/") || link.contains("id=")) {
                score += 800
            }

            // Thumbnail presence bonus
            if (match.thumbnail != null) score += 500

            // Penalize noisy results
            if (title.contains("stock") || title.contains("model") || 
                title.contains("on this day") || title.contains("history")) {
                score -= 2000
            }

            match.copy(score = score)
        }
        .sortedByDescending { it.score }
        .distinctBy { it.link }

        // --- STEP 3: LOCAL BIOMETRIC VERIFICATION ---
        onLog("Verifying top targets via local optics...")
        val verifiedResults = if (sourceEmbedding != null && embedder != null) {
            verifyWithLocalEmbedding(scoredResults.take(12), sourceEmbedding, embedder)
        } else {
            scoredResults
        }
        
        return verifiedResults
            .filter { it.score >= 1200 } // Keep promising results
            .sortedByDescending { it.score }
            .take(8)
    }

    suspend fun verifyWithLocalEmbedding(
        results: List<SerpVisualMatch>,
        myReferenceEmbedding: FloatArray,
        embedder: (android.graphics.Bitmap) -> FloatArray?,
        threshold: Double = 0.65 // adjust based on your tests
    ): List<SerpVisualMatch> {
        return results.map { match ->
            if (match.thumbnail == null) return@map match
            
            val thumbnailBitmap = downloadBitmap(match.thumbnail) ?: return@map match
            val candidateEmb = embedder(thumbnailBitmap) ?: return@map match
            
            val similarity = cosineSimilarity(myReferenceEmbedding, candidateEmb)
            
            // Boost score significantly if similarity is high
            var finalScore = match.score + (similarity * 6000).toInt()
            
            // Strict Filtering: If similarity is low, penalize heavily
            if (similarity < threshold) finalScore -= 4000
            
            match.copy(score = finalScore)
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            normA += a[i].toDouble() * a[i].toDouble()
            normB += b[i].toDouble() * b[i].toDouble()
        }
        return dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
    }

    private suspend fun downloadBitmap(url: String): android.graphics.Bitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val request = okhttp3.Request.Builder().url(url).build()
            val response = okhttp3.OkHttpClient().newCall(request).execute()
            
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null) {
                    return@withContext android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
