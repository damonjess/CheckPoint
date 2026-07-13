package com.yourcompany.facesearch.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface FaceSearchApi {
    @GET("search")
    suspend fun searchGoogle(
        @Query("engine") engine: String,
        @Query("api_key") apiKey: String,
        @Query("image_url") imageUrl: String? = null,
        @Query("url") url: String? = null
    ): Response<SerpApiResponse>
}

data class SerpApiResponse(
    @SerializedName("image_results") val imageResults: List<SerpApiMatch>?,
    @SerializedName("inline_images") val inlineImages: List<InlineImage>?,
    @SerializedName("visual_matches") val visualMatches: List<VisualMatch>?,
    @SerializedName("knowledge_graph") val knowledgeGraph: KnowledgeGraph?
)

data class VisualMatch(
    val title: String?,
    val link: String?,
    val source: String?,
    val thumbnail: String?
)

data class KnowledgeGraph(
    val title: String?,
    val subtitle: String?,
    val description: String?,
    @SerializedName("header_images") val headerImages: List<HeaderImage>?
)

data class HeaderImage(
    val image: String?
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