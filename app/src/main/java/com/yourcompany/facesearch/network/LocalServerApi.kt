package com.yourcompany.facesearch.network

import retrofit2.http.*
import com.yourcompany.facesearch.network.model.ServerSearchRequest
import com.yourcompany.facesearch.network.model.ServerSearchResponse

interface LocalServerApi {
    @POST("api/search")
    @Headers("Content-Type: application/json")
    suspend fun searchByImage(
        @Body request: ServerSearchRequest
    ): ServerSearchResponse

    @POST("api/extract")
    @Headers("Content-Type: application/json")
    suspend fun extractMedia(
        @Body request: Map<String, String>
    ): retrofit2.Response<com.yourcompany.facesearch.network.model.ExtractionResponse>

    @POST("api/dork-search")
    @Headers("Content-Type: application/json")
    suspend fun dorkSearch(
        @Body request: com.yourcompany.facesearch.network.model.DorkSearchRequest
    ): ServerSearchResponse

    @GET("api/ping")
    suspend fun ping(): Map<String, String>
}



