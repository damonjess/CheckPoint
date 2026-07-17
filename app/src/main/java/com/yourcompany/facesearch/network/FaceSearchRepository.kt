package com.yourcompany.facesearch.network

import android.graphics.Bitmap
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class FaceSearchRepository(
    private val apiService: SerpApiService = ApiClient.serpApiService,
    private val serpApiKey: String = ApiClient.API_KEY,
    private val faceCheckApi: FaceCheckApi = ApiClient.faceCheckApi,
    private val faceCheckApiKey: String = ApiClient.FACECHECK_API_KEY,
    private val apifyRepository: ApifyRepository = ApifyRepository()
) {

    suspend fun performFaceCheckSearch(
        bitmap: Bitmap,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> {
        if (faceCheckApiKey.isBlank() || faceCheckApiKey == "null") {
            onLog("⚠ FaceCheck token missing. Skipping biometric scan.")
            return emptyList()
        }
        try {
            onLog("PREPARING FACECHECK.ID UPLOAD...")
            
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val byteArray = stream.toByteArray()
            val requestBody = byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
            val multipartBody = MultipartBody.Part.createFormData("images", "face_probe.jpg", requestBody)

            val bearerToken = if (faceCheckApiKey.startsWith("Bearer ")) faceCheckApiKey else "Bearer $faceCheckApiKey"
            val uploadResponse = faceCheckApi.uploadPic(multipartBody, bearerToken)
            
            if (!uploadResponse.isSuccessful) {
                val errorMsg = uploadResponse.errorBody()?.string() ?: "Unknown error"
                if (uploadResponse.code() == 401 || errorMsg.contains("token", true)) {
                    onLog("⚠ FaceCheck: Invalid API Key or No Tokens left.")
                } else {
                    onLog("✗ UPLOAD FAILED: HTTP ${uploadResponse.code()}")
                }
                return emptyList()
            }
            
            val idSearch = uploadResponse.body()?.idSearch
            if (idSearch == null) {
                onLog("✗ UPLOAD FAILED: No Search ID returned.")
                return emptyList()
            }

            onLog("✓ UPLOAD SUCCESS. SEARCH ID: ${idSearch.take(8)}...")
            onLog("INITIATING ASYNCHRONOUS PROBE...")

            var retryCount = 0
            val maxRetries = 60 // 2 minutes
            
            while (retryCount < maxRetries) {
                val searchResponse = faceCheckApi.search(
                    FaceCheckSearchRequest(
                        idSearch = idSearch, 
                        demo = true 
                    ),
                    bearerToken
                )
                
                if (searchResponse.isSuccessful) {
                    val body = searchResponse.body()
                    val progress = body?.progress ?: 0
                    val status = body?.message ?: body?.status ?: "searching"
                    val items = body?.output?.items ?: body?.items
                    
                    onLog("PROBING... $progress% [$status]")
                    
                    if (items != null && items.isNotEmpty()) {
                        onLog("✓ PROBE COMPLETE. FOUND ${items.size} TARGETS")
                        return items.map { match ->
                            SerpVisualMatch(
                                title = match.site ?: "Social Media Profile",
                                link = match.url,
                                source = match.site ?: "Public Profile",
                                thumbnail = if (match.base64 != null) "data:image/jpeg;base64,${match.base64}" else null,
                                score = (match.score ?: 0) * 100
                            )
                        }
                    }
                    
                    if (progress >= 100 || status.contains("completed", ignoreCase = true)) {
                        break
                    }
                } else {
                    onLog("⚠ POLLING ERROR: HTTP ${searchResponse.code()}")
                }
                
                kotlinx.coroutines.delay(2000)
                retryCount++
            }
            
            onLog("⚠ PROBE FINISHED: No results found.")
            return emptyList()
        } catch (e: Exception) {
            onLog("✗ FACECHECK ERROR: ${e.message}")
            return emptyList()
        }
    }

    suspend fun performFaceSearch(
        uploadedImageUrl: String,
        keywordHint: String? = null,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> {
        val allResults = mutableListOf<SerpVisualMatch>()
        
        // NO ACTORS, NO DEMOS - Direct Scraping Only
        onLog("Engaging Sherlock Free Scraper...")
        try {
            val scraperResults = scrapeFreeIndex(uploadedImageUrl, onLog)
            allResults.addAll(scraperResults)
        } catch (e: Exception) {
            onLog("⚠ Scraper interrupted: ${e.message}")
        }

        return allResults
            .map { match ->
                var score = match.score
                val link = match.link?.lowercase() ?: ""
                val platform = SocialMediaDetector.detectPlatform(link)
                score += platform.baseScore
                
                if (keywordHint != null && match.title?.lowercase()?.contains(keywordHint.lowercase()) == true) {
                    score += 1500
                }
                
                match.copy(score = score, source = platform.name)
            }
            .sortedByDescending { it.score }
            .distinctBy { it.link }
    }

    private suspend fun scrapeFreeIndex(imageUrl: String, onLog: (String) -> Unit): List<SerpVisualMatch> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val client = okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val results = mutableListOf<SerpVisualMatch>()
        val encodedUrl = android.net.Uri.encode(imageUrl)

        // ENGINE 1: YANDEX (The King of Facial Recognition)
        try {
            onLog("Deep-Scanning Global Facial Index (Yandex)...")
            val yandexUrl = "https://yandex.com/images/search?rpt=imageview&url=$encodedUrl"
            val request = okhttp3.Request.Builder()
                .url(yandexUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""
            
            // Extract sites with matching images and their thumbnails
            // Pattern for Yandex's JSON data structure
            val siteMatchRegex = "\\{\"url\":\"(http[s]?://[^\"]+)\",\"title\":\"([^\"]+)\".*?\"thumb\":\\{\"url\":\"([^\"]+)\"".toRegex()
            siteMatchRegex.findAll(html).forEach { matchResult ->
                val link = matchResult.groups[1]?.value ?: ""
                val title = matchResult.groups[2]?.value?.replace("\\u002F", "/") ?: "Web Match"
                var thumb = matchResult.groups[3]?.value?.replace("\\u002F", "/") ?: ""
                
                if (thumb.startsWith("//")) thumb = "https:$thumb"
                else if (thumb.startsWith("/") && !thumb.startsWith("http")) thumb = "https://yandex.com$thumb"

                if (!link.contains("yandex.") && !link.contains("google.")) {
                    results.add(SerpVisualMatch(
                        title = title,
                        link = link,
                        source = SocialMediaDetector.detectPlatform(link).name,
                        thumbnail = if (thumb.isNotEmpty()) thumb else null,
                        score = 2500
                    ))
                }
            }
            onLog("✓ Global Index: Found ${results.size} visual leads")
        } catch (e: Exception) {
            onLog("⚠ Global Index probe failed")
        }

        // ENGINE 2: BING (Social Profile Aggregator)
        try {
            onLog("Scanning Social Platform Index (Bing)...")
            val bingUrl = "https://www.bing.com/images/search?view=detailv2&iss=sbi&FORM=IRSBIQ&q=imgurl:$encodedUrl"
            val request = okhttp3.Request.Builder()
                .url(bingUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""
            
            // Extract visual leads with source icons/urls
            val bingLeadRegex = "mediaurl=(http[s]?%3a%2f%2f[^\"]+?)&.*?expurl=(http[s]?%3a%2f%2f[^\"]+?)&".toRegex()
            var bingCount = 0
            bingLeadRegex.findAll(html).forEach { matchResult ->
                val thumb = android.net.Uri.decode(matchResult.groups[1]?.value ?: "")
                val link = android.net.Uri.decode(matchResult.groups[2]?.value ?: "")
                
                if (link.isNotEmpty() && results.none { it.link == link }) {
                    val platform = SocialMediaDetector.detectPlatform(link)
                    results.add(SerpVisualMatch(
                        title = if (platform.isProfileBased) "${platform.name} Profile" else "Social Lead",
                        link = link,
                        source = platform.name,
                        thumbnail = if (thumb.isNotEmpty()) thumb else null,
                        score = 2200
                    ))
                    bingCount++
                }
            }
            onLog("✓ Social Index: Found $bingCount profile leads")
        } catch (e: Exception) {
            onLog("⚠ Social Index probe failed")
        }

        // ENGINE 3: GOOGLE (OSINT Organic Search)
        try {
            onLog("Finalizing Organic Search (Google)...")
            val googleUrl = "https://www.google.com/searchbyimage?image_url=$encodedUrl&safe=off"
            val request = okhttp3.Request.Builder()
                .url(googleUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""
            
            val organicPattern = "<a href=\"(https?://[^\"]+)\"[^>]*><h3[^>]*>(.*?)</h3>".toRegex(RegexOption.DOT_MATCHES_ALL)
            organicPattern.findAll(html).forEach { matchResult ->
                val link = matchResult.groups[1]?.value ?: ""
                val title = matchResult.groups[2]?.value?.replace("<[^>]*>".toRegex(), "")?.trim() ?: "Organic Match"
                
                if (!link.contains("google.com") && results.none { it.link == link }) {
                    results.add(SerpVisualMatch(
                        title = title,
                        link = link,
                        source = SocialMediaDetector.detectPlatform(link).name,
                        thumbnail = null,
                        score = 1800
                    ))
                }
            }
        } catch (e: Exception) {}

        results.distinctBy { it.link }
    }
}
