package com.yourcompany.facesearch.network.model

import com.google.gson.annotations.SerializedName

data class SerpResponse(
    @SerializedName("visual_matches")
    val visualMatches: List<SerpVisualMatch>? = null,
    
    @SerializedName("search_metadata")
    val searchMetadata: SearchMetadata? = null,
    
    @SerializedName("error")
    val error: String? = null
)

data class SerpVisualMatch(
    @SerializedName("title")
    val title: String?,
    
    @SerializedName("link")
    val link: String?,
    
    @SerializedName("source")
    val source: String?,
    
    @SerializedName("thumbnail")
    val thumbnail: String?
)

data class SearchMetadata(
    @SerializedName("status")
    val status: String?,
    
    @SerializedName("id")
    val id: String?
)
