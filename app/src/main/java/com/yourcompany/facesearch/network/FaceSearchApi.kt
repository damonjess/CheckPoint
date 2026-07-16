package com.yourcompany.facesearch.network

import com.google.gson.*
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import java.lang.reflect.Type

interface SerpApiService {
    @GET("search.json")
    suspend fun searchVisual(
        @Query("engine") engine: String,
        @Query("url") imageUrl: String? = null,
        @Query("image_url") googleImageUrl: String? = null,    // For google_reverse_image
        @Query("q") query: String? = null,                    // OSINT Keyword Hint
        @Query("api_key") apiKey: String,
        @Query("google_domain") googleDomain: String? = "google.com",
        @Query("gl") country: String? = "us",                 // Country code (e.g., us, uk, ca)
        @Query("hl") language: String? = "en"                 // Language code
    ): Response<SerpLensResponse>
}

data class SerpLensResponse(
    @SerializedName("visual_matches")
    val visualMatches: List<SerpVisualMatch>? = null,
    @JsonAdapter(KnowledgeGraphAdapter::class)
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
    @JsonAdapter(ThumbnailAdapter::class)
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
    @JsonAdapter(ThumbnailAdapter::class)
    val thumbnail: String?,
    val favicon: String? = null,
    @SerializedName("rich_snippet") val richSnippet: RichSnippet? = null
)

data class RichSnippet(
    @SerializedName("top") val top: TopSnippet? = null
)

data class TopSnippet(
    @SerializedName("detected_extensions") val detectedExtensions: Map<String, Any>? = null,
    @JsonAdapter(ThumbnailAdapter::class)
    @SerializedName("thumbnail") val thumbnail: String? = null
)

data class InlineImage(
    val source: String?,
    @JsonAdapter(ThumbnailAdapter::class)
    val thumbnail: String? // The valid live web URL path to the preview photo
)

/**
 * Handles SerpApi's inconsistent "thumbnail" field, which can be a String URL 
 * or an Object containing the URL.
 */
class ThumbnailAdapter : JsonDeserializer<String?> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): String? {
        if (json == null || json.isJsonNull) return null
        
        return when {
            json.isJsonPrimitive && json.asJsonPrimitive.isString -> json.asString
            json.isJsonObject -> {
                val obj = json.asJsonObject
                // Try common SerpApi keys for nested thumbnail URLs
                obj.get("src")?.asString 
                    ?: obj.get("link")?.asString 
                    ?: obj.get("url")?.asString
                    ?: obj.get("static")?.asString
                    ?: json.toString() // Fallback to raw stringified object if nothing found
            }
            else -> json.toString()
        }
    }
}

/**
 * Handles SerpApi's inconsistent "knowledge_graph" field, which can be an Object
 * or an empty Array [].
 */
class KnowledgeGraphAdapter : JsonDeserializer<KnowledgeGraph?> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): KnowledgeGraph? {
        if (json == null || json.isJsonNull) return null
        
        return if (json.isJsonObject) {
            context?.deserialize(json, KnowledgeGraph::class.java)
        } else if (json.isJsonArray) {
            val array = json.asJsonArray
            if (array.size() > 0 && array.get(0).isJsonObject) {
                context?.deserialize(array.get(0), KnowledgeGraph::class.java)
            } else {
                null
            }
        } else {
            null
        }
    }
}

// Clean unified UI model to keep adapter code simple
data class UnifiedSearchResult(
    val title: String?,
    val webLink: String?,
    val displayImageUrl: String?
)