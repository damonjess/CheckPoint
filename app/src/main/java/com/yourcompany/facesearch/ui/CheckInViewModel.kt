package com.yourcompany.facesearch.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.*
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.imageLoader
import coil3.toBitmap
import coil3.request.allowHardware
import com.yourcompany.facesearch.network.FaceSearchRepository
import com.yourcompany.facesearch.network.FreeImageHost
import com.yourcompany.facesearch.network.LocalServer
import com.yourcompany.facesearch.network.RetrofitClient
import com.yourcompany.facesearch.network.SerpVisualMatch
import com.yourcompany.facesearch.network.SocialMediaDetector
import com.yourcompany.facesearch.network.ThumbnailUtils
import com.yourcompany.facesearch.network.model.Match
import com.yourcompany.facesearch.ui.models.WebMatchDisplay
import com.yourcompany.facesearch.vision.FaceDetectionResult
import com.yourcompany.facesearch.vision.FaceDetectorHelper
import com.yourcompany.facesearch.vision.FaceEmbedder
import com.yourcompany.facesearch.vision.FaceVerifier
import com.yourcompany.facesearch.vision.FreeFaceSearchHelper
import com.yourcompany.facesearch.vision.NativeFaceCropper
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class CheckInViewModel(
    application: Application
) : AndroidViewModel(application) {

    private companion object {
        const val MAX_CANDIDATES_TO_VERIFY = 24
        const val VERIFIED_MATCH_BASE_SCORE = 5_000
        const val VERIFIED_MATCH_SIMILARITY_WEIGHT = 1_000
        const val LIKELY_MATCH_BASE_SCORE = 3_000
        const val LIKELY_MATCH_SIMILARITY_WEIGHT = 1_000
        const val LIKELY_MATCH_THRESHOLD = 0.58f
    }

    var isSearching by mutableStateOf(false)
        private set

    private val nativeFaceCropper = NativeFaceCropper()
    private val captureDetector = FaceDetectorHelper(application)
    private val faceSearchRepository = FaceSearchRepository(getApplication())
    private val faceEmbedder = FaceEmbedder(application)
    private val faceVerifier = FaceVerifier(application)
    private val freeImageHost = FreeImageHost()
    private val freeSearchHelper = FreeFaceSearchHelper(getApplication())
    var uiState by mutableStateOf<CheckInUiState>(CheckInUiState.Idle)
        private set

    var capturedBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var targetHint by mutableStateOf("")

    var sensitivity by mutableFloatStateOf(0.58f)
    var fullFaceMode by mutableStateOf(false)

    private val currentLogs = mutableListOf<String>()
    private var latestSourceEmbedding: FloatArray? = null

    private fun addLog(msg: String) {
        currentLogs.add(msg)
        Log.e("CheckIn", "CONSOLE_LOG: $msg")
        uiState = when (val previous = uiState) {
            is CheckInUiState.Success -> previous.copy(logs = currentLogs.toList())
            is CheckInUiState.NoMatch -> previous.copy(logs = currentLogs.toList())
            is CheckInUiState.Error -> previous.copy(logs = currentLogs.toList())
            is CheckInUiState.NoFaceDetected -> previous.copy(logs = currentLogs.toList())
            is CheckInUiState.Loading -> previous.copy(logs = currentLogs.toList())
            else -> CheckInUiState.Loading(0.3f, currentLogs.toList())
        }
    }

    fun onTargetHintChange(newHint: String) {
        targetHint = newHint
    }

    var searchMode by mutableStateOf(SearchMode.PRECISION)
    var debugMode by mutableStateOf(false)

    fun onPhotoCaptured(bitmap: Bitmap) {
        Log.e("CheckIn", "!!! CRITICAL LOG !!! onPhotoCaptured triggered. Mode: ${searchMode.name}")
        if (isSearching) {
            Log.e("CheckIn", "Already searching, ignoring capture.")
            return
        }
        capturedBitmap = bitmap
        
        isSearching = true
        viewModelScope.launch {
            try {
                // Keep an unmodified, size-normalized copy for reverse-image search.
                // The aligned face crop is used only for local verification.
                val searchPhoto = normalizeReverseImageProbe(bitmap)
                LocalServer.stageProbe(searchPhoto, isFaceCrop = false)

                when (val detection = captureDetector.detectAndCropFace(searchPhoto)) {
                    is FaceDetectionResult.Success -> {
                        val quality = detection.quality
                        addLog(
                            "Capture accepted: ${quality.faceWidthPx}px face, " +
                                "brightness ${quality.meanLuminance.toInt()}, " +
                                "sharpness ${quality.sharpness.toInt()}."
                        )
                        LocalServer.stageProbe(searchPhoto, isFaceCrop = true)
                        performSearchPipeline(searchPhoto, detection.croppedFace, searchPhoto)
                    }
                    is FaceDetectionResult.MultipleFacesFound -> {
                        uiState = CheckInUiState.NoFaceDetected(
                            listOf("Use a photo with only one visible face before searching.")
                        )
                    }
                    is FaceDetectionResult.PoorQuality -> {
                        uiState = CheckInUiState.NoFaceDetected(listOf(detection.reason))
                    }
                    FaceDetectionResult.NoFaceFound -> {
                        Log.e("CheckIn", "No face detected in capture.")
                        uiState = CheckInUiState.NoFaceDetected(
                            listOf("No face detected. Use even lighting, face the camera, and try again.")
                        )
                    }
                    is FaceDetectionResult.Error -> {
                        uiState = CheckInUiState.Error(
                            "Face detection could not complete. Please take another photo."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("CheckIn", "Auto-search failed", e)
                uiState = CheckInUiState.Error("Search failed: ${e.message}")
            } finally {
                isSearching = false
            }
        }
    }

    fun onRetry() {
        uiState = CheckInUiState.Idle
        capturedBitmap = null
        isSearching = false
    }

    fun loadHighRes(match: WebMatchDisplay) {
        val currentState = uiState
        if (currentState !is CheckInUiState.Success) return

        // Set loading state for this specific match
        uiState = currentState.copy(
            matches = currentState.matches.map { 
                if (it.profileUrl == match.profileUrl) it.copy(isHighResLoading = true) else it 
            }
        )

        viewModelScope.launch {
            try {
                // Increased timeout for human-like scraping on Termux
                val highResUrl = withTimeoutOrNull(60000L) {
                    val fromBackend = faceSearchRepository.extractHighResMedia(match.profileUrl)
                    if (fromBackend != null) return@withTimeoutOrNull fromBackend
                    
                    // Fallback to metadata extraction (og:image) if backend extraction fails
                    faceSearchRepository.extractMetadataThumbnail(match.profileUrl)
                }

                val highResBitmap = highResUrl?.let { loadThumbnailBitmap(it) }
                val highResSimilarity = if (
                    highResBitmap != null &&
                    latestSourceEmbedding != null &&
                    captureDetector.hasSingleCandidateFace(highResBitmap)
                ) {
                    faceVerifier.calculateSimilarity(highResBitmap, latestSourceEmbedding)
                } else {
                    null
                }

                val finalState = uiState
                if (finalState is CheckInUiState.Success) {
                    uiState = finalState.copy(
                        matches = finalState.matches.map { m ->
                            if (m.profileUrl == match.profileUrl) {
                                when {
                                    highResSimilarity != null && highResSimilarity >= FaceVerifier.VERIFICATION_THRESHOLD -> {
                                        m.copy(
                                            imageUrl = highResUrl ?: m.imageUrl,
                                            score = VERIFIED_MATCH_BASE_SCORE + (highResSimilarity * VERIFIED_MATCH_SIMILARITY_WEIGHT).toInt(),
                                            confidence = calculateConfidence(VERIFIED_MATCH_BASE_SCORE + (highResSimilarity * VERIFIED_MATCH_SIMILARITY_WEIGHT).toInt()),
                                            isFaceVerified = true,
                                            isLikelyFaceMatch = false,
                                            isHighResLoading = false
                                        )
                                    }
                                    highResSimilarity != null && highResSimilarity >= LIKELY_MATCH_THRESHOLD -> {
                                        m.copy(
                                            imageUrl = highResUrl ?: m.imageUrl,
                                            score = LIKELY_MATCH_BASE_SCORE + (highResSimilarity * LIKELY_MATCH_SIMILARITY_WEIGHT).toInt(),
                                            confidence = calculatePossibleConfidence(LIKELY_MATCH_BASE_SCORE + (highResSimilarity * LIKELY_MATCH_SIMILARITY_WEIGHT).toInt()),
                                            isFaceVerified = false,
                                            isLikelyFaceMatch = true,
                                            isHighResLoading = false
                                        )
                                    }
                                    else -> m.copy(
                                        imageUrl = highResUrl ?: m.imageUrl,
                                        isHighResLoading = false
                                    )
                                }
                            } else m
                        }
                    )
                    when {
                        highResSimilarity != null && highResSimilarity >= FaceVerifier.VERIFICATION_THRESHOLD ->
                            addLog("High-resolution recheck promoted one candidate to a locally verified match.")
                        highResSimilarity != null && highResSimilarity >= LIKELY_MATCH_THRESHOLD ->
                            addLog("High-resolution recheck found a possible face match. Manual review is still required.")
                    }
                }
            } catch (e: Exception) {
                Log.e("CheckIn", "Manual extraction failed", e)
                val finalState = uiState
                if (finalState is CheckInUiState.Success) {
                    uiState = finalState.copy(
                        matches = finalState.matches.map { m ->
                            if (m.profileUrl == match.profileUrl) m.copy(isHighResLoading = false) else m
                        }
                    )
                }
            }
        }
    }

    fun onConfirmFreeSearch(bitmap: Bitmap) {
        freeSearchHelper.launchDirectSearch(bitmap, targetHint)
    }

    fun onGoogleLensOnlySearch(bitmap: Bitmap) {
        val uri = freeSearchHelper.saveToCache(bitmap)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            `package` = "com.google.android.googlequicksearchbox"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            val fallback = "https://lens.google.com/upload"
            getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun openBlockedEnginesInBrowser(imageUrl: String, blocked: List<String>) {
        val app = getApplication<Application>()
        
        blocked.forEachIndexed { index, engine ->
            val url = when (engine) {
                "Google Master" -> "https://lens.google.com/uploadbyurl?url=${Uri.encode(imageUrl)}"
                "Yandex" -> "https://yandex.com/images/search?rpt=imageview&url=${Uri.encode(imageUrl)}"
                "Baidu" -> "https://graph.baidu.com/pcpage/index?tpl_from=pc&image=${Uri.encode(imageUrl)}"
                else -> null
            }
            
            if (url != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        // Try Chrome specifically first
                        val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            `package` = "com.android.chrome"
                        }
                        app.startActivity(chromeIntent)
                        addLog("🌐 Opened $engine in Chrome")
                    } catch (e: Exception) {
                        // Fallback to any browser
                        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        app.startActivity(fallback)
                        addLog("🌐 Opened $engine in browser")
                    }
                }, index * 2500L) // Stagger by 2.5s so tabs don't overwhelm
            }
        }
    }

    private suspend fun performSearchPipeline(bitmap: Bitmap, faceBitmap: Bitmap, probeBitmap: Bitmap) {
        Log.e("CheckIn", "!!! CRITICAL LOG !!! Starting performSearchPipeline")
        currentLogs.clear()
        addLog("Initializing consent-based reverse-image search...")
        val effectiveSearchMode = SearchMode.PRECISION

        uiState = CheckInUiState.Loading(0.1f, currentLogs.toList())

        // Try to upload probe to free hosting
        addLog("Uploading probe to free hosting...")
        var publicUrl = freeImageHost.upload(probeBitmap, ::addLog)

        if (publicUrl == null) {
            addLog("✗ All free hosts failed. Falling back to local probe.")
            try {
                LocalServer.start(getApplication())
                publicUrl = "http://127.0.0.1:8080/face.jpg"
                addLog("✓ Using local probe: $publicUrl")
            } catch (e: Exception) {
                addLog("✗ Failed to start LocalServer: ${e.message}")
                uiState = CheckInUiState.Error("No free image host available and local probe failed.", currentLogs.toList())
                return
            }
        } else {
            addLog("✓ Probe hosted: ${publicUrl.take(35)}...")
        }

        // Upgrade 5: EXIF Metadata Extraction
        val exifHints = extractExifHints(bitmap)
        val combinedHint = listOf(targetHint, exifHints)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { null }

        if (exifHints.isNotBlank()) {
            addLog("EXIF hints found: $exifHints")
        }

        // Use the optional local helper for the same supported precision flow on every runtime.
        var useTermux = true

        if (useTermux) {
            addLog("Probing for local Termux backend...")
            // Increased timeout for parallel discovery
            val available = withTimeoutOrNull(10000L) { faceSearchRepository.isLocalBackendAvailable() } ?: false
            if (!available) {
                addLog("No local Termux server detected; falling back to in-app WebView scanning.")
                useTermux = false
            } else {
                addLog("Local Termux backend detected; offloading heavy engines.")
            }
        }

        // Execute searches in parallel
        val allRawResults = java.util.Collections.synchronizedList(mutableListOf<SerpVisualMatch>())
        val blockedEngines = linkedSetOf<String>()
        // If we're using the local probe URL and there's no network, call the local server's face-search endpoint
        fun isNetworkAvailable(): Boolean {
            try {
                val cm = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
                return cap.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } catch (e: Exception) { return false }
        }

        if (publicUrl != null && publicUrl.startsWith("http://127.0.0.1") && !isNetworkAvailable()) {
            addLog("No network detected — running local offline analysis via LocalServer...")
            try {
                // Post face bytes to local server endpoint
                val bytes = ByteArrayOutputStream().apply { faceBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, this) }.toByteArray()
                val client = okhttp3.OkHttpClient.Builder().build()
                val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", "face.jpg", bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
                    .build()
                val req = okhttp3.Request.Builder().url("http://127.0.0.1:8080/api/v1/face-search").post(body).build()
                client.newCall(req).execute().use {
                    addLog("Local image quality analysis completed. Online reverse-image results require an internet connection.")
                }
            } catch (e: Exception) {
                addLog("⚠ LocalServer offline analysis failed: ${e.message}")
            }
            uiState = CheckInUiState.NoMatch(
                logs = currentLogs.toList(),
                message = "Your photo was checked locally, but reverse-image search needs an internet connection.",
                hasAccessChallenge = false
            )
            return
        }
        coroutineScope {
            val termuxDeferred = async {
                if (!useTermux) return@async null
                try {
                    faceSearchRepository.performLocalServerSearch(
                        bitmap = bitmap,
                        faceBitmap = faceBitmap,
                        keywordHint = combinedHint,
                        imageUrl = publicUrl,
                        searchMode = effectiveSearchMode.name,
                        onLog = ::addLog
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // Don't log cancellation as an error
                } catch (e: Exception) {
                    addLog("⚠ Termux call failed: ${e.message}")
                    null
                }
            }

            val webDeferred = async {
                delay(500)
                try {
                    faceSearchRepository.performFaceSearch(
                        bitmap = bitmap,
                        faceBitmap = faceBitmap,
                        keywordHint = combinedHint,
                        imageUrl = publicUrl,
                        deepCrawl = false,
                        searchMode = effectiveSearchMode.name,
                        skipVisualEngines = false,
                        onLog = ::addLog
                    )
                } catch (e: Exception) {
                    addLog("⚠ WebView error: ${e.message}")
                    emptyList<SerpVisualMatch>()
                }
            }

            if (useTermux) {
                val response = try { 
                    withTimeoutOrNull(120000L) { termuxDeferred.await() } 
                } catch (e: kotlinx.coroutines.CancellationException) { 
                    throw e 
                } catch (e: Exception) { 
                    addLog("⚠ Termux call failed: ${e.message}")
                    null 
                }
                
                if (response == null) {
                    addLog("⚠ Termux call timed out; falling back to in-app WebView.")
                    termuxDeferred.cancel()
                    val webResults = try { webDeferred.await() } catch (_: kotlinx.coroutines.CancellationException) { emptyList<SerpVisualMatch>() }
                    if (webResults.isNotEmpty()) {
                        allRawResults.addAll(webResults)
                    }
                    return@coroutineScope
                }
                
                if (response.success) {
                    val total = response.matches?.size ?: 0
                    val blocked = response.meta?.blockedEngines ?: emptyList()
                    val engines = response.meta?.engines?.keys?.joinToString(", ") ?: "Multiple"
                    
                    addLog("✓ Termux SUCCESS: $total matches via $engines")
                    
                    if (blocked.isNotEmpty()) {
                        blockedEngines.addAll(blocked)
                        addLog("${blocked.joinToString()} requested an access challenge. Use the in-app 'Open in Lens' action if you choose to continue manually.")
                    }
                    
                    if (total == 0) {
                        addLog("Termux returned no usable candidates; switching to the in-app visual-search flow.")
                        val webResults = try {
                            webDeferred.await()
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            emptyList<SerpVisualMatch>()
                        }
                        if (webResults.isNotEmpty()) {
                            allRawResults.addAll(webResults)
                        }
                        return@coroutineScope
                    }

                    response.matches?.let { matches ->
                        val newMatches = matches.map {
                            SerpVisualMatch(title = it.title, link = it.link, source = it.source, thumbnail = it.thumbnail, score = it.score)
                        }
                        allRawResults.addAll(newMatches)
                    }
                } else {
                    if (response.error != null) addLog("⚠ Termux error: ${response.error}")
                    val webResults = try { webDeferred.await() } catch (_: kotlinx.coroutines.CancellationException) { emptyList<SerpVisualMatch>() }
                    if (webResults.isNotEmpty()) {
                        allRawResults.addAll(webResults)
                    }
                }
            } else {
                val webResults = try { webDeferred.await() } catch (_: kotlinx.coroutines.CancellationException) { emptyList<SerpVisualMatch>() }
                if (webResults.isNotEmpty()) {
                    allRawResults.addAll(webResults)
                }
            }
        }

        if (allRawResults.isEmpty()) {
            val message = if (blockedEngines.isNotEmpty()) {
                "${blockedEngines.joinToString()} requested an access check and returned no candidates. Open your photo in Lens to continue manually."
            } else {
                "No visual candidates were returned by the available search providers."
            }
            uiState = CheckInUiState.NoMatch(
                logs = currentLogs.toList(),
                message = message,
                hasAccessChallenge = blockedEngines.isNotEmpty()
            )
            return
        }

        val candidates = prioritizeCandidates(allRawResults)
        addLog("Checking ${candidates.size} best visual candidate(s) for a visible face…")
        val review = reviewCandidates(candidates, faceBitmap)
        val verifiedMatches = review.verifiedMatches
        val likelyMatches = review.likelyMatches
        val unverifiedLeads = review.faceBearingLeads

        if (review.excludedNoFace > 0) {
            addLog("Excluded ${review.excludedNoFace} product, body-only, group, or no-face thumbnail(s).")
        }

        if (verifiedMatches.isEmpty() && likelyMatches.isEmpty() && unverifiedLeads.isEmpty()) {
            addLog("No candidate with one visible face remained after local filtering.")
            uiState = CheckInUiState.NoMatch(
                logs = currentLogs.toList(),
                message = "Visual search returned images, but they were products, body-only images, groups, or thumbnails without a clear face. They were not shown as face-search leads.",
                hasAccessChallenge = blockedEngines.isNotEmpty()
            )
            return
        }

        if (verifiedMatches.isEmpty() && likelyMatches.isEmpty()) {
            addLog("No locally verified or possible face match was found. Showing ${unverifiedLeads.size} face-bearing visual lead(s).")
            updateResultsLive(unverifiedLeads)
            return
        }

        updateResultsLive(verifiedMatches + likelyMatches + unverifiedLeads)
        addLog("Showing ${verifiedMatches.size} verified, ${likelyMatches.size} possible face match(es), and ${unverifiedLeads.size} face-bearing visual lead(s).")
    }

    private fun updateResultsLive(newResults: List<SerpVisualMatch>) {
        Log.e("CheckIn", "CONSOLE_LOG: updateResultsLive called with ${newResults.size} new results")
        val currentState = uiState
        val existingMatches = if (currentState is CheckInUiState.Success) {
            currentState.matches
        } else {
            emptyList()
        }

        val allMatches = (existingMatches.map { it.profileUrl to it } + 
                          newResults.map { mapToDisplay(it) }.map { it.profileUrl to it })
            .toMap().values.toList()
            .sortedByDescending { it.score }

        uiState = CheckInUiState.Success(
            matches = allMatches,
            logs = currentLogs.toList()
        )
        // Ensure scanning state is cleared when results are displayed
        isSearching = false
        
        // Start/restart background verification for the combined list
        // Note: we don't use the full bitmap here to save memory, just the results
        // The background verification loop in performSearchPipeline was launching separate jobs,
        // here we just ensure verification eventually runs.
    }

    private fun prioritizeCandidates(results: List<SerpVisualMatch>): List<SerpVisualMatch> {
        val socialHosts = listOf(
            "instagram.com", "facebook.com", "linkedin.com", "x.com", "twitter.com",
            "tiktok.com", "youtube.com", "reddit.com"
        )
        return results.asSequence()
            .filter { !it.link.isNullOrBlank() }
            .filterNot(::isLikelyProductResult)
            .distinctBy { match -> ThumbnailUtils.canonicalKey(match.thumbnail) ?: match.link }
            .sortedByDescending { match ->
                val thumbnail = ThumbnailUtils.normalize(match.thumbnail)
                val hasUsableThumbnail = thumbnail != null &&
                    !thumbnail.contains("placeholder", ignoreCase = true) &&
                    !thumbnail.contains("default", ignoreCase = true)
                val isSocialProfile = socialHosts.any { host -> match.link?.contains(host, ignoreCase = true) == true }
                (if (hasUsableThumbnail) 1_000 else 0) +
                    (if (isSocialProfile) 300 else 0) +
                    match.score.coerceAtMost(999)
            }
            .take(MAX_CANDIDATES_TO_VERIFY)
            .toList()
    }

    private fun isLikelyProductResult(match: SerpVisualMatch): Boolean {
        val productTerms = listOf(
            "shirt", "t-shirt", "tshirt", "tee", "clothing", "apparel", "menswear",
            "womenswear", "outfit", "size", "fabric", "shop", "store", "product", "buy",
            "sale", "amazon", "ebay", "etsy", "walmart", "shopify"
        )
        val metadata = listOfNotNull(match.title, match.link, match.source)
            .joinToString(" ")
            .lowercase(Locale.US)
        return productTerms.any { term ->
            Regex("(?<![a-z0-9])${Regex.escape(term)}(?![a-z0-9])")
                .containsMatchIn(metadata)
        }
    }

    private fun mapToDisplay(match: SerpVisualMatch): WebMatchDisplay {
        val socialDomains = listOf(
            "facebook.com", "instagram.com", "linkedin.com", "twitter.com",
            "x.com", "tiktok.com", "youtube.com", "reddit.com"
        )
        val isSocial = socialDomains.any { domain -> match.link?.contains(domain) == true }

        val urlUsername = match.link?.let { WebMatchDisplay.extractUsernameFromUrl(it) }
        
        var cleanTitle = match.title ?: "Visual Match"
        
        // Only trust URL username if the link actually points to a profile page
        val isLikelyProfileUrl = match.link?.let { link ->
            val lower = link.lowercase()
            lower.contains("/user/") || lower.contains("/in/") || lower.contains("/@") ||
            (lower.contains("instagram.com/") && !lower.contains("/p/")) ||
            (lower.contains("facebook.com/") && !lower.contains("/pages/") && !lower.contains("/groups/")) ||
            lower.contains("twitter.com/") || lower.contains("x.com/") ||
            lower.contains("tiktok.com/@") || lower.contains("youtube.com/@") ||
            lower.contains("reddit.com/user/") || lower.contains("reddit.com/u/")
        } ?: false

        if (urlUsername != null && isLikelyProfileUrl && (
                cleanTitle.contains("match", ignoreCase = true) 
                || cleanTitle.length < 4 
                || cleanTitle.contains("facebook", ignoreCase = true)
                || cleanTitle.contains("instagram", ignoreCase = true)
                || cleanTitle.contains("reddit", ignoreCase = true)
            )) {
            cleanTitle = urlUsername.replaceFirstChar { it.titlecase(Locale.US) }
        } else if (cleanTitle.contains(Regex("^[a-zA-Z0-9-]+\\.[a-z]{2,}$")) || cleanTitle == "Visual Match") {
            val uri = try { Uri.parse(match.link) } catch(e: Exception) { null }
            val pathSegments = uri?.pathSegments
            if (pathSegments?.isNotEmpty() == true) {
                val fromUrl = pathSegments.last().replace("-", " ").replace("_", " ")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                if (fromUrl.length > 2) cleanTitle = fromUrl
            }
        }
        
        cleanTitle = cleanTitle.replace(Regex("#\\w+"), "").trim()

        // Detect suspicious thumbnails (very small URLs often indicate bad crops)
        val thumb = ThumbnailUtils.normalize(match.thumbnail)
        val hasGoodThumbnail = thumb != null && 
            !thumb.contains("thumbnail") && 
            !thumb.contains("preview") && 
            thumb.length > 20

        val isVerified = match.score >= VERIFIED_MATCH_BASE_SCORE
        val isLikely = !isVerified && match.score >= LIKELY_MATCH_BASE_SCORE
        val sourceLabel = when {
            isVerified -> "✓ ${match.source}"
            isLikely -> "≈ ${match.source}"
            else -> match.source
        }

        return WebMatchDisplay(
            name = cleanTitle,
            source = sourceLabel ?: "Free Engine",
            profileUrl = match.link ?: "",
            score = match.score,
            imageUrl = thumb,
            isSocial = isSocial,
            confidence = when {
                isVerified -> calculateConfidence(match.score)
                isLikely -> calculatePossibleConfidence(match.score)
                else -> 0f
            },
            isFaceVerified = isVerified,
            isLikelyFaceMatch = isLikely,
            isHighResLoading = (isVerified || isLikely) && !hasGoodThumbnail && isSocial
        )
    }

    private fun calculatePossibleConfidence(score: Int): Float =
        (0.45f + ((score - LIKELY_MATCH_BASE_SCORE) / LIKELY_MATCH_SIMILARITY_WEIGHT.toFloat()) * 0.18f)
            .coerceIn(0.45f, 0.63f)

    private fun calculateConfidence(score: Int): Float {
        return when {
            score >= 8000 -> 1.0f
            score >= 5000 -> 0.85f + ((score - 5000) / 20000f)
            score >= 1000 -> 0.60f + ((score - 1000) / 10000f)
            score >= 300 -> 0.15f + ((score - 300) / 2000f)
            else -> (score.toFloat() / 2000f).coerceIn(0.07f, 0.14f)
        }
    }

    private data class CandidateReview(
        val verifiedMatches: List<SerpVisualMatch>,
        val likelyMatches: List<SerpVisualMatch>,
        val faceBearingLeads: List<SerpVisualMatch>,
        val excludedNoFace: Int
    )

    /**
     * Reviews each visual candidate once. A candidate can only reach the lead
     * screen when ML Kit detects exactly one sufficiently visible face. Product
     * shots, torso images, groups, icons, and thumbnails without a face are
     * discarded before display. A face-bearing lead is upgraded only when the
     * embedding comparison also passes the local verification threshold.
     */
    private suspend fun reviewCandidates(
        results: List<SerpVisualMatch>,
        sourceFaceBitmap: Bitmap
    ): CandidateReview {
        val sourceEmbedding = faceEmbedder.getEmbedding(sourceFaceBitmap)
            ?: return CandidateReview(emptyList(), emptyList(), emptyList(), results.size)
        latestSourceEmbedding = sourceEmbedding
        val verified = mutableListOf<SerpVisualMatch>()
        val likely = mutableListOf<SerpVisualMatch>()
        val faceBearingLeads = mutableListOf<SerpVisualMatch>()
        var excludedNoFace = 0

        for (match in results) {
            try {
                val thumbnailUrl = match.thumbnail
                    ?: match.link?.let { faceSearchRepository.extractMetadataThumbnail(it) }
                if (thumbnailUrl == null) {
                    excludedNoFace++
                    continue
                }
                val thumbnail = loadThumbnailBitmap(thumbnailUrl)
                if (thumbnail == null || !captureDetector.hasSingleCandidateFace(thumbnail)) {
                    excludedNoFace++
                    continue
                }

                val faceBearingMatch = match.copy(thumbnail = thumbnailUrl)
                val similarity = faceVerifier.calculateSimilarity(thumbnail, sourceEmbedding)
                when {
                    similarity != null && similarity >= FaceVerifier.VERIFICATION_THRESHOLD -> {
                        verified += faceBearingMatch.copy(
                            score = VERIFIED_MATCH_BASE_SCORE +
                                (similarity * VERIFIED_MATCH_SIMILARITY_WEIGHT).toInt()
                        )
                    }
                    similarity != null && similarity >= LIKELY_MATCH_THRESHOLD -> {
                        likely += faceBearingMatch.copy(
                            score = LIKELY_MATCH_BASE_SCORE +
                                (similarity * LIKELY_MATCH_SIMILARITY_WEIGHT).toInt()
                        )
                    }
                    else -> faceBearingLeads += faceBearingMatch
                }
            } catch (error: Exception) {
                excludedNoFace++
                Log.w("CheckIn", "Candidate review failed for ${match.link}", error)
            }
        }
        return CandidateReview(
            verifiedMatches = verified.sortedByDescending { it.score },
            likelyMatches = likely.sortedByDescending { it.score },
            faceBearingLeads = faceBearingLeads.sortedByDescending { it.score },
            excludedNoFace = excludedNoFace
        )
    }
    private fun extractExifHints(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        val bytes = stream.toByteArray()
        
        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val latLong = exif.latLong
            
            val hints = mutableListOf<String>()
            
            if (latLong != null && latLong.size >= 2) {
                // Round to city-level precision
                val lat = String.format(Locale.US, "%.1f", latLong[0])
                val lon = String.format(Locale.US, "%.1f", latLong[1])
                hints.add("$lat $lon")
            }
            
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
                if (it.length >= 4) hints.add(it.take(4))
            }
            
            exif.getAttribute(ExifInterface.TAG_MAKE)?.let { hints.add(it) }
            
            hints.joinToString(" ")
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Creates a software bitmap capped at a practical upload size. It preserves
     * the captured photograph and does not synthesize, mirror, or distort it.
     */
    private fun normalizeReverseImageProbe(source: Bitmap): Bitmap {
        val safeSource = if (source.config == Bitmap.Config.HARDWARE || source.config == null) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }
        val longestEdge = maxOf(safeSource.width, safeSource.height)
        if (longestEdge <= 1600) return safeSource
        val scale = 1600f / longestEdge.toFloat()
        return Bitmap.createScaledBitmap(
            safeSource,
            (safeSource.width * scale).toInt().coerceAtLeast(1),
            (safeSource.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private suspend fun loadThumbnailBitmap(url: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        try {
            val request = ImageRequest.Builder(getApplication())
                .data(url)
                .allowHardware(false) 
                .build()
            val result = getApplication<Application>().imageLoader.execute(request)
            result.image?.toBitmap()
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        faceVerifier.close()
        faceEmbedder.close()
        captureDetector.release()
        nativeFaceCropper.release()
    }
}
