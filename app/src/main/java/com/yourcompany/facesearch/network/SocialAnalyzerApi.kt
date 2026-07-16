package com.yourcompany.facesearch.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Interface for the Social-Analyzer API (Self-Hosted).
 * Reference: https://github.com/qeeqbox/social-analyzer
 */
interface SocialAnalyzerApi {

    @POST("search")
    suspend fun searchUsername(
        @Body request: SocialAnalyzerRequest
    ): Response<SocialAnalyzerResponse>
}

data class SocialAnalyzerRequest(
    @SerializedName("username") val username: String,
    @SerializedName("top") val top: Int = 100,
    @SerializedName("mode") val mode: String = "fast", // "fast", "slow", or "normal"
    @SerializedName("option") val option: String = "analyze"
)

data class SocialAnalyzerResponse(
    @SerializedName("results") val results: List<SocialAnalyzerResult>?
)

data class SocialAnalyzerResult(
    @SerializedName("text") val platform: String?,
    @SerializedName("link") val profileUrl: String?,
    @SerializedName("status") val status: String? // e.g. "found", "not found"
)
