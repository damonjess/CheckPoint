package com.yourcompany.facesearch.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {
    private const val BASE_URL = "https://serpapi.com/"
    
    // Sign up at serpapi.com to get your own free API Key (250 searches/month)
    val API_KEY = Secrets.SERP_API_KEY

    val faceSearchApi: FaceSearchApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FaceSearchApi::class.java)
    }

    val imageUploadApi: ImageUploadApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.imgbb.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ImageUploadApi::class.java)
    }
}