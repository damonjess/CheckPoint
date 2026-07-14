package com.yourcompany.facesearch.network

import android.util.Log
import kotlinx.coroutines.delay
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ApifyRepository(
    private val token: String = Secrets.APIFY_API_TOKEN
) {
    private val api: ApifyApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.apify.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApifyApiService::class.java)
    }

    /**
     * Deep Scrape: Bypasses login walls by using Apify's residential proxies 
     * and headless browsers to extract data directly from the social profile.
     */
    suspend fun deepScrapeProfile(profileUrl: String, onLog: (String) -> Unit): List<String> {
        val actorId = when {
            profileUrl.contains("instagram.com") -> "apify~instagram-profile-scraper"
            profileUrl.contains("facebook.com") -> "apify~facebook-profile-scraper"
            else -> "apify~web-scraper" // Fallback
        }

        try {
            onLog("Engaging Apify Shadow Node for $actorId...")
            val response = api.runActor(actorId, token, mapOf("urls" to listOf(profileUrl)))
            
            if (response.isSuccessful) {
                val runId = response.body()?.data?.id
                val datasetId = response.body()?.data?.defaultDatasetId
                
                if (runId != null && datasetId != null) {
                    onLog("Bypass active. Extracting dataset $datasetId...")
                    
                    // Poll for completion (Max 30s for a quick profile scan)
                    repeat(6) {
                        delay(5000)
                        val itemsResponse = api.getDatasetItems(datasetId, token)
                        if (itemsResponse.isSuccessful) {
                            val items = itemsResponse.body()
                            if (!items.isNullOrEmpty()) {
                                onLog("Data harvested from profile.")
                                // Extract images/bio found directly on the page
                                return items.flatMap { it["profilePicUrl"]?.toString()?.let { listOf(it) } ?: emptyList() }
                            }
                        }
                    }
                }
            } else {
                onLog("Apify Bridge Rejected: ${response.code()}")
            }
        } catch (e: Exception) {
            onLog("Deep Scrape Failed: ${e.message}")
        }
        return emptyList()
    }
}
