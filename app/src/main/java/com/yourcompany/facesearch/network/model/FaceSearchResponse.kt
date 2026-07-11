package com.yourcompany.facesearch.network.model

import com.google.gson.annotations.SerializedName

data class FaceSearchResponse(
    @SerializedName("search_id")
    val searchId: String? = null,

    @SerializedName("status")
    val status: String? = null,

    @SerializedName("match_found")
    val matchFound: Boolean = false,

    @SerializedName("match_confidence")
    val matchConfidence: Double? = null,

    @SerializedName("results")
    val results: List<WebMatch>? = null,

    @SerializedName("message")
    val message: String? = null
)

data class WebMatch(
    @SerializedName("name")
    val name: String? = null,

    @SerializedName("confidence")
    val confidence: Double? = null,

    @SerializedName("source")
    val source: String? = null,

    @SerializedName("profile_url")
    val profileUrl: String? = null,

    @SerializedName("image_url")
    val imageUrl: String? = null
)
