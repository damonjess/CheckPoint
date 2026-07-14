package com.yourcompany.facesearch.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface SerpApiService {
    @GET("search.json")
    suspend fun searchVisual(
        @Query("engine") engine: String,
        @Query("url") imageUrl: String? = null,
        @Query("image_url") googleImageUrl: String? = null,    // For google_reverse_image
        @Query("q") query: String? = null,                    // OSINT Keyword Hint
        @Query("api_key") apiKey: String
    ): Response<SerpLensResponse>
}

data class SerpLensResponse(
    @SerializedName("visual_matches")
    val visualMatches: List<SerpVisualMatch>? = null,
    @SerializedName("knowledge_graph")
    val knowledgeGraph: KnowledgeGraph? = null,
    @SerializedName("image_results")
    val imageResults: List<SerpApiMatch>? = null,
    @SerializedName("inline_images")
    val inlineImages: List<InlineImage>? = null,
    @SerializedName("visual_search_results")
    val visualSearchResults: List<SerpVisualMatch>? = null,
    @SerializedName("organic_results")
    val organicResults: List<SerpApiMatch>? = null
)

data class SerpVisualMatch(
    @SerializedName("title")
    val title: String?,
    
    @SerializedName("link")
    val link: String?, // The direct social media link (View Profile target)
    
    @SerializedName("source")
    val source: String?, // Shows "Instagram", "Facebook", "LinkedIn", etc.
    
    @SerializedName("thumbnail")
    val thumbnail: String?, // The image profile link to fix the gray circles

    val score: Int = 0
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
    val source: String?,
    val thumbnail: String?,
    val favicon: String? = null,
    @SerializedName("rich_snippet") val richSnippet: RichSnippet? = null
)

data class RichSnippet(
    @SerializedName("top") val top: TopSnippet? = null
)

data class TopSnippet(
    @SerializedName("detected_extensions") val detectedExtensions: Map<String, Any>? = null,
    @SerializedName("thumbnail") val thumbnail: String? = null
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