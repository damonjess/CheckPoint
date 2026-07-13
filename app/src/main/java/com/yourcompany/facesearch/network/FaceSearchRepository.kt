package com.yourcompany.facesearch.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class FaceSearchOutcome {
    data class Success(val matches: List<UnifiedSearchResult>) : FaceSearchOutcome()
    object NoMatches : FaceSearchOutcome()
    data class Error(val message: String) : FaceSearchOutcome()
}

class FaceSearchRepository(
    private val api: FaceSearchApi = ApiClient.faceSearchApi
) {

    suspend fun searchTheWebForFree(
        uploadedImageUrl: String,
        onUpdate: (progress: Float, log: String) -> Unit
    ): FaceSearchOutcome = withContext(Dispatchers.IO) {
        try {
            onUpdate(0.20f, "Initializing Sherlock Vision Deep Scan...")
            
            val unifiedList = mutableListOf<UnifiedSearchResult>()

            // Step 1: Use Google Lens Engine (Superior for Face/Entity recognition)
            onUpdate(0.40f, "Querying Google Lens neural engine...")
            val lensResponse = try {
                api.searchGoogle(
                    engine = "google_lens",
                    apiKey = ApiClient.API_KEY,
                    url = uploadedImageUrl
                )
            } catch (e: Exception) {
                null
            }

            if (lensResponse?.isSuccessful == true) {
                val body = lensResponse.body()
                val kgName = body?.knowledgeGraph?.title?.lowercase()
                
                // Add Knowledge Graph result (Official profiles/Bios)
                body?.knowledgeGraph?.let { kg ->
                    onUpdate(0.60f, "Identity confirmed: ${kg.title}")
                    unifiedList.add(
                        UnifiedSearchResult(
                            title = kg.title ?: kg.subtitle ?: "Identified Public Figure",
                            webLink = "https://www.google.com/search?q=${kg.title?.replace(" ", "+")}",
                            displayImageUrl = kg.headerImages?.firstOrNull()?.image 
                                ?: body.visualMatches?.firstOrNull()?.thumbnail 
                                ?: uploadedImageUrl
                        )
                    )
                }

                if (kgName != null) {
                    onUpdate(0.80f, "Applying identity filters to social results...")
                }

                // Add Visual Matches (Social media links, articles)
                body?.visualMatches?.take(15)?.forEach { match ->
                    val title = match.title ?: ""
                    
                    // IF we have a Knowledge Graph name, only keep matches that are relevant
                    // to that name to avoid gender/identity mismatches.
                    val isRelevant = if (kgName != null) {
                        val nameParts = kgName.split(" ").filter { it.length > 2 }
                        nameParts.any { title.lowercase().contains(it) }
                    } else {
                        true // No KG, keep all visual matches as best guess
                    }

                    if (isRelevant) {
                        unifiedList.add(
                            UnifiedSearchResult(
                                title = title,
                                webLink = match.link,
                                displayImageUrl = match.thumbnail
                            )
                        )
                    }
                }
            }

            // Step 2: Fallback to Reverse Image Search if Lens was empty
            if (unifiedList.isEmpty()) {
                onUpdate(0.70f, "Lens search restricted. Running OSINT fallback...")
                val reverseResponse = try {
                    api.searchGoogle(
                        engine = "google_reverse_image",
                        apiKey = ApiClient.API_KEY,
                        imageUrl = uploadedImageUrl
                    )
                } catch (e: Exception) {
                    null
                }

                if (reverseResponse?.isSuccessful == true) {
                    val body = reverseResponse.body()
                    body?.imageResults?.take(10)?.forEach { match ->
                        unifiedList.add(
                            UnifiedSearchResult(
                                title = match.title ?: match.source ?: "Public Data Match",
                                webLink = match.link,
                                displayImageUrl = body.inlineImages?.firstOrNull()?.thumbnail ?: uploadedImageUrl
                            )
                        )
                    }
                }
            }

            if (unifiedList.isNotEmpty()) {
                onUpdate(1.0f, "Analysis complete. Targets located.")
                // Remove duplicates by link
                val distinctResults = unifiedList.distinctBy { it.webLink }
                return@withContext FaceSearchOutcome.Success(distinctResults)
            } else {
                return@withContext FaceSearchOutcome.NoMatches
            }

        } catch (e: Exception) {
            Log.e("FaceSearch", "Search error", e)
            return@withContext FaceSearchOutcome.Error("Analysis Interrupted: ${e.localizedMessage}")
        }
    }
}
