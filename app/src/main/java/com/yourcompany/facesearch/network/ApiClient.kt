package com.yourcompany.facesearch.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val BASE_URL = "https://serpapi.com/"
    
    // Sign up at serpapi.com to get your own free API Key (250 searches/month)
    val API_KEY = Secrets.SERP_API_KEY
    
    // Sign up at facecheck.id/Face-Search/API to get your API Token
    val FACECHECK_API_KEY = Secrets.FACECHECK_API_KEY

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    // Main SerpApi instance
    val serpApiService: SerpApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SerpApiService::class.java)
    }

    // FaceCheck.ID API instance
    val faceCheckApi: FaceCheckApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://facecheck.id/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FaceCheckApi::class.java)
    }

    val imageUploadApi: ImageUploadApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.imgbb.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ImageUploadApi::class.java)
    }
}