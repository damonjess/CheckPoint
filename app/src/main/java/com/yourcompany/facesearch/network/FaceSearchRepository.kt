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
import retrofit2.HttpException
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

            val cropper = NativeFaceCropper()
            val searchBitmap = faceBitmap ?: cropper.prepareFaceForSearch(bitmap)
            val expandedBitmap = cropper.getExpandedHeadAndShouldersProbe(bitmap)

            val stream = ByteArrayOutputStream()
            searchBitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            val byteArray = stream.toByteArray()

            val probeUrl = if (imageUrl != null && imageUrl.startsWith("http")) {
                imageUrl
            } else {
                onLog("Uploading primary face probe (${byteArray.size / 1024} KB)...")
                freeHost.upload(searchBitmap, onLog)
            }

            val expandedProbeUrl = if (expandedBitmap != null && (imageUrl == null || !imageUrl.startsWith("http"))) {
                onLog("Uploading secondary expanded probe (head & shoulders)...")
                freeHost.upload(expandedBitmap, onLog)
            } else sceneUrl ?: imageUrl

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

                    if ("Sogou" !in enginesToSkip) {
                        jobs.add(async {
                            val s = WebViewScraper.create(context)
                            try {
                                val matches = s.scrapeSogou(probeUrl)
                                if (matches.isNotEmpty()) {
                                    onLog("✓ Sogou found ${matches.size} candidate(s)")
                                } else {
                                    onLog("ℹ Sogou: 0 candidates found")
                                }
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }

                    if ("TinEye" !in enginesToSkip) {
                        jobs.add(async {
                            val s = WebViewScraper.create(context)
                            try {
                                val matches = s.scrapeTinEye(probeUrl)
                                if (matches.isNotEmpty()) {
                                    onLog("✓ TinEye found ${matches.size} candidate(s)")
                                } else {
                                    onLog("ℹ TinEye: 0 candidates found")
                                }
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
                                if (matches.isNotEmpty()) {
                                    onLog("✓ Google found ${matches.size} candidate(s)")
                                } else {
                                    onLog("ℹ Google: 0 candidates found")
                                }
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
                                if (matches.isNotEmpty()) {
                                    onLog("✓ Bing found ${matches.size} candidate(s)")
                                } else {
                                    onLog("ℹ Bing: 0 candidates found")
                                }
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
                                if (expandedProbeUrl != null && expandedProbeUrl != probeUrl) {
                                    val secondary = s.scrapeYandex(expandedProbeUrl)
                                    allResults.addAll(secondary)
                                }
                                if (matches.isNotEmpty()) {
                                    onLog("✓ Yandex found ${matches.size} candidate(s)")
                                } else {
                                    onLog("ℹ Yandex: 0 candidates found")
                                }
                                allResults.addAll(matches)
                                Unit
                            } finally { s.destroy() }
                        })
                    }

                    if ("SerpApi" !in enginesToSkip && SerpApiKeyManager.hasApiKey(context)) {
                        jobs.add(async {
                            val matches = performSerpApiSearch(probeUrl, includeExactLensMatches, searchBitmap, onLog)
                            if (expandedProbeUrl != null && expandedProbeUrl != probeUrl) {
                                val secondary = performSerpApiSearch(expandedProbeUrl, includeExactLensMatches, expandedBitmap)
                                allResults.addAll(secondary)
                            }
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
                        
                        // Validate the identity hint before using it
                        val isUrl = primaryName.startsWith("http") || primaryName.contains("://") || primaryName.contains("www.")
                        val isTooLong = primaryName.length > 50
                        val isGarbage = primaryName.contains("shutterstock") || primaryName.contains("gettyimages") || primaryName.contains("alamy")
                        
                        if (isUrl || isTooLong || isGarbage) {
                            onLog("⚠ Identity hint looks like a URL or stock photo reference — skipping social dorking.")
                        } else {
                            // Concurrent Live Username Handle Scanner across 34 social platforms
                            val directHandles = UsernameScanner.scanUsername(primaryName, onLog)
                            allResults.addAll(directHandles)

                            // Concurrent DuckDuckGo OSINT Dorking for major social networks
                            val ddgDomains = listOf(
                                "facebook.com", "instagram.com", "linkedin.com", "twitter.com",
                                "tiktok.com", "github.com", "reddit.com", "threads.net",
                                "bsky.app", "mastodon.social", "vk.com", "tumblr.com",
                                "flickr.com", "pinterest.com", "youtube.com", "twitch.tv",
                                "onlyfans.com", "fansly.com", "patreon.com", "soundcloud.com",
                                "spotify.com", "behance.net", "dribbble.com", "keybase.io",
                                "linktr.ee", "vsco.co", "substack.com", "medium.com"
                            )
                            coroutineScope {
                                val ddgJobs = ddgDomains.map { domain ->
                                    async { DuckDuckGoDorker.dork(domain, primaryName, onLog) }
                                }
                                allResults.addAll(ddgJobs.awaitAll().flatten())
                            }

                            // ALL Social Dorking — parallelized with coroutines for speed
                            val socialDorkSites = listOf(
                                "facebook.com", "instagram.com", "linkedin.com/in", "twitter.com",
                                "tiktok.com/@", "threads.net", "bsky.app", "mastodon.social",
                                // UK & Regional Directories
                                "192.com", "thegazette.co.uk", "grimsbytelegraph.co.uk",
                                "scunthorpetelegraph.co.uk", "yell.com", "companieshouse.gov.uk",
                                "findmypast.co.uk",
                                // Image & Creator Platforms
                                "reddit.com/user", "pinterest.co.uk", "flickr.com", "tumblr.com",
                                "vsco.co", "vk.com", "linktr.ee", "twitch.tv", "youtube.com/@",
                                "soundcloud.com", "behance.net", "dribbble.com", "patreon.com",
                                "substack.com", "medium.com/@", "keybase.io",
                                // Professional & Tech Platforms
                                "github.com", "gitlab.com", "stackoverflow.com/users", "producthunt.com/@"
                            )
                            
                            onLog("Parallel social dorking across ${socialDorkSites.size} sites...")
                            coroutineScope {
                                // Process in batches of 8 to balance speed vs WebView resource limits
                                socialDorkSites.chunked(8).forEachIndexed { batchIdx, batch ->
                                    val batchJobs = batch.map { site ->
                                        async {
                                            val s = WebViewScraper.create(context)
                                            try {
                                                s.scrapeSocialDork(site, primaryName, onLog)
                                            } finally {
                                                s.destroy()
                                            }
                                        }
                                    }
                                    val batchResults = batchJobs.awaitAll().flatten()
                                    allResults.addAll(batchResults)
                                    if (batchResults.isNotEmpty()) {
                                        onLog("✓ Dork batch #${batchIdx + 1}: ${batchResults.size} result(s)")
                                    }
                                }
                            }
                            onLog("Social dorking complete.")
                        }
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
        // Reject titles that are URLs, image links, or file paths.
        val urlPattern = Regex("(?i)^https?://|^www\\.|\\.(com|net|org|co\\\\.uk|io|ru|de|fr|jpg|jpeg|png|gif|webp|html?|php|aspx?)$|/pic-|/images?/|/photo|/uploads?/|/assets?/|/static/|cdn\\.")
        // Reject titles containing multiple URLs (e.g. "https://site1 https://site2")
        val multiUrlPattern = Regex("(?i)https?://")

        val candidates = matches.filter {
            val title = it.title?.trim() ?: ""
            title.isNotBlank() &&
            title != "visual match" &&
            title != "visual candidate" &&
            title != "visual matches" &&
            !urlPattern.containsMatchIn(title) &&
            multiUrlPattern.findAll(title).count() <= 1 &&
            !title.startsWith("http") &&
            !title.contains("shutterstock.com") &&
            !title.contains("gettyimages") &&
            !title.contains("alamy.com") &&
            !title.contains("istockphoto") &&
            !title.contains("pinterest.com/pin/") &&
            !title.contains("pinimg.com") &&
            !title.contains("fotocdn") &&
            !title.contains("vivoo.ru")
        }
        if (candidates.isEmpty()) return emptyList()

        val hints = mutableSetOf<String>()
        
        val stopWords = setOf(
            "image", "photo", "picture", "wallpaper", "visual", "match", "candidate", "matches",
            "search", "engine", "google", "bing", "yandex", "lens", "the", "and", "for", "with",
            "amazon", "vest", "shirt", "apparel", "clothing", "style", "shop", "store",
            "http", "https", "www", "com", "net", "org", "co", "uk",
            // Exclude entertainment databases
            "imdb", "wikipedia", "fandom", "themoviedb", "britannica", "wiki", "biography",
            "actor", "actress", "celebrity", "movie", "film", "cast", "character", "tv",
            // Exclude stock photo / generic terms
            "shutterstock", "getty", "alamy", "istock", "stock", "royalty", "free",
            "download", "buy", "price", "license", "editorial", "creative",
            // Exclude tech/education that clutters results
            "cloudflare", "geeksforgeeks", "hostinger", "mcafee", "tutorial",
            "explain", "working", "what", "why", "how", "learn", "guide"
        )

        candidates.forEach { match ->
            val title = match.title.orEmpty()
            
            // Skip if title looks like a URL or file path
            if (title.startsWith("http") || title.contains("://") || title.matches(Regex(".*\\.(jpg|png|gif|webp|html?)", RegexOption.IGNORE_CASE))) {
                return@forEach
            }
            
            // Try extracting a name from the title
            val cleanTitle = title
                .replace(Regex("(?i)[|\\-–—:(\\[].*"), "")
                .replace(Regex("(?i)\\s+(profile|page|account|user|official)\\s*"), " ")
                .replace(Regex("[^a-zA-Z0-9\\s]"), "")
                .trim()

            val words = cleanTitle.split(Regex("\\s+")).filter { it.isNotBlank() }
            
            if (cleanTitle.all { it.isDigit() || it.isWhitespace() }) return@forEach
            if (cleanTitle.length < 2) return@forEach

            // Accept 2+ word titles where most words aren't stop words
            if (words.size >= 2 && words.count { it.lowercase() in stopWords } == 0) {
                hints.add(cleanTitle)
            } else if (words.size >= 2 && words.count { it.lowercase() in stopWords } <= 1) {
                // Allow one stop word if we have 3+ words total
                val filtered = words.filter { it.lowercase() !in stopWords }
                if (filtered.size >= 2) {
                    hints.add(filtered.joinToString(" "))
                }
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
        @Suppress("UNUSED_PARAMETER") includeExactMatches: Boolean = false,
        bitmap: Bitmap? = null,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        val apiKey = SerpApiKeyManager.getApiKey(context)
        if (apiKey.isBlank()) return@withContext emptyList()

        var effectiveUrl = imageUrl
        if (effectiveUrl.isBlank() || effectiveUrl.contains("127.0.0.1") || effectiveUrl.contains("localhost")) {
            if (bitmap != null) {
                onLog("Uploading probe to public host for SerpApi...")
                val uploaded = freeHost.upload(bitmap, onLog)
                if (!uploaded.isNullOrBlank()) {
                    effectiveUrl = uploaded
                }
            }
        }

        if (effectiveUrl.isBlank() || effectiveUrl.contains("127.0.0.1") || effectiveUrl.contains("localhost")) {
            onLog("⚠ SerpApi requires a publicly accessible image URL. (Localhost probe URLs cannot be reached by SerpApi).")
            return@withContext emptyList()
        }

        onLog("Requesting Google Lens visual matches via SerpApi...")
        try {
            val response = RetrofitClient.getSerpApi().googleLensSearch(
                url = effectiveUrl,
                apiKey = apiKey
            )

            val rawErr = response.error.orEmpty()
            if (rawErr.isNotBlank()) {
                if (rawErr.contains("Invalid API key", ignoreCase = true) || rawErr.contains("API key", ignoreCase = true)) {
                    onLog("⚠ SerpApi Error: Invalid API key. Please check your key in settings.")
                    return@withContext emptyList()
                } else if (rawErr.contains("search limit", ignoreCase = true) || rawErr.contains("quota", ignoreCase = true) || rawErr.contains("out of searches", ignoreCase = true)) {
                    onLog("⚠ SerpApi Error: Monthly search limit reached for this API key.")
                    return@withContext emptyList()
                } else if (!rawErr.contains("hasn't returned any results", ignoreCase = true) &&
                    !rawErr.contains("no results", ignoreCase = true)) {
                    onLog("⚠ SerpApi notice: $rawErr")
                }
            }

            val visualMatches = response.visualMatches.orEmpty().map { match ->
                SerpVisualMatch(
                    title = match.title ?: "Google Lens Visual Match",
                    link = match.link,
                    source = match.source ?: "Google Lens",
                    thumbnail = match.bestThumbnail,
                    score = 800
                )
            }

            val exactMatches = response.exactMatches.orEmpty().map { match ->
                SerpVisualMatch(
                    title = match.title ?: "Google Lens Exact Match",
                    link = match.link,
                    source = match.source ?: "Google Lens (Exact)",
                    thumbnail = match.bestThumbnail,
                    score = 900
                )
            }

            val knowledgeMatches = response.knowledgeGraph.orEmpty().map { match ->
                SerpVisualMatch(
                    title = match.title ?: "Google Lens Knowledge Match",
                    link = match.link,
                    source = match.source ?: "Google Knowledge Graph",
                    thumbnail = match.bestThumbnail,
                    score = 850
                )
            }

            val organicMatches = response.organicResults.orEmpty().map { match ->
                SerpVisualMatch(
                    title = match.title ?: "Google Search Result",
                    link = match.link,
                    source = match.source ?: "Google Search",
                    thumbnail = match.bestThumbnail,
                    score = 750
                )
            }

            val combined = (exactMatches + visualMatches + knowledgeMatches + organicMatches)
                .filter { !it.link.isNullOrBlank() }
                .distinctBy { it.link }

            if (combined.isNotEmpty()) {
                onLog("✓ SerpApi (Google Lens) found ${combined.size} candidate(s)")
            } else {
                onLog("ℹ SerpApi (Google Lens): 0 candidates found for this image probe")
            }
            combined
        } catch (e: HttpException) {
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string().orEmpty()
            if (code == 401) {
                onLog("⚠ SerpApi 401 Unauthorized: Invalid API key. Check settings.")
            } else if (code == 429) {
                onLog("⚠ SerpApi 429 Rate Limit: Monthly search limit reached.")
            } else {
                onLog("⚠ SerpApi HTTP $code Error: ${errorBody.ifBlank { e.message() }}")
            }
            emptyList()
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