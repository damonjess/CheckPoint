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
                    allResults.addAll(scraper.scrapeSocialDork("linktr.ee", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("twitch.tv", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("patreon.com", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("bsky.app", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("mastodon.social", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("behance.net", primaryName))
                } finally {
                    scraper.destroy()
                }
            }

            onLog("Search complete. Retrieved ${allResults.size} total candidates.")
            allResults.distinctBy { it.link }
        }
    }

    private fun harvestSearchHints(matches: List<SerpVisualMatch>): List<String> {
        val candidates = matches.filter { !it.title.isNullOrBlank() && it.title != "Visual Match" && it.title != "Visual Candidate" }
        if (candidates.isEmpty()) return emptyList()

        val hints = mutableSetOf<String>()
        
        // Expanded to completely block weird extractions like "Page 294: Celebrity"
        val stopWords = setOf(
            "image", "photo", "picture", "wallpaper", "visual", "match", "stock", "vector",
            "search", "engine", "google", "bing", "yandex", "lens", "the", "and", "for", "with",
            "amazon", "vest", "shirt", "apparel", "clothing", "camicie", "style", "shop", "store",
            "t-shirt", "tee", "sleeve", "patchwork", "casual", "mens", "womens", "aliexpress", "temu",
            "fotka", "foto", "pic", "pics", "images", "img", "teeth", "hair", "feet", "body", "legs",
            "hot", "pregnant", "surgery", "plastic", "boyfriend", "girlfriend", "husband", "wife",
            "dating", "outfit", "dress", "makeup", "look", "looks", "page", "celebrity", "gallery",
            "index", "collection", "album", "download", "free", "video", "videos", "clip", "watch",
            "movie", "movies", "actor", "actress", "model", "star", "birthday", "celebrates", "today",
            "tiktok", "instagram", "facebook", "reddit", "twitter", "shein", "ebay"
        )

        candidates.forEach { match ->
            // Aggressively strip anything after a dash, pipe, colon, or bracket to isolate the name
            val cleanTitle = match.title.orEmpty()
                .replace(Regex("(?i)[|\\-–—:(\\[].*"), "") 
                .trim()

            // Names don't typically have numbers in them. Discard instantly.
            if (cleanTitle.any { it.isDigit() }) return@forEach

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

    // RESTORED FUNCTIONS:

    suspend fun extractHighResMedia(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val backendBase = activeBackend ?: "http://127.0.0.1:3000"
            val api = RetrofitClient.getInstance(backendBase)
            val response = api.extractMedia(mapOf("url" to url))
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    return@withContext body.highResUrl
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FaceSearchRepository", "Extraction error: ${e.message}")
        }
        null
    }

    suspend fun performTermuxDorkSearch(
        keyword: String,
        sites: List<String> = AdultSiteConfig.SITES,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        onLog("Requesting Termux Dork Scan...")
        try {
            val request = com.yourcompany.facesearch.network.model.DorkSearchRequest(
                keyword = keyword,
                sites = sites
            )
            val backendBase = activeBackend ?: "http://127.0.0.1:3000"
            val api = RetrofitClient.getInstance(backendBase)
            val response = api.dorkSearch(request)
            if (response.success) {
                onLog("✓ Termux Dork found ${response.matches?.size ?: 0} hits")
                return@withContext response.matches?.map {
                    SerpVisualMatch(
                        title = it.title,
                        link = it.link,
                        source = it.source,
                        thumbnail = it.thumbnail,
                        score = it.score
                    )
                } ?: emptyList()
            }
        } catch (e: Exception) {
            onLog("⚠ Termux Dork failed: ${e.message}")
        }
        emptyList()
    }

    suspend fun extractMetadataThumbnail(url: String): String? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val html = response.body?.string() ?: return@withContext null
                
                val ogMatch = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']").find(html)
                    ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']").find(html)
                
                val rawUrl = ogMatch?.groupValues?.get(1)
                ThumbnailUtils.normalize(rawUrl)
            }
        } catch (_: Exception) {
            null
        }
    }
}
