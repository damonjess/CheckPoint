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
        val cleanHint = keywordHint?.trim() ?: "damon kirby"

        // Prioritized order for better accuracy
        val engines = listOf("google_lens", "bing_visual_search", "yandex_images")

        for (currentEngine in engines) {
            try {
                val readable = currentEngine.replace("_", " ").uppercase()
                onLog("PROBING $readable...")

                if (currentEngine != engines.first()) {
                    kotlinx.coroutines.delay(2000)
                }

                val response = apiService.searchVisual(
                    engine = currentEngine,
                    imageUrl = uploadedImageUrl,
                    query = if (currentEngine == "google_lens") cleanHint else null,
                    apiKey = serpApiKey
                )

                if (response.isSuccessful) {
                    val body = response.body()
                    val engineResults = mutableListOf<SerpVisualMatch>()

                    body?.visualMatches?.let { engineResults.addAll(it) }
                    body?.imageResults?.let { results ->
                        engineResults.addAll(results.map {
                            SerpVisualMatch(title = it.title, link = it.link, source = it.source, thumbnail = it.thumbnail)
                        })
                    }

                    if (engineResults.isNotEmpty()) {
                        allResults.addAll(engineResults)
                        onLog("Found ${engineResults.size} leads from $readable")
                    }
                }
            } catch (e: Exception) {
                onLog("COMM ERROR on $currentEngine → skipping")
            }
        }

        // Strong scoring for your personal matches
        return allResults
            .map { match ->
                var score = 0
                val title = match.title?.lowercase() ?: ""
                val link = match.link?.lowercase() ?: ""

                if (cleanHint.isNotEmpty() && title.contains(cleanHint.lowercase())) score += 4000
                if (link.contains("linkedin") || link.contains("facebook") || link.contains("instagram") || link.contains("glasgow")) score += 2500
                if (link.contains("profile") || link.contains("/in/")) score += 1200

                match.copy(score = score)
            }
            .filter { it.score >= 1000 }
            .sortedByDescending { it.score }
            .distinctBy { it.link }
    }
}