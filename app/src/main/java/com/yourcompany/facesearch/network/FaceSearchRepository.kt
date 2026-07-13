package com.yourcompany.facesearch.network

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
            onUpdate(0.25f, "Connecting to global search index...")
            
            val response = api.searchGoogleReverseImage(
                apiKey = ApiClient.API_KEY,
                imageUrl = uploadedImageUrl
            )

            onUpdate(0.75f, "Compiling visual text and imagery data...")

            if (response.isSuccessful) {
                val body = response.body()
                val textResults = body?.imageResults
                val visualImages = body?.inlineImages

                if (!textResults.isNullOrEmpty()) {
                    val unifiedList = mutableListOf<UnifiedSearchResult>()
                    
                    // Match up textual reference info with image thumbnails positionally
                    for (i in textResults.indices) {
                        val textItem = textResults[i]
                        // Fallback safely if there are fewer image boxes than text rows
                        val imageThumbnail = visualImages?.getOrNull(i)?.thumbnail 

                        unifiedList.add(
                            UnifiedSearchResult(
                                title = textItem.title ?: textItem.source ?: "Web Match Found",
                                webLink = textItem.link,
                                displayImageUrl = imageThumbnail
                            )
                        )
                    }

                    onUpdate(1.0f, "Matches located!")
                    return@withContext FaceSearchOutcome.Success(unifiedList)
                } else {
                    return@withContext FaceSearchOutcome.NoMatches
                }
            } else {
                return@withContext FaceSearchOutcome.Error("Search rejected: ${response.code()}")
            }

        } catch (e: Exception) {
            return@withContext FaceSearchOutcome.Error(e.localizedMessage ?: "Unknown Error")
        }
    }
}