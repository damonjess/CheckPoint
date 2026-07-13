package com.yourcompany.facesearch.network

class FaceSearchRepository(
    private val apiService: SerpApiService = ApiClient.serpApiService,
    private val serpApiKey: String = ApiClient.API_KEY
) {
    suspend fun performFaceSearch(uploadedImageUrl: String): List<SerpVisualMatch> {
        return try {
            val response = apiService.searchGoogleLens(
                imageUrl = uploadedImageUrl,
                apiKey = serpApiKey
            )
            if (response.isSuccessful) {
                response.body()?.visualMatches ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
