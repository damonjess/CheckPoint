package com.yourcompany.facesearch.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var cachedApi: LocalServerApi? = null
    private var cachedBaseUrl: String? = null

    /**
     * Creates or returns a cached Retrofit instance for the given base URL.
     * Call this with the discovered Termux address.
     */
    fun getInstance(baseUrl: String): LocalServerApi {
        // Normalize trailing slash
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        if (cachedApi != null && cachedBaseUrl == normalized) {
            return cachedApi!!
        }

        val instance = Retrofit.Builder()
            .baseUrl(normalized)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LocalServerApi::class.java)

        cachedApi = instance
        cachedBaseUrl = normalized
        return instance
    }

    /** Legacy fallback for code that doesn't know the backend yet. */
    val instance: LocalServerApi by lazy {
        getInstance("http://127.0.0.1:3000")
    }
}
