package com.yourcompany.facesearch.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface FaceSearchApi {
    @GET("search")
    suspend fun searchGoogleReverseImage(
        @Query("engine") engine: String = "google_reverse_image",
        @Query("api_key") apiKey: String,
        @Query("image_url") imageUrl: String
    ): Response<SerpApiResponse>
}

data class SerpApiResponse(
    @SerializedName("image_results") val imageResults: List<SerpApiMatch>?,
    @SerializedName("inline_images") val inlineImages: List<InlineImage>? // Captures visual image frames
)

data class SerpApiMatch(
    val title: String?,
    val link: String?,
    val snippet: String?,
    val source: String?
)

data class InlineImage(
    val source: String?,
    val thumbnail: String? // The valid live web URL path to the preview photo
)

// Clean unified UI model to keep adapter code simple
data class UnifiedSearchResult(
    val title: String?,
    val webLink: String?,
    val displayImageUrl: String?
)