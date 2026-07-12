package com.yourcompany.facesearch.network

import com.yourcompany.facesearch.network.model.FaceSearchResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface FaceSearchApi {

    /**
     * Uploads an image to start a new internet-wide face search.
     * Returns a search ID that can be used to poll for results.
     */
    @Multipart
    @POST("api/upload_pic")
    suspend fun uploadFace(
        @Header("Authorization") apiKey: String,
        @Part image: MultipartBody.Part
    ): Response<UploadResponse>

    /**
     * Retrieves results for a search ID. 
     * In a real implementation, you would poll this until status is 'completed'.
     */
    @POST("api/search")
    suspend fun getSearchResults(
        @Header("Authorization") apiKey: String,
        @Body request: SearchRequest
    ): Response<FaceSearchResponse>
}

data class UploadResponse(
    val id_search: String?,
    val message: String?,
    val error: String?
)

data class SearchRequest(
    val id_search: String,
    val status_only: Boolean = false,
    val testing_mode: Boolean = false
)
