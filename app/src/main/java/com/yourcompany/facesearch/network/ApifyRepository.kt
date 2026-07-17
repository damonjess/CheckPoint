package com.yourcompany.facesearch.network

import android.util.Log
import kotlinx.coroutines.delay
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ApifyRepository(
    private val token: String = Secrets.APIFY_API_TOKEN
) {
    fun hasToken(): Boolean = token.isNotBlank() && token != "null"

    private val api: ApifyApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.apify.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApifyApiService::class.java)
    }

    /**
     * Reverse Image Search: Uses Yandex/Google Lens scrapers on Apify 
     * to find visual matches for a hosted image URL.
     */
    suspend fun performReverseImageSearch(imageUrl: String, onLog: (String) -> Unit): List<SerpVisualMatch> {
        // Try multiple actors if one fails
        val actors = listOf(
            "johnvc~yandex-reverse-image-search",
            "getascraper~yandex-reverse-image-search"
        )
        
        if (token.isBlank()) {
            onLog("⚠ APIFY_API_TOKEN is missing. Skipping deep scan.")
            return emptyList()
        }

        for (actorId in actors) {
            try {
                onLog("Engaging Deep Scan ($actorId)...")
                
                // Flexible input to satisfy different Yandex scrapers
                val input = mapOf(
                    "imageUrl" to imageUrl,
                    "startUrls" to listOf(mapOf("url" to imageUrl)),
                    "maxItems" to 20
                )
                
                val response = api.runActor(actorId, token, input)
                
                if (response.isSuccessful) {
                    val datasetId = response.body()?.data?.defaultDatasetId
                    if (datasetId != null) {
                        onLog("Deep Scan running (Dataset: ${datasetId.take(6)}...)")
                        
                        // Poll for completion
                        repeat(12) {
                            delay(4000)
                            val itemsResponse = api.getDatasetItems(datasetId, token)
                            if (itemsResponse.isSuccessful) {
                                val items = itemsResponse.body()
                                if (!items.isNullOrEmpty()) {
                                    onLog("✓ Found ${items.size} matches via $actorId")
                                    return items.mapNotNull { item ->
                                        val title = item["title"]?.toString() ?: item["site"]?.toString() ?: "Web Match"
                                        val link = item["url"]?.toString() ?: item["link"]?.toString() ?: return@mapNotNull null
                                        val thumbnail = item["thumbnail"]?.toString() ?: item["imageUrl"]?.toString() ?: item["img"]?.toString()
                                        
                                        SerpVisualMatch(
                                            title = title,
                                            link = link,
                                            source = item["source"]?.toString() ?: "Public Index",
                                            thumbnail = thumbnail,
                                            score = 2200
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    onLog("⚠ Actor $actorId rejected request: ${response.code()}")
                }
            } catch (e: Exception) {
                onLog("⚠ Actor $actorId error: ${e.message}")
            }
        }
        return emptyList()
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
                                // Extract multiple images (Profile pic + recent posts)
                                val images = mutableListOf<String>()
                                items.forEach { item ->
                                    item["profilePicUrl"]?.toString()?.let { images.add(it) }
                                    item["displayUrl"]?.toString()?.let { images.add(it) } // Instagram post
                                    
                                    // Handle nested list of images in some scrapers
                                    (item["latestPosts"] as? List<Map<String, Any>>)?.forEach { post ->
                                        post["displayUrl"]?.toString()?.let { images.add(it) }
                                    }
                                }
                                return images.distinct()
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
