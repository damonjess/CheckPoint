package com.yourcompany.facesearch.network.model

import com.google.gson.annotations.SerializedName

data class FaceSearchResponse(
    val status: String?,
    val progress: Int?,
    val message: String?,
    val error: String?,
    val output: SearchOutput? // Nested data envelope where results actually live
)

data class SearchOutput(
    val items: List<WebMatch>?, // The true list array containing search items
    @SerializedName("tookSeconds") val tookSeconds: Double?
)

data class WebMatch(
    val base64: String?,    // Thumbnail data string
    val url: String?,       // Direct internet link profile reference
    val score: Int?,        // Biometric similarity score index (0-100)
    val group: Int?
)