package com.yourcompany.facesearch.network.model

import com.google.gson.annotations.SerializedName

data class SerpResponse(
    @SerializedName("visual_matches")
    val visualMatches: List<SerpVisualMatch>? = null,

    @SerializedName("exact_matches")
    val exactMatches: List<SerpVisualMatch>? = null,

    @SerializedName("knowledge_graph")
    val knowledgeGraph: List<SerpVisualMatch>? = null,

    @SerializedName("organic_results")
    val organicResults: List<SerpVisualMatch>? = null,
    
    @SerializedName("search_metadata")
    val searchMetadata: SearchMetadata? = null,
    
    @SerializedName("error")
    val error: String? = null
)

data class SerpVisualMatch(
    @SerializedName("title")
    val title: String? = null,
    
    @SerializedName("link")
    val link: String? = null,
    
    @SerializedName("source")
    val source: String? = null,
    
    @SerializedName("thumbnail")
    val thumbnail: String? = null,

    @SerializedName("image")
    val image: String? = null,

    @SerializedName("source_icon")
    val sourceIcon: String? = null
) {
    val bestThumbnail: String?
        get() = thumbnail ?: image ?: sourceIcon
}

data class SearchMetadata(
    @SerializedName("status")
    val status: String? = null,
    
    @SerializedName("id")
    val id: String? = null
)
