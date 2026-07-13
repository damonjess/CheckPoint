package com.yourcompany.facesearch.network

import com.google.gson.annotations.SerializedName

// The request body required by the face search actor
data class ApifyFaceInput(
    @SerializedName("image_url")
    val imageUrl: String
)

// The structure of the profile results scraped from the web
data class FaceMatchResult(
    @SerializedName("image")
    val image: String?, // This will cleanly load into your Glide/Coil image views!

    @SerializedName("url")
    val url: String?, // This fixes the 'View Profile' buttons

    @SerializedName("score")
    val score: Int?
)