package com.yourcompany.facesearch.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface SerpApiService {
    @GET("search.json")
    suspend fun searchGoogleLens(
        @Query("engine") engine: String = "google_lens",      // Configures SerpApi engine
        @Query("url") imageUrl: String,                       // The hosted image to match
        @Query("api_key") apiKey: String                      // Pulls your SERP_API_KEY
    ): Response<SerpLensResponse>
}

data class SerpLensResponse(
    @SerializedName("visual_matches")
    val visualMatches: List<SerpVisualMatch>?,
    @SerializedName("knowledge_graph")
    val knowledgeGraph: KnowledgeGraph? = null,
    @SerializedName("image_results")
    val imageResults: List<SerpApiMatch>? = null,
    @SerializedName("inline_images")
    val inlineImages: List<InlineImage>? = null
)

data class SerpVisualMatch(
    @SerializedName("title")
    val title: String?,
    
    @SerializedName("link")
    val link: String?, // The direct social media link (View Profile target)
    
    @SerializedName("source")
    val source: String?, // Shows "Instagram", "Facebook", "LinkedIn", etc.
    
    @SerializedName("thumbnail")
    val thumbnail: String? // The image profile link to fix the gray circles
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