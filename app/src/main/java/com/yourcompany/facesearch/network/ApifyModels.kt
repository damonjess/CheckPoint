package com.yourcompany.facesearch.network

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

// The request body required by the face search actor
data class ApifyFaceInput(
    // Force Gson to output "image_url" in snake_case to match Face Finder's schema
    @SerializedName("image_url")
    val imageUrl: String
)

// The structure of the profile results scraped from the web
data class FaceMatchResult(
    @SerializedName("url")
    val url: String, // Direct destination links (e.g., instagram.com)

    @SerializedName("score")
    val score: Int, // Confidence match percentage

    @SerializedName("image")
    val image: JsonElement? // Keeps complex nested images safe from errors
)