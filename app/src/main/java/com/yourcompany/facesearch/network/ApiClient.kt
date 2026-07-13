package com.yourcompany.facesearch.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val BASE_URL = "https://serpapi.com/"
    
    // Sign up at serpapi.com to get your own free API Key (250 searches/month)
    val API_KEY = Secrets.SERP_API_KEY

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val faceSearchApi: FaceSearchApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FaceSearchApi::class.java)
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