package com.yourcompany.facesearch.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApifyApiService {
    
    // Runs an Apify Actor (e.g. Instagram Scraper, LinkedIn Scraper)
    @POST("v2/acts/{actorId}/runs")
    @JvmSuppressWildcards
    suspend fun runActor(
        @Path("actorId") actorId: String,
        @Query("token") token: String,
        @Body input: Map<String, Any>
    ): Response<ApifyRunResponse>

    // Gets the results from a dataset
    @GET("v2/datasets/{datasetId}/items")
    @JvmSuppressWildcards
    suspend fun getDatasetItems(
        @Path("datasetId") datasetId: String,
        @Query("token") token: String
    ): Response<List<Map<String, Any>>>
}

data class ApifyRunResponse(
    val data: ApifyRunData
)

data class ApifyRunData(
    val id: String,
    val defaultDatasetId: String,
    val status: String
)
