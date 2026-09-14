package com.yourcompany.facesearch.network

import retrofit2.http.GET
import retrofit2.http.Query
import com.yourcompany.facesearch.network.model.SerpResponse

interface SerpApiService {
    @GET("search.json")
    suspend fun googleLensSearch(
        @Query("engine") engine: String = "google_lens",
        @Query("url") url: String,
        @Query("api_key") apiKey: String
    ): SerpResponse
}
