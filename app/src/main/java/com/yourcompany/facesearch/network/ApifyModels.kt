package com.yourcompany.facesearch.network

import com.google.gson.annotations.SerializedName

// The request body required by the face search actor
data class ApifyFaceInput(
    val imageUrl: String,
    // Enabled "demo" mode to test without consuming full credits (approx. 5 cents per search)
    @SerializedName("demo")
    val debugMode: Boolean = true
)

// The structure of the profile results scraped from the web
data class FaceMatchResult(
    val url: String,      // The social media or web profile link
    val score: Int,       // The actual facial similarity percentage (e.g., 92)
    val image: String?    // Matched face image URL or base64 data
)