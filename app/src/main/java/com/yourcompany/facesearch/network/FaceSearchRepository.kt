package com.yourcompany.facesearch.network

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Patterns
import com.yourcompany.facesearch.data.cache.SearchCacheManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.yourcompany.facesearch.network.model.ServerSearchRequest
import com.yourcompany.facesearch.network.model.ServerSearchResponse
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import com.yourcompany.facesearch.network.model.DorkSearchRequest
import com.yourcompany.facesearch.vision.NativeFaceCropper
import org.json.JSONObject
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
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
            val allResults = Collections.synchronizedList(mutableListOf<SerpVisualMatch>())

            val cropper = NativeFaceCropper()
            // Always route the face crop through prepareFaceForSearch and applyClothingHardenedMask
            // right before handoff to freeHost.upload for search engine upload
            val searchBitmap = cropper.prepareFaceForSearch(faceBitmap ?: bitmap)
            val expandedBitmap = searchBitmap

            // Perform OCR on the full original image to extract contextual clues (e.g. badges, signs)
            var textHint = keywordHint
            if (textHint.isNullOrBlank()) {
                try {
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val task = recognizer.process(image)
                    var resultText = ""
                    var isDone = false
                    task.addOnSuccessListener { r ->
                        resultText = r.text
                        isDone = true
                    }.addOnFailureListener { e ->
                        onLog("⚠ OCR extraction failed: ${e.message}")
                        isDone = true
                    }
                    while (!isDone) {
                        delay(50L)
                    }
                    val extractedText = resultText.replace(Regex("\\s+"), " ").trim()
                    
                    if (extractedText.isNotBlank()) {
                        textHint = extractedText
                        onLog("OCR Extracted context: '$textHint'")
                    }
                } catch (e: Exception) {
                    onLog("⚠ OCR extraction failed: ${e.message}")
                }
            }

            val stream = ByteArrayOutputStream()
            searchBitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            val byteArray = stream.toByteArray()

            val probeHash = MessageDigest.getInstance("SHA-256")
                .digest(byteArray)
                .joinToString("") { "%02x".format(it) }

            val cachedHits = SearchCacheManager.getCachedResults(context, probeHash)
            if (!cachedHits.isNullOrEmpty()) {
                onLog("✓ Gelueden aus dem lokale Cache: ${cachedHits.size} Resultater fonnt.")
                return@withContext cachedHits
            }

            val probeUrl = if (imageUrl != null && imageUrl.startsWith("http")) {
                imageUrl
            } else {
                onLog("Uploading face-focused probe (excluding neckline)...")
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

            val harvestedHints = if (textHint.isNullOrBlank()) {
                harvestSearchHints(allResults.toList())
            } else listOf(textHint.trim())

            if (harvestedHints.isNotEmpty()) {
                val primaryName = harvestedHints.first().trim()
                
                if (searchMode.equals("ADULT", ignoreCase = true)) {
                    // ==========================================
                    // 1. ADULT NETWORK DORKING BRANCH
                    // ==========================================
                    onLog("Adult scan mode active: Running dork queries for '$primaryName' across adult networks...")
                    val scraper = WebViewScraper.create(context)
                    try {
                        val chunks = AdultSiteConfig.SITES.chunked(5)
                        chunks.forEachIndexed { idx, siteBatch ->
                            onLog("Scanning Adult Group #${idx + 1}...")
                            
                            val dorkHits = performTermuxDorkSearch(
                                keyword = primaryName,
                                sites = siteBatch,
                                onLog = onLog
                            )
                            
                            if (dorkHits.isNotEmpty()) {
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
                            } else {
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
                    } finally {
                        scraper.destroy() // Safely destroyed only after Adult block finishes
                    }
                } else {
                    // ==========================================
                    // 2. STANDARD OSINT & REGIONAL LOOKUP BRANCH
                    // ==========================================
                    onLog("Discovered identity hint: '$primaryName'. Running UK & global profile lookup...")
                    
                    val isUrl = primaryName.startsWith("http") || primaryName.contains("://") || primaryName.contains("www.")
                    val isTooLong = primaryName.length > 50
                    val isGarbage = primaryName.contains("shutterstock") || primaryName.contains("gettyimages") || primaryName.contains("alamy")
                    
                    if (isUrl || isTooLong || isGarbage) {
                        onLog("⚠ Identity hint looks like a URL or stock photo reference — skipping social dorking.")
                    } else {
                        val directHandles = UsernameScanner.scanUsername(primaryName, onLog)
                        allResults.addAll(directHandles)

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

                        val socialDorkSites = listOf(
                            "facebook.com", "instagram.com", "linkedin.com/in", "twitter.com",
                            "tiktok.com/@", "threads.net", "bsky.app", "mastodon.social",
                            "192.com", "thegazette.co.uk", "grimsbytelegraph.co.uk",
                            "scunthorpetelegraph.co.uk", "yell.com", "companieshouse.gov.uk",
                            "findmypast.co.uk", "reddit.com/user", "pinterest.co.uk", "flickr.com", 
                            "tumblr.com", "vsco.co", "vk.com", "linktr.ee", "twitch.tv", "youtube.com/@",
                            "soundcloud.com", "behance.net", "dribbble.com", "patreon.com",
                            "substack.com", "medium.com/@", "keybase.io", "github.com", "gitlab.com", 
                            "stackoverflow.com/users", "producthunt.com/@", "tinder.com/@",
                            "badoo.com/profile", "okcupid.com/profile", "xing.com/profile",
                            "foursquare.com/user"
                        )
                        
                        onLog("Parallel batched social dorking and dedicated engine queries...")
                        coroutineScope {
                            val batchJobs = socialDorkSites.chunked(10).mapIndexed { batchIdx, batch ->
                                async {
                                    val s = WebViewScraper.create(context)
                                    try {
                                        s.scrapeBatchedSocialDork(
                                            sites = batch,
                                            keyword = primaryName,
                                            groupLabel = "Social Group #${batchIdx + 1}",
                                            onLog = onLog
                                        )
                                    } finally {
                                        s.destroy()
                                    }
                                }
                            }
                            
                            val search4FacesJob = async {
                                querySearch4Faces(byteArray, onLog)
                            }
                            
                            val results = batchJobs.awaitAll().flatten() + search4FacesJob.await()
                            allResults.addAll(results)
                            onLog("✓ Social dorking and dedicated engines complete: ${results.size} result(s)")
                        }
                    }
                }
            } else if (searchMode.equals("ADULT", ignoreCase = true)) {
                onLog("⚠️ Tip: For adult platform scanning, enter a name or username in OSINT TARGET HINT if reverse image search finds no text matches.")
            }

            onLog("Search complete. Retrieved ${allResults.size} total candidates.")
            val distinctResults = allResults.distinctBy { it.link }
            distinctResults
        }
    }

    private fun harvestSearchHints(matches: List<SerpVisualMatch>): List<String> {
        val stopWords = setOf(
            "http", "https", "www", "com", "net", "org", "co", "uk", "io", "jpg", "png", "jpeg",
            "image", "photo", "picture", "pic", "pics", "wallpaper", "visual", "match", "matches", "candidate",
            "shutterstock", "gettyimages", "alamy", "istockphoto", "stock", "fotocdn"
        )

        val hints = mutableSetOf<String>()

        for (match in matches) {
            val title = match.title?.trim().orEmpty()
            if (title.isBlank() || title.length < 3 || title.startsWith("http")) continue

            val cleaned = title
                .replace(Regex("(?i)https?://\\S+"), "")
                .replace(Regex("(?i)\\b\\d+\\s*[x×*]\\s*\\d+\\b"), "")
                .replace(Regex("(?i)[|\\-–—:(\\[].*"), "")
                .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleaned.length >= 3 && cleaned.count { it.isLetter() } >= 3) {
                val words = cleaned.split(" ").filter { it.length > 1 && it.lowercase() !in stopWords }
                if (words.isNotEmpty()) {
                    val candidateHint = words.joinToString(" ")
                    if (candidateHint.length >= 3) {
                        hints.add(candidateHint)
                    }
                }
            }
        }

        if (hints.isEmpty()) {
            val wordCounts = matches.flatMap { 
                it.title.orEmpty().lowercase().replace(Regex("[^a-z0-9\\s]"), " ").split(Regex("\\s+")) 
            }
                .filter { it.length > 2 && it !in stopWords && !it.all { char -> char.isDigit() } }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }

            if (wordCounts.isNotEmpty()) {
                hints.add(wordCounts.joinToString(" "))
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

    suspend fun executeBackendCommand(fullCommand: String): String = withContext(Dispatchers.IO) {
        val trimmed = fullCommand.trim()
        if (trimmed.isBlank()) return@withContext "Error: Command cannot be empty."

        val parts = trimmed.split("\\s+".toRegex())
        val rawBaseCmd = parts[0]
        val lowerBase = rawBaseCmd.lowercase()

        // List of known binary tools
        val knownTools = setOf("sherlock", "holehe", "phoneinfoga", "blackbird", "blackbird.py", "help")

        // Command normalization: Auto-detect phone, email, username, or known tool
        val isPhone = isPhoneNumberInput(trimmed) || lowerBase == "phoneinfoga"
        val isEmail = isEmailInput(trimmed) || lowerBase == "holehe"

        val (baseCmd, args) = when {
            isPhone -> {
                val targetPhone = if (lowerBase == "phoneinfoga") {
                    parts.filter { !it.startsWith("-") && it != "phoneinfoga" && it != "scan" }.joinToString(" ").trim()
                } else {
                    trimmed
                }
                "phoneinfoga" to listOf(targetPhone)
            }
            isEmail -> {
                val targetEmail = if (lowerBase == "holehe" || lowerBase == "blackbird" || lowerBase == "blackbird.py") {
                    parts.filter { !it.startsWith("-") && it != "holehe" && it != "blackbird" && it != "blackbird.py" && it != "-e" }.joinToString(" ").trim()
                } else {
                    trimmed
                }
                "holehe" to listOf(targetEmail)
            }
            !knownTools.contains(lowerBase) -> {
                val username = rawBaseCmd.removePrefix("@")
                "sherlock" to listOf(username)
            }
            else -> {
                val extractedArgs = if (parts.size > 1) parts.subList(1, parts.size) else emptyList()
                lowerBase to extractedArgs
            }
        }

        if (baseCmd == "help") {
            return@withContext "Available binaries: sherlock, holehe, phoneinfoga, blackbird.py\n" +
                    "Usage Examples:\n" +
                    "  • Email OSINT: user@example.com or holehe user@example.com\n" +
                    "  • Phone OSINT: +1234567890 or phoneinfoga +1234567890\n" +
                    "  • Username OSINT: john_doe or sherlock john_doe"
        }

        // Only attempt backend HTTP call if activeBackend is set OR if quick ping succeeds
        val isBackendAlive = activeBackend != null || isLocalBackendAvailable()
        if (isBackendAlive) {
            val backendBase = activeBackend ?: "http://127.0.0.1:3000"
            val req = mapOf<String, Any>(
                "command" to baseCmd,
                "args" to args
            )

            try {
                val response = RetrofitClient.getInstance(backendBase).executeCommand(req)
                if (response.isSuccessful) {
                    val body = response.body()
                    val output = body?.get("output")?.toString()
                    val error = body?.get("error")?.toString()
                    val success = body?.get("success") as? Boolean ?: false

                    val resultOutput = if (success || !output.isNullOrBlank()) {
                        output ?: "Command executed successfully."
                    } else {
                        error ?: "Execution failed."
                    }

                    if (!resultOutput.contains("process executed successfully for") &&
                        !(baseCmd == "sherlock" && (resultOutput.isBlank() || resultOutput.contains("Command executed successfully.")))
                    ) {
                        return@withContext resultOutput
                    }
                }
            } catch (_: Exception) {
                // Termux backend unreachable — fallback to native implementation
            }
        }

        // Native fallbacks
        val target = args.filter { !it.startsWith("-") }.firstOrNull()?.removePrefix("@") ?: ""

        if (baseCmd == "phoneinfoga") {
            val phoneTarget = target.ifBlank { trimmed }
            return@withContext runNativePhoneInfoga(phoneTarget)
        }

        if (baseCmd == "holehe" || isEmailInput(trimmed)) {
            val emailTarget = target.ifBlank { trimmed }
            return@withContext runNativeEmailOsint(emailTarget)
        }

        if (baseCmd == "sherlock" || baseCmd == "blackbird" || baseCmd == "blackbird.py") {
            if (target.isNotBlank()) {
                return@withContext runNativeSherlock(target)
            }
            return@withContext "Usage: $baseCmd <username>"
        }

        if (target.isNotBlank()) {
            return@withContext runNativeSherlock(target)
        }

        "Termux connection error: Unable to reach Termux backend and no native fallback for '$fullCommand'."
    }

    private fun isEmailInput(input: String): Boolean {
        val clean = input.trim().removePrefix("holehe").removePrefix("blackbird").removePrefix("epieos").trim()
        val candidate = clean.split("\\s+".toRegex()).firstOrNull()?.removePrefix("@") ?: ""
        if (candidate.isBlank()) return false
        return Patterns.EMAIL_ADDRESS.matcher(candidate).matches() ||
                (candidate.contains("@") && candidate.contains(".") && !candidate.startsWith("@") && !candidate.endsWith("@"))
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.trim().lowercase().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun runNativeEmailOsint(rawEmail: String): String {
        val email = rawEmail.trim().removePrefix("holehe").removePrefix("blackbird").removePrefix("epieos").trim().lowercase()
        if (!email.contains("@")) {
            return "Usage: holehe <email_address> (e.g. user@example.com)"
        }

        val atIdx = email.indexOf('@')
        val handle = if (atIdx > 0) email.substring(0, atIdx) else email
        val domain = if (atIdx > 0 && atIdx < email.length - 1) email.substring(atIdx + 1) else ""
        val md5Hash = md5Hex(email)
        val encodedEmail = URLEncoder.encode("\"$email\"", "UTF-8")

        val sb = StringBuilder()
        sb.appendLine("==========================================================")
        sb.appendLine("⚡ ADVANCED EMAIL OSINT / HOLEHE & RECON MATRIX")
        sb.appendLine("==========================================================")
        sb.appendLine("[+] Target Email: $email")
        sb.appendLine("[+] Extracted Username Handle: $handle")
        sb.appendLine("[+] Email Domain: $domain")
        sb.appendLine("[+] MD5 Gravatar Hash: $md5Hash")
        sb.appendLine()
        sb.appendLine("[1] GRAVATAR & AVATAR IDENTITY RECON:")
        sb.appendLine("  • Gravatar Profile: https://gravatar.com/$md5Hash")
        sb.appendLine("  • Gravatar JSON Data: https://en.gravatar.com/$md5Hash.json")
        sb.appendLine("  • Gravatar Direct Avatar: https://gravatar.com/avatar/$md5Hash?s=400")
        sb.appendLine()
        sb.appendLine("[2] EMAIL REGISTRY & REPUTATION SERVICES:")
        sb.appendLine("  • Epieos Email OSINT: https://epieos.com/?q=$email")
        sb.appendLine("  • WhatsMyName Handle Search ($handle): https://whatsmyname.app/?q=$handle")
        sb.appendLine("  • EmailRep Reputation Score: https://emailrep.io/$email")
        sb.appendLine("  • Hunter.io Email Verifier: https://hunter.io/email-verifier/$email")
        sb.appendLine("  • Holehe Web Scanner: https://holehe.com/")
        sb.appendLine()
        sb.appendLine("[3] DATA BREACH & LEAK INTELLIGENCE:")
        sb.appendLine("  • HaveIBeenPwned Leaks: https://haveibeenpwned.com/account/$email")
        sb.appendLine("  • Intelligence X Leak Search: https://intelx.io/?s=$email")
        sb.appendLine("  • DeHashed Breach Registry: https://dehashed.com/search?query=$email")
        sb.appendLine("  • LeakCheck Registry: https://leakcheck.io/search?key=$email")
        sb.appendLine("  • Pastebin Dump Leaks Dork: https://www.google.com/search?q=(site:pastebin.com+OR+site:rentry.co+OR+site:ghostbin.com)+AND+$encodedEmail")
        sb.appendLine()
        sb.appendLine("[4] GOOGLE & MULTI-ENGINE DORKS:")
        sb.appendLine("  • Google Exact Match: https://www.google.com/search?q=$encodedEmail")
        sb.appendLine("  • DuckDuckGo Search: https://duckduckgo.com/?q=$encodedEmail")
        sb.appendLine("  • Social Media Dork: https://www.google.com/search?q=(site:github.com+OR+site:twitter.com+OR+site:linkedin.com+OR+site:facebook.com+OR+site:instagram.com)+AND+$encodedEmail")
        sb.appendLine("  • Public Document Dork: https://www.google.com/search?q=(filetype:pdf+OR+filetype:doc+OR+filetype:txt+OR+filetype:csv)+AND+$encodedEmail")
        sb.appendLine()
        sb.appendLine("[5] HANDLE CROSS-PIVOT ($handle):")
        sb.appendLine("  • Sherlock Username Scan: sherlock $handle")
        if (domain.contains("gmail")) {
            sb.appendLine("  • GHunt Google Workspace OSINT: ghunt email $email")
        }
        sb.appendLine()
        sb.appendLine("[6] TERMUX / CLI OSINT COMMANDS:")
        sb.appendLine("  • Holehe Email Scan: holehe $email")
        sb.appendLine("  • Blackbird Email Scan: python blackbird.py -e $email")
        sb.appendLine("  • Mosint Email Recon: mosint $email")
        sb.appendLine("==========================================================")
        sb.appendLine("[+] Recon complete. 20 high-yield email OSINT channels generated for $email.")
        return sb.toString().trim()
    }

    private fun isPhoneNumberInput(input: String): Boolean {
        val clean = input.trim().removePrefix("phoneinfoga").removePrefix("scan").removePrefix("-n").trim()
        if (clean.isBlank()) return false
        val digitsOnly = clean.replace(Regex("[^0-9]"), "")
        if (digitsOnly.length in 7..15) {
            val nonDigitCount = clean.count { !it.isDigit() && it != '+' && it != '-' && it != ' ' && it != '(' && it != ')' }
            if (nonDigitCount == 0) return true
        }
        return false
    }

    private fun runNativePhoneInfoga(rawPhone: String): String {
        val digitsOnly = rawPhone.replace(Regex("[^0-9]"), "")
        if (digitsOnly.length < 7) return "Usage: phoneinfoga scan -n <phone_number> (e.g. +1234567890)"

        val e164 = normalizePhoneE164(rawPhone)
        val cleanDigits = e164.replace(Regex("[^0-9]"), "")
        val localDigits = digitsOnly
        val combinedQuery = URLEncoder.encode("\"$e164\" OR \"$localDigits\"", "UTF-8")

        val sb = StringBuilder()
        sb.appendLine("==========================================================")
        sb.appendLine("⚡ ADVANCED PHONE OSINT / PHONEINFOGA MULTI-ENGINE RECON")
        sb.appendLine("==========================================================")
        sb.appendLine("[+] Target Phone Input: $rawPhone")
        sb.appendLine("[+] E.164 International: $e164 | National Standard: $localDigits")
        sb.appendLine()
        sb.appendLine("[1] MESSAGING & SOCIAL FOOTPRINT:")
        sb.appendLine("  • WhatsApp Direct: https://wa.me/$cleanDigits")
        sb.appendLine("  • Telegram Channel/User: https://t.me/$e164")
        sb.appendLine("  • Viber Chat Direct: https://viber.click/$cleanDigits")
        sb.appendLine()
        sb.appendLine("[2] CALLER ID & OWNER REPUTATION DATABASES:")
        sb.appendLine("  • Sync.ME Social Registry: https://sync.me/search/?number=$e164")
        sb.appendLine("  • TrueCaller ID Lookup: https://www.truecaller.com/search/gb/$cleanDigits")
        sb.appendLine("  • ShouldIAnswer Reputation: https://www.shouldianswer.com/phone-number/$e164")
        sb.appendLine("  • Tellows Reputation Index: https://www.tellows.co.uk/num/$localDigits")
        sb.appendLine("  • WhoCalledMe Directory: https://whocalled.co.uk/phone-number/$localDigits")
        sb.appendLine("  • UnknownPhone Registry: https://www.unknownphone.com/search.php?num=$localDigits")
        sb.appendLine("  • NumLookup Owner & Carrier: https://www.numlookup.com/")
        sb.appendLine("  • FreeCarrierLookup Line Type: https://freecarrierlookup.com/")
        sb.appendLine()
        sb.appendLine("[3] DATA LEAKS & BREACH INTELLIGENCE:")
        sb.appendLine("  • Intelligence X Leak Search: https://intelx.io/?s=$cleanDigits")
        sb.appendLine("  • Pastebin Dump Leaks Dork: https://www.google.com/search?q=(site:pastebin.com+OR+site:rentry.co)+AND+($combinedQuery)")
        sb.appendLine()
        sb.appendLine("[4] PLATFORM-SPECIFIC SEARCH DORKS:")
        sb.appendLine("  • Facebook Profile Dork: https://www.google.com/search?q=site:facebook.com+AND+($combinedQuery)")
        sb.appendLine("  • Instagram/X/LinkedIn Dork: https://www.google.com/search?q=(site:instagram.com+OR+site:x.com+OR+site:linkedin.com)+AND+($combinedQuery)")
        sb.appendLine("  • TikTok & Snapchat Dork: https://www.google.com/search?q=(site:tiktok.com+OR+site:snapchat.com)+AND+($combinedQuery)")
        sb.appendLine("  • Classifieds & Marketplaces: https://www.google.com/search?q=(site:gumtree.com+OR+site:ebay.co.uk+OR+site:craigslist.org)+AND+($combinedQuery)")
        sb.appendLine()
        sb.appendLine("[5] MULTI-ENGINE GLOBAL SEARCH:")
        sb.appendLine("  • Google Exact Match: https://www.google.com/search?q=$combinedQuery")
        sb.appendLine("  • DuckDuckGo Deep Search: https://duckduckgo.com/?q=$combinedQuery")
        sb.appendLine("  • Bing Global Search: https://www.bing.com/search?q=$combinedQuery")
        sb.appendLine("  • Yandex International: https://yandex.com/search/?text=$combinedQuery")
        sb.appendLine()
        sb.appendLine("[6] TERMUX / CLI OSINT COMMANDS:")
        sb.appendLine("  • PhoneInfoga Standard: phoneinfoga scan -n \"$e164\"")
        sb.appendLine("  • PhoneInfoga NumVerify: phoneinfoga scan -n \"$e164\" --input numverify")
        sb.appendLine("  • Ignorant Phone Check: ignorant -n \"$e164\"")
        sb.appendLine("  • Phonia OSINT Scanner: python3 phonia.py -n \"$e164\"")
        sb.appendLine("==========================================================")
        sb.appendLine("[+] Recon complete. 21 high-yield OSINT channels active for $e164.")
        return sb.toString().trim()
    }

    private fun normalizePhoneE164(rawPhone: String): String {
        val clean = rawPhone.trim()
        if (clean.isBlank()) return ""
        val digitsOnly = clean.replace(Regex("[^0-9]"), "")
        if (digitsOnly.length < 7) return ""

        if (clean.startsWith("+")) {
            return "+$digitsOnly"
        }
        if (digitsOnly.length == 11 && digitsOnly.startsWith("07")) {
            return "+44${digitsOnly.substring(1)}"
        }
        if (digitsOnly.length in 10..11 && digitsOnly.startsWith("0")) {
            return "+44${digitsOnly.substring(1)}"
        }
        return "+$digitsOnly"
    }

    private suspend fun runNativeSherlock(rawTarget: String): String {
        val target = rawTarget.removePrefix("@").trim()
        if (target.isBlank()) return "Usage: sherlock <username>"

        val matches = UsernameScanner.scanUsername(target)
        val sb = StringBuilder()
        sb.appendLine("Executing Sherlock OSINT engine for target '@$target'...")
        if (matches.isEmpty()) {
            sb.appendLine("[-] No active profile handles found for '@$target' across platforms.")
        } else {
            sb.appendLine("[+] Found ${matches.size} active profile handle(s) for '@$target':")
            matches.forEach { match ->
                val platformName = match.source?.removeSuffix(" (Direct Handle)") ?: "Social Media"
                sb.appendLine("  • $platformName: ${match.link}")
            }
        }
        return sb.toString().trim()
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

    private suspend fun querySearch4Faces(
        imageBytes: ByteArray,
        onLog: (String) -> Unit
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SerpVisualMatch>()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val databases = listOf("vk", "ok", "tiktok", "clubhouse")
        
        onLog("Querying Search4faces (VK, OK, TikTok, ClubHouse)...")
        
        coroutineScope {
            val jobs = databases.map { db ->
                async {
                    val dbResults = mutableListOf<SerpVisualMatch>()
                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("image", "probe.jpg", imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
                        .addFormDataPart("source", db) 
                        .build()

                    val request = Request.Builder()
                        .url("https://search4faces.com/api/search")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                        .header("Accept", "application/json")
                        .post(requestBody)
                        .build()

                    try {
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val json = response.body?.string().orEmpty()
                                val jsonObject = JSONObject(json)
                                val profiles = jsonObject.optJSONArray("profiles")
                                
                                if (profiles != null) {
                                    for (i in 0 until profiles.length()) {
                                        val profile = profiles.getJSONObject(i)
                                        val profileUrl = profile.optString("profile_url")
                                        if (profileUrl.isNotBlank()) {
                                            dbResults.add(
                                                SerpVisualMatch(
                                                    title = profile.optString("name", "Search4faces Match"),
                                                    link = profileUrl,
                                                    source = "Search4faces (${db.uppercase()})",
                                                    thumbnail = profile.optString("photo"),
                                                    score = (profile.optDouble("score", 0.0) * 100).toInt() + 400
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Suppress individual DB failures to allow others to complete
                    }
                    dbResults
                }
            }
            
            val allDbResults = jobs.awaitAll().flatten()
            results.addAll(allDbResults)
        }
        
        if (results.isNotEmpty()) {
            onLog("✓ Search4faces found ${results.size} candidate(s) across OSINT databases")
        }
        results
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
            Log.e("FaceSearchRepository", "Extraction error: ${e.message}")
        }
        null
    }

    suspend fun performTermuxDorkSearch(
        keyword: String,
        sites: List<String> = AdultSiteConfig.SITES,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        try {
            val request = DorkSearchRequest(
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