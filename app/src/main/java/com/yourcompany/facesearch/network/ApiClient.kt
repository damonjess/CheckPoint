package com.yourcompany.facesearch.network

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
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

    // Optional: Self-hosted Social-Analyzer instance
    // Update the base URL to your server's IP/domain (default port is 9005)
    val socialAnalyzerApi: SocialAnalyzerApi by lazy {
        Retrofit.Builder()
            .baseUrl("http://localhost:9005/") 
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SocialAnalyzerApi::class.java)
    }

    private var _imageLoader: ImageLoader? = null
    
    /**
     * Provides a shared, cached ImageLoader for biometric thumbnail verification.
     */
    fun getImageLoader(context: Context): ImageLoader {
        return _imageLoader ?: synchronized(this) {
            _imageLoader ?: ImageLoader.Builder(context)
                .components {
                    add(OkHttpNetworkFetcherFactory(okHttpClient))
                }
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, 0.25)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("biometric_thumbnails"))
                        .maxSizePercent(0.05)
                        .build()
                }
                .build().also { _imageLoader = it }
        }
    }
}
