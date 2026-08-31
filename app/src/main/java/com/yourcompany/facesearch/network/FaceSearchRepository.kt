package com.yourcompany.facesearch.network

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import com.yourcompany.facesearch.network.model.ServerSearchRequest
import com.yourcompany.facesearch.network.model.ServerSearchResponse
import com.yourcompany.facesearch.vision.NativeFaceCropper
import java.util.concurrent.TimeUnit

class FaceSearchRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val fastClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val freeHost = FreeImageHost()
    var activeBackend: String? = null
        private set

    private val searchMutex = Mutex()

    suspend fun performFaceSearch(
        bitmap: Bitmap,
        faceBitmap: Bitmap? = null,
        keywordHint: String? = null,
        imageUrl: String? = null,
        sceneUrl: String? = null,
        @Suppress("UNUSED_PARAMETER") deepCrawl: Boolean = false,
        @Suppress("UNUSED_PARAMETER") searchMode: String = "PRECISION",
        @Suppress("UNUSED_PARAMETER") includeExactLensMatches: Boolean = false,
        @Suppress("UNUSED_PARAMETER") skipVisualEngines: Boolean = false,
        enginesToSkip: Set<String> = emptySet(),
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = searchMutex.withLock {
        withContext(Dispatchers.IO) {
            val allResults = java.util.Collections.synchronizedList(mutableListOf<SerpVisualMatch>())

            // 1. FORCE TIGHT FACE CROP UPLOAD
            // We intercept the image here to ensure clothing/background is NEVER sent to Google Lens
            val faceToUpload = faceBitmap ?: NativeFaceCropper().getTightFaceCrop(bitmap) ?: bitmap
            
            val probeUrl = if (imageUrl != null && imageUrl.startsWith("http")) {
                imageUrl
            } else {
                onLog("Uploading strict face probe to prevent clothing matches...")
                freeHost.upload(faceToUpload, onLog)
            }

            if (probeUrl == null) {
                onLog("✗ Image upload failed. Cannot perform visual search.")
                return@withContext emptyList()
            }

            // 2. Termux Execution
            if (isLocalBackendAvailable()) {
                onLog("Querying Termux scraper backend...")
                val termuxResponse = performLocalServerSearch(
                    bitmap = bitmap,
                    faceBitmap = faceToUpload,
                    keywordHint = keywordHint,
                    imageUrl = probeUrl,
                    sceneUrl = probeUrl,
                    searchMode = searchMode,
                    onLog = onLog
                )

                termuxResponse.matches?.forEach { match ->
                    allResults.add(
                        SerpVisualMatch(
                            title = match.title,
                            link = match.link,
                            source = match.source,
                            thumbnail = ThumbnailUtils.normalize(match.thumbnail),
                            score = match.score
                        )
                    )
                }
            }

            // 3. In-App Scraper Fallback
            if (allResults.size < 5) {
                onLog("Running in-app visual engine fallback...")
                coroutineScope {
                    val jobs = mutableListOf<Deferred<Unit>>()

                    if ("TinEye" !in enginesToSkip) {
                        jobs.add(async {
                            val s = WebViewScraper.create(context)
                            try {
                                val matches = s.scrapeTinEye(probeUrl)
                                onLog("TinEye found ${matches.size} candidate(s)")
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }

                    if ("Google" !in enginesToSkip) {
                        jobs.add(async {
                            val s = WebViewScraper.create(context)
                            try {
                                val matches = s.scrapeGoogle(probeUrl)
                                onLog("Google found ${matches.size} candidate(s)")
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }

                    if ("Bing" !in enginesToSkip) {
                        jobs.add(async {
                            val s = WebViewScraper.create(context)
                            try {
                                val matches = s.scrapeBing(probeUrl)
                                onLog("Bing found ${matches.size} candidate(s)")
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }

                    if ("Yandex" !in enginesToSkip) {
                        jobs.add(async {
                            val s = WebViewScraper.create(context)
                            try {
                                val matches = s.scrapeYandex(probeUrl)
                                onLog("Yandex found ${matches.size} candidate(s)")
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }

                    if (com.yourcompany.facesearch.BuildConfig.SERP_API_KEY.isNotBlank()) {
                        jobs.add(async {
                            val matches = performSerpApiSearch(probeUrl, includeExactLensMatches, onLog)
                            allResults.addAll(matches)
                            Unit
                        })
                    }

                    jobs.awaitAll()
                }
            }

            // 4. Social Scan
            val harvestedHints = if (keywordHint.isNullOrBlank()) {
                harvestSearchHints(allResults.toList())
            } else listOf(keywordHint)

            if (harvestedHints.isNotEmpty()) {
                val primaryName = harvestedHints.first()
                onLog("Discovered identity hint: '$primaryName'. Running social lookup...")
                val scraper = WebViewScraper.create(context)
                try {
                    allResults.addAll(scraper.scrapeSocialDork("instagram.com", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("facebook.com", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("twitter.com", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("tiktok.com", primaryName))
                } finally {
                    scraper.destroy()
                }
            }

            onLog("Search complete. Retrieved ${allResults.size} total candidates.")
            allResults.distinctBy { it.link }
        }
    }

    /**
     * Attempts to extract high-resolution media from a profile URL using the Termux backend.
     */
    suspend fun extractHighResMedia(profileUrl: String): String? = withContext(Dispatchers.IO) {
        val backendBase = activeBackend ?: return@withContext null
        try {
            val api = RetrofitClient.getInstance(backendBase)
            val response = api.extractMedia(mapOf("url" to profileUrl))
            if (response.isSuccessful) {
                response.body()?.highResUrl
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fallback that extracts thumbnail/preview from page metadata (og:image).
     */
    suspend fun extractMetadataThumbnail(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val html = response.body?.string() ?: return@withContext null

                val ogRegex = Regex("""<meta\s+property=["']og:image["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                val twitterRegex = Regex("""<meta\s+name=["']twitter:image["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

                ogRegex.find(html)?.groupValues?.get(1)
                    ?: twitterRegex.find(html)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun harvestSearchHints(matches: List<SerpVisualMatch>): List<String> {
        val candidates = matches.filter { !it.title.isNullOrBlank() && it.title != "Visual Match" }
        if (candidates.isEmpty()) return emptyList()

        val hints = mutableSetOf<String>()
        val stopWords = setOf(
            "image", "photo", "picture", "wallpaper", "visual", "match", "stock", "vector",
            "search", "engine", "google", "bing", "yandex", "lens", "the", "and", "for", "with",
            "amazon", "vest", "shirt", "apparel", "clothing", "camicie", "style", "shop", "store",
            "t-shirt", "tee", "sleeve", "patchwork", "casual", "mens", "womens", "aliexpress", "temu"
        )

        candidates.forEach { match ->
            val cleanTitle = match.title.orEmpty()
                .replace(Regex("(?i)[|\\-].*(wikipedia|imdb|instagram|facebook|twitter|news|youtube|amazon|shein|temu|ebay).*"), "")
                .trim()

            val words = cleanTitle.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size in 2..3 && words.none { it.lowercase() in stopWords }) {
                hints.add(cleanTitle)
            }
        }
        return hints.toList().take(3)
    }

    suspend fun performLocalServerSearch(
        @Suppress("UNUSED_PARAMETER") bitmap: Bitmap,
        @Suppress("UNUSED_PARAMETER") faceBitmap: Bitmap? = null,
        keywordHint: String? = null,
        imageUrl: String? = null,
        sceneUrl: String? = null,
        searchMode: String = "PRECISION",
        @Suppress("UNUSED_PARAMETER") onLog: (String) -> Unit = {}
    ): ServerSearchResponse {
        val backendBase = activeBackend ?: "http://127.0.0.1:3000"
        val request = ServerSearchRequest(
            imageUrl = imageUrl ?: "http://127.0.0.1:8080/face.jpg",
            sceneUrl = sceneUrl ?: imageUrl,
            keywordHint = keywordHint,
            searchMode = searchMode
        )
        return try {
            RetrofitClient.getInstance(backendBase).searchByImage(request)
        } catch (e: Exception) {
            ServerSearchResponse(success = false, error = e.message)
        }
    }

    suspend fun isLocalBackendAvailable(): Boolean = withContext(Dispatchers.IO) {
        val backendBase = "http://127.0.0.1:3000"
        try {
            fastClient.newCall(Request.Builder().url("$backendBase/api/ping").get().build()).execute().use { 
                if (it.isSuccessful) { activeBackend = backendBase; return@withContext true }
            }
        } catch (_: Exception) {}
        false
    }

    suspend fun performSerpApiSearch(
        imageUrl: String,
        includeExactMatches: Boolean = false,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        val apiKey = com.yourcompany.facesearch.BuildConfig.SERP_API_KEY
        if (apiKey.isBlank()) return@withContext emptyList()

        try {
            val visualResponse = RetrofitClient.getSerpApi().googleLensSearch(url = imageUrl, type = "visual_matches", apiKey = apiKey)
            val visualMatches = visualResponse.visualMatches.orEmpty().map { match ->
                SerpVisualMatch(title = match.title, link = match.link, source = "Google Lens", thumbnail = match.thumbnail, score = 800)
            }
            onLog("✓ SerpApi found ${visualMatches.size} candidate(s)")
            visualMatches
        } catch (_: Exception) {
            emptyList()
        }
    }
}
