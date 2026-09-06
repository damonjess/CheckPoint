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
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class FaceSearchRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
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
        @Suppress("UNUSED_PARAMETER") sceneUrl: String? = null,
        @Suppress("UNUSED_PARAMETER") deepCrawl: Boolean = false,
        searchMode: String = "PRECISION",
        includeExactLensMatches: Boolean = false,
        @Suppress("UNUSED_PARAMETER") skipVisualEngines: Boolean = false,
        enginesToSkip: Set<String> = emptySet(),
        skipTermux: Boolean = false,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = searchMutex.withLock {
        withContext(Dispatchers.IO) {
            val allResults = java.util.Collections.synchronizedList(mutableListOf<SerpVisualMatch>())

            val searchBitmap = faceBitmap ?: NativeFaceCropper().prepareFaceForSearch(bitmap)
            val stream = ByteArrayOutputStream()
            // This must be 92 (high quality) instead of a low number! (The 15 KB Issue Fix)
            searchBitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            val byteArray = stream.toByteArray()

            val probeUrl = if (imageUrl != null && imageUrl.startsWith("http")) {
                imageUrl
            } else {
                onLog("Uploading strict face probe (${byteArray.size / 1024} KB) to prevent clothing matches...")
                freeHost.upload(searchBitmap, onLog)
            }

            if (probeUrl == null) {
                onLog("✗ Image upload failed. Cannot perform visual search.")
                return@withContext emptyList()
            }

            if (!skipTermux && isLocalBackendAvailable()) {
                onLog("Querying Termux scraper backend...")
                val termuxResponse = performLocalServerSearch(
                    bitmap = bitmap,
                    faceBitmap = searchBitmap,
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

            // In no-Termux mode (skipTermux) we always run the in-app visual
            // engines, even if another source already returned a few hits.
            if (allResults.size < 5 || skipTermux) {
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
            } else listOf(keywordHint.trim())

            if (harvestedHints.isNotEmpty()) {
                val primaryName = harvestedHints.first().trim()
                val scraper = WebViewScraper.create(context)
                try {
                    if (searchMode.equals("ADULT", ignoreCase = true)) {
                        // ==========================================
                        // 1. ADULT NETWORK DORKING BRANCH
                        // ==========================================
                        onLog("Adult scan mode active: Running dork queries for '$primaryName' across adult networks...")
                        val chunks = AdultSiteConfig.SITES.chunked(5)
                        chunks.forEachIndexed { idx, siteBatch ->
                            onLog("Scanning Adult Group #${idx + 1}...")
                            
                            // Try Termux Video Index Scraper first
                            val dorkHits = performTermuxDorkSearch(
                                keyword = primaryName,
                                sites = siteBatch,
                                onLog = onLog
                            )
                            
                            if (dorkHits.isNotEmpty()) {
                                // Fetch any missing metadata thumbnails concurrently in parallel
                                val enrichedHits = coroutineScope {
                                    dorkHits.map { hit ->
                                        async {
                                            if (hit.thumbnail.isNullOrBlank()) {
                                                val metaThumb = extractMetadataThumbnail(hit.link.orEmpty())
                                                hit.copy(thumbnail = metaThumb)
                                            } else {
                                                hit
                                            }
                                        }
                                    }.awaitAll()
                                }
                                
                                allResults.addAll(enrichedHits)
                                onLog("✓ Termux Adult Group #${idx + 1} returned ${dorkHits.size} result(s)")
                            }
 else {
                                // Fallback to In-App WebView scraper if Termux returned 0 or is offline
                                val batchResults = scraper.scrapeBatchedAdultDork(
                                    sites = siteBatch,
                                    keyword = primaryName,
                                    groupLabel = "Adult Group #${idx + 1}",
                                    onLog = onLog
                                )
                                allResults.addAll(batchResults)
                                onLog("✓ In-App Adult Group #${idx + 1} returned ${batchResults.size} result(s)")
                            }
                        }
                    } else {
                        // ==========================================
                        // 2. STANDARD OSINT & REGIONAL LOOKUP BRANCH
                        // ==========================================
                        onLog("Discovered identity hint: '$primaryName'. Running UK & global profile lookup...")
                        
                        // Core Social Networks
                        allResults.addAll(scraper.scrapeSocialDork("facebook.com", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("instagram.com", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("linkedin.com/in", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("twitter.com", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("tiktok.com/@", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("threads.net", primaryName, onLog))
                        
                        // UK & Regional Directories
                        allResults.addAll(scraper.scrapeSocialDork("192.com", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("thegazette.co.uk", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("grimsbytelegraph.co.uk", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("scunthorpetelegraph.co.uk", primaryName, onLog))
                        
                        // Image & Creator Platforms
                        allResults.addAll(scraper.scrapeSocialDork("reddit.com/user", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("pinterest.co.uk", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("flickr.com", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("tumblr.com", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("vsco.co", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("vk.com", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("linktr.ee", primaryName, onLog))
                        allResults.addAll(scraper.scrapeSocialDork("twitch.tv", primaryName, onLog))
                    }
                } finally {
                    scraper.destroy()
                }
            } else if (searchMode.equals("ADULT", ignoreCase = true)) {
                onLog("⚠️ Tip: For adult platform scanning, enter a name or username in OSINT TARGET HINT if reverse image search finds no text matches.")
            }

            onLog("Search complete. Retrieved ${allResults.size} total candidates.")
            allResults.distinctBy { it.link }
        }
    }

    private fun harvestSearchHints(matches: List<SerpVisualMatch>): List<String> {
        val candidates = matches.filter { 
            val title = it.title?.lowercase() ?: ""
            title.isNotBlank() && title != "visual match" && title != "visual candidate" && title != "visual matches"
        }
        if (candidates.isEmpty()) return emptyList()

        val hints = mutableSetOf<String>()
        
        val stopWords = setOf(
            "image", "photo", "picture", "wallpaper", "visual", "match", "stock", "vector",
            "search", "engine", "google", "bing", "yandex", "lens", "the", "and", "for", "with",
            "amazon", "vest", "shirt", "apparel", "clothing", "style", "shop", "store",
            // Exclude entertainment databases
            "imdb", "wikipedia", "fandom", "themoviedb", "britannica", "wiki", "biography",
            "actor", "actress", "celebrity", "movie", "film", "cast", "character", "tv"
        )

        candidates.forEach { match ->
            val title = match.title.orEmpty()
            
            val cleanTitle = title
                .replace(Regex("(?i)[|\\-–—:(\\[].*"), "")
                .replace(Regex("[^a-zA-Z0-9\\s]"), "")
                .trim()

            val words = cleanTitle.split(Regex("\\s+")).filter { it.isNotBlank() }
            
            if (cleanTitle.all { it.isDigit() || it.isWhitespace() }) return@forEach

            if (words.size >= 2 && words.none { it.lowercase() in stopWords }) {
                hints.add(cleanTitle)
            } else if (words.size == 1 && words[0].length > 3 && words[0].lowercase() !in stopWords) {
                hints.add(words[0])
            }
        }
        
        if (hints.isEmpty()) {
            val allWords = candidates.flatMap { it.title.orEmpty().lowercase().split(Regex("\\s+")) }
                .filter { it.length > 3 && it !in stopWords }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
            
            if (allWords.isNotEmpty()) {
                hints.add(allWords.joinToString(" "))
            }
        }

        return hints.toList().take(5)
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
            val visualResponse = RetrofitClient.getSerpApi().googleLensSearch(
                url = imageUrl,
                type = "visual_matches",
                apiKey = apiKey
            )
            val visualMatches = visualResponse.visualMatches.orEmpty().map { match ->
                SerpVisualMatch(
                    title = match.title,
                    link = match.link,
                    source = "Google Lens",
                    thumbnail = match.thumbnail,
                    score = 800
                )
            }
            val exactFromVisual = visualResponse.exactMatches.orEmpty().map { match ->
                SerpVisualMatch(
                    title = match.title,
                    link = match.link,
                    source = "Google Lens (Exact)",
                    thumbnail = match.thumbnail,
                    score = 900
                )
            }

            val exactMatches = if (includeExactMatches) {
                try {
                    val exactResponse = RetrofitClient.getSerpApi().googleLensSearch(
                        url = imageUrl,
                        type = "exact_matches",
                        apiKey = apiKey
                    )
                    exactResponse.exactMatches.orEmpty().map { match ->
                        SerpVisualMatch(
                            title = match.title,
                            link = match.link,
                            source = "Google Lens (Exact)",
                            thumbnail = match.thumbnail,
                            score = 900
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val combined = (exactMatches + exactFromVisual + visualMatches)
                .filter { !it.link.isNullOrBlank() }
                .distinctBy { it.link }

            onLog("✓ SerpApi found ${combined.size} candidate(s)")
            combined
        } catch (e: Exception) {
            onLog("⚠ SerpApi search error: ${e.message}")
            emptyList()
        }
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
        try {
            val request = com.yourcompany.facesearch.network.model.DorkSearchRequest(
                keyword = keyword,
                sites = sites
            )
            val backendBase = activeBackend ?: "http://127.0.0.1:3000"
            val api = RetrofitClient.getInstance(backendBase)
            val response = api.dorkSearch(request)
            if (response.success) {
                return@withContext response.matches?.map {
                    SerpVisualMatch(
                        title = it.title,
                        link = it.link,
                        source = AdultSiteConfig.labelFor(it.link ?: ""), // Fixes source assignment
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