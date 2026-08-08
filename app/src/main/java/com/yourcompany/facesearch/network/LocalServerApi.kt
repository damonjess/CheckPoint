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
}
