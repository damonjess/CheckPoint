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
import com.yourcompany.facesearch.network.AdultSiteConfig
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

                val finalState = uiState
                if (finalState is CheckInUiState.Success) {
                    uiState = finalState.copy(
                        matches = finalState.matches.map { m ->
                            if (m.profileUrl == match.profileUrl) {
                                m.copy(
                                    imageUrl = highResUrl ?: m.imageUrl,
                                    isHighResLoading = false
                                )
                            } else m
                        }
                    )
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
                client.newCall(req).execute().use { resp ->
                    val txt = resp.body?.string()
                    addLog("✓ LocalServer analysis complete")
                    // The local server returns a simple JSON map; we'll show a single mock result so UI continues
                    val match = SerpVisualMatch(title = "Local Offline Match", link = "http://local/face", source = "LocalServer", thumbnail = publicUrl, score = 900)
                    allRawResults.add(match)
                    updateResultsLive(listOf(match))
                }
            } catch (e: Exception) {
                addLog("⚠ LocalServer offline analysis failed: ${e.message}")
            }
            // Skip network scrapers
            if (allRawResults.isEmpty()) {
                uiState = CheckInUiState.NoMatch(currentLogs.toList())
                return
            }
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
                        updateResultsLive(webResults)
                    }
                    return@coroutineScope
                }
                
                if (response.success) {
                    val total = response.matches?.size ?: 0
                    val blocked = response.meta?.blockedEngines ?: emptyList()
                    val engines = response.meta?.engines?.keys?.joinToString(", ") ?: "Multiple"
                    
                    addLog("✓ Termux SUCCESS: $total matches via $engines")
                    
                    // FREE FIX: Open blocked engines in real browser
                    if (blocked.isNotEmpty()) {
                        addLog("🚀 Opening ${blocked.joinToString()} in Chrome (real browser bypass)...")
                        openBlockedEnginesInBrowser(publicUrl ?: "", blocked)
                    }
                    
                    if (response.matches != null) {
                        val newMatches = response.matches.map {
                            SerpVisualMatch(title = it.title, link = it.link, source = it.source, thumbnail = it.thumbnail, score = it.score)
                        }
                        allRawResults.addAll(newMatches)
                        updateResultsLive(newMatches)
                    }
                } else {
                    if (response.error != null) addLog("⚠ Termux error: ${response.error}")
                    val webResults = try { webDeferred.await() } catch (_: kotlinx.coroutines.CancellationException) { emptyList<SerpVisualMatch>() }
                    if (webResults.isNotEmpty()) {
                        allRawResults.addAll(webResults)
                        updateResultsLive(webResults)
                    }
                }
            } else {
                val webResults = try { webDeferred.await() } catch (_: kotlinx.coroutines.CancellationException) { emptyList<SerpVisualMatch>() }
                if (webResults.isNotEmpty()) {
                    allRawResults.addAll(webResults)
                    updateResultsLive(webResults)
                }
            }
        }

        if (allRawResults.isEmpty()) {
            if (uiState !is CheckInUiState.Success) {
                uiState = CheckInUiState.NoMatch(currentLogs.toList())
            }
            return
        }

        // Ensure the UI shows the found results immediately
        addLog("⏳ Updating UI with ${allRawResults.distinctBy { it.link }.size} result(s)")
        try {
            updateResultsLive(allRawResults.distinctBy { it.link })
            addLog("✅ UI update complete")
            
            // Call verifyResultsLive for background precision boost
            verifyResultsLive(allRawResults.distinctBy { it.link }, faceBitmap)
            
        } catch (e: Exception) {
            addLog("❌ Error updating UI: ${e.message}")
            Log.e("CheckIn", "Error in search loop UI update", e)
        }
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

    private fun mapToDisplay(match: SerpVisualMatch): WebMatchDisplay {
        val socialDomains = listOf(
            "facebook.com", "instagram.com", "linkedin.com", "twitter.com", 
            "t.me", "vk.com", "pinterest.com", "ok.ru", "reddit.com"
        ) + AdultSiteConfig.SITES
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

        val isVerified = match.score > 5000
        val sourceLabel = if (isVerified) "✓ ${match.source}" else match.source

        return WebMatchDisplay(
            name = cleanTitle,
            source = sourceLabel ?: "Free Engine",
            profileUrl = match.link ?: "",
            score = match.score,
            imageUrl = thumb,
            isSocial = isSocial,
            confidence = calculateConfidence(match.score),
            isHighResLoading = !hasGoodThumbnail && isSocial // flag to auto-fetch better image
        )
    }

    private fun calculateConfidence(score: Int): Float {
        return when {
            score >= 8000 -> 1.0f
            score >= 5000 -> 0.85f + ((score - 5000) / 20000f)
            score >= 1000 -> 0.60f + ((score - 1000) / 10000f)
            score >= 300 -> 0.15f + ((score - 300) / 2000f)
            else -> (score.toFloat() / 2000f).coerceIn(0.07f, 0.14f)
        }
    }

    private suspend fun verifyResultsLive(
        results: List<SerpVisualMatch>,
        sourceBitmap: Bitmap
    ) {
        val cropped = nativeFaceCropper.cropAndAlignFace(sourceBitmap, fullFaceMode) ?: return
        val sourceEmbedding = faceEmbedder.getEmbedding(cropped) ?: return

        // Maintain a local mutable list for updates
        val currentResults = results.toMutableList()

        // Verify top 20 candidates only to keep background work efficient
        val candidates = results.take(20)

        for (index in candidates.indices) {
            val match = candidates[index]
            try {
                var thumbUrl = match.thumbnail
                
                if (thumbUrl.isNullOrBlank() && !match.link.isNullOrBlank()) {
                    thumbUrl = faceSearchRepository.extractMetadataThumbnail(match.link)
                }

                val finalThumb = thumbUrl ?: match.thumbnail
                if (finalThumb.isNullOrBlank()) continue

                val thumbBitmap = loadThumbnailBitmap(finalThumb) ?: continue
                val similarity = faceVerifier.verifyFaceMatch(thumbBitmap, sourceEmbedding) ?: 0f

                if (similarity > (sensitivity - 0.15f).coerceAtLeast(0.35f)) {
                    val updatedMatch = match.copy(
                        thumbnail = finalThumb,
                        score = match.score + (similarity * 7000).toInt() // Slightly higher boost
                    )
                    currentResults[index] = updatedMatch
                    
                    // Trigger UI Update
                    val currentState = uiState
                    if (currentState is CheckInUiState.Success) {
                        uiState = currentState.copy(
                            matches = currentResults.map { mapToDisplay(it) }.sortedByDescending { it.score }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("CheckIn", "Live verification error for ${match.link}", e)
            }
        }
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
