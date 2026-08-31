package com.yourcompany.facesearch.network

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import com.yourcompany.facesearch.network.model.ServerSearchRequest
import com.yourcompany.facesearch.network.model.ServerSearchResponse
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class FaceSearchRepository(private val context: Context) {

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    )

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
        bitmap: android.graphics.Bitmap,
        faceBitmap: android.graphics.Bitmap? = null,
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

            // 1. Upload Probes
            val faceUrl = if (imageUrl != null && imageUrl.startsWith("http")) {
                imageUrl
            } else {
                onLog("Uploading face probe...")
                freeHost.upload(faceBitmap ?: bitmap, onLog)
            }

            val finalSceneUrl = if (sceneUrl != null && sceneUrl.startsWith("http")) {
                sceneUrl
            } else if (faceBitmap != null) {
                onLog("Uploading full portrait scene...")
                freeHost.upload(bitmap, onLog)
            } else {
                faceUrl
            }

            // FIX: Force engines to use the tightly cropped face! 
            // Sending finalSceneUrl causes Google to search for clothing instead of the face.
            val primaryProbe = faceUrl ?: finalSceneUrl

            // 2. Termux First Execution
            val termuxAvailable = isLocalBackendAvailable()
            if (termuxAvailable && primaryProbe != null) {
                onLog("Querying Termux scraper backend...")
                val termuxResponse = performLocalServerSearch(
                    bitmap = bitmap,
                    faceBitmap = faceBitmap,
                    keywordHint = keywordHint,
                    imageUrl = faceUrl,
                    sceneUrl = finalSceneUrl,
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

            // 3. In-App Scraper Fallback if Termux returned few or zero results
            if (allResults.size < 5 && primaryProbe != null) {
                onLog("Running in-app visual engine fallback...")
                coroutineScope {
                    val jobs = mutableListOf<Deferred<Unit>>()

                    if ("Google" !in enginesToSkip) {
                        jobs.add(async {
                            val s = WebViewScraper.create(context)
                            try {
                                val matches = s.scrapeGoogle(primaryProbe)
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }

                    if ("Bing" !in enginesToSkip) {
                        jobs.add(async {
                            val s = WebViewScraper.create(context)
                            try {
                                val matches = s.scrapeBing(primaryProbe)
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }

                    if ("Yandex" !in enginesToSkip) {
                        jobs.add(async {
                            val s = WebViewScraper.create(context)
                            try {
                                val matches = s.scrapeYandex(primaryProbe)
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }

                    if (com.yourcompany.facesearch.BuildConfig.SERP_API_KEY.isNotBlank()) {
                        jobs.add(async {
                            val matches = performSerpApiSearch(primaryProbe, includeExactLensMatches, onLog)
                            allResults.addAll(matches)
                            Unit
                        })
                    }

                    jobs.awaitAll()
                }
            }

            // 4. Celebrity/Name Extraction & Social Scan
            val harvestedHints = if (keywordHint.isNullOrBlank()) {
                harvestSearchHints(allResults.toList())
            } else listOf(keywordHint)

            if (harvestedHints.isNotEmpty()) {
                val primaryName = harvestedHints.first()
                onLog("Discovered entity hint: '$primaryName'. Running social lookup...")

                val scraper = WebViewScraper.create(context)
                try {
                    allResults.addAll(scraper.scrapeSocialDork("instagram.com", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("facebook.com", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("twitter.com", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("tiktok.com", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("vsco.co", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("github.com", primaryName))
                    allResults.addAll(scraper.scrapeSocialDork("newsite.com", primaryName))
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
        val candidates = matches.filter { !it.title.isNullOrBlank() && it.title != "Visual Candidate" }
        if (candidates.isEmpty()) return emptyList()

        val hints = mutableSetOf<String>()
        
        // FIX: Added extensive shopping and product stop-words so it doesn't search for shirts
        val stopWords = setOf(
            "image", "photo", "picture", "wallpaper", "visual", "match", "stock", "vector",
            "search", "engine", "google", "bing", "yandex", "lens", "the", "and", "for", "with",
            "amazon", "amazoncom", "vest", "shirt", "apparel", "clothing", "camicie", "style", 
            "scarf", "preaching", "consulting", "contact", "product", "shop", "store"
        )

        candidates.forEach { match ->
            val cleanTitle = match.title.orEmpty()
                .replace(Regex("(?i)[|\\-].*(wikipedia|imdb|instagram|facebook|twitter|news|youtube|amazon).*"), "")
                .trim()

            val words = cleanTitle.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size in 2..4 && words.none { it.lowercase() in stopWords }) {
                hints.add(cleanTitle)
            }
        }

        return hints.toList().take(5)
    }

    suspend fun performLocalServerSearch(
        @Suppress("UNUSED_PARAMETER") bitmap: android.graphics.Bitmap,
        @Suppress("UNUSED_PARAMETER") faceBitmap: android.graphics.Bitmap? = null,
        keywordHint: String? = null,
        imageUrl: String? = null,
        sceneUrl: String? = null,
        searchMode: String = "PRECISION",
        onLog: (String) -> Unit = {}
    ): ServerSearchResponse {
        val backendBase = activeBackend ?: "http://127.0.0.1:3000"
        val request = ServerSearchRequest(
            imageUrl = imageUrl ?: "http://127.0.0.1:8080/face.jpg",
            sceneUrl = sceneUrl ?: imageUrl,
            keywordHint = keywordHint,
            searchMode = searchMode
        )

        return try {
            val api = RetrofitClient.getInstance(backendBase)
            api.searchByImage(request)
        } catch (e: Exception) {
            onLog("Termux request error: ${e.message}")
            ServerSearchResponse(success = false, error = e.message)
        }
    }

    suspend fun isLocalBackendAvailable(): Boolean = withContext(Dispatchers.IO) {
        val backendBase = "http://127.0.0.1:3000"
        try {
            val request = Request.Builder().url("$backendBase/api/ping").get().build()
            fastClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    activeBackend = backendBase
                    return@withContext true
                }
            }
        } catch (_: Exception) {}
        false
    }

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

    suspend fun performSerpApiSearch(
        imageUrl: String,
        includeExactMatches: Boolean = false,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        val apiKey = com.yourcompany.facesearch.BuildConfig.SERP_API_KEY
        if (apiKey.isBlank()) return@withContext emptyList()

        onLog(if (includeExactMatches) {
            "Requesting Google Lens visual and exact matches via SerpApi..."
        } else {
            "Requesting Google Lens visual matches via SerpApi..."
        })
        val api = RetrofitClient.getSerpApi()
        try {
            val visualResponse = runCatching {
                api.googleLensSearch(url = imageUrl, type = "visual_matches", apiKey = apiKey)
            }.getOrElse { error ->
                onLog("⚠ Lens visual-match request failed: ${error.message}")
                null
            }
            val exactResponse = if (includeExactMatches) {
                runCatching {
                    api.googleLensSearch(url = imageUrl, type = "exact_matches", apiKey = apiKey)
                }.getOrElse { error ->
                    onLog("⚠ Lens exact-match request failed: ${error.message}")
                    null
                }
            } else {
                null
            }

            val visualMatches = visualResponse?.visualMatches.orEmpty().map { match ->
                SerpVisualMatch(
                    title = match.title,
                    link = match.link,
                    source = "Google Lens visual · ${match.source ?: "web"}",
                    thumbnail = match.thumbnail,
                    score = 800
                )
            }
            val exactMatches = (exactResponse?.exactMatches ?: exactResponse?.visualMatches).orEmpty().map { match ->
                SerpVisualMatch(
                    title = match.title,
                    link = match.link,
                    source = "Google Lens exact · ${match.source ?: "web"}",
                    thumbnail = match.thumbnail,
                    score = 1_000
                )
            }
            val results = (exactMatches + visualMatches)
                .distinctBy { match -> ThumbnailUtils.canonicalKey(match.thumbnail) ?: match.link }

            onLog(
                if (includeExactMatches) {
                    "✓ SerpApi found ${visualMatches.size} visual and ${exactMatches.size} exact candidate(s)"
                } else {
                    "✓ SerpApi found ${visualMatches.size} visual candidate(s)"
                }
            )
            results
        } catch (e: Exception) {
            onLog("⚠ SerpApi error: ${e.message}")
            emptyList()
        }
    }

    suspend fun extractMetadataThumbnail(url: String): String? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgents.first()) 
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val html = response.body?.string() ?: return@withContext null
                
                val ogMatch = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']").find(html)
                    ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']").find(html)
                
                val rawUrl = ogMatch?.groupValues?.get(1)
                ThumbnailUtils.normalize(rawUrl)
            }
        } catch (e: Exception) {
            null
        }
    }
}
