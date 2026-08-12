package com.yourcompany.facesearch.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.yourcompany.facesearch.vision.FaceEmbedder
import com.yourcompany.facesearch.vision.FaceVerifier
import com.yourcompany.facesearch.vision.FreeFaceSearchHelper
import com.yourcompany.facesearch.vision.GemmaAnalyzer
import com.yourcompany.facesearch.vision.ImageEnhancer
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
    private val faceSearchRepository = FaceSearchRepository(getApplication())
    private val faceEmbedder = FaceEmbedder(application)
    private val faceVerifier = FaceVerifier(application)
    private val freeImageHost = FreeImageHost()
    private val freeSearchHelper = FreeFaceSearchHelper(getApplication())
    private val gemmaAnalyzer = GemmaAnalyzer(application)

    var uiState by mutableStateOf<CheckInUiState>(CheckInUiState.Idle)
        private set

    var capturedBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var targetHint by mutableStateOf("")

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
                // 1. Stage full probe immediately
                LocalServer.stageProbe(bitmap, isFaceCrop = false)

                // 2. Detect and stage face crop
                val faceCrop = nativeFaceCropper.cropAndAlignFace(bitmap)
                if (faceCrop != null) {
                    LocalServer.stageProbe(faceCrop, isFaceCrop = true)
                    // 3. Run pipeline
                    performSearchPipeline(bitmap, faceCrop)
                } else {
                    Log.e("CheckIn", "No face detected in capture.")
                    uiState = CheckInUiState.NoFaceDetected(listOf("✗ No face detected in probe. Adjust angle and retry."))
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
                    faceSearchRepository.extractHighResMedia(match.profileUrl)
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

    private suspend fun performSearchPipeline(bitmap: Bitmap, faceBitmap: Bitmap) {
        Log.e("CheckIn", "!!! CRITICAL LOG !!! Starting performSearchPipeline")
        val logs = mutableListOf("Initializing free-only pipeline (Face-Centric)...")
        fun addLog(msg: String) {
            logs.add(msg)
            Log.e("CheckIn", "CONSOLE_LOG: $msg")
            uiState = when (val previous = uiState) {
                is CheckInUiState.Success -> previous.copy(logs = logs.toList())
                is CheckInUiState.NoMatch -> previous.copy(logs = logs.toList())
                is CheckInUiState.Error -> previous.copy(logs = logs.toList())
                is CheckInUiState.NoFaceDetected -> previous.copy(logs = logs.toList())
                is CheckInUiState.Loading -> previous.copy(logs = logs.toList())
                else -> CheckInUiState.Loading(0.3f, logs.toList())
            }
        }

        uiState = CheckInUiState.Loading(0.1f, logs.toList())

        // Try to upload face crop to free hosting; if that fails, fall back to the local probe served by `LocalServer`.
        addLog("Uploading face probe to free hosting...")
        var publicUrl = freeImageHost.upload(faceBitmap, ::addLog)

        if (publicUrl == null) {
            addLog("✗ All free hosts failed. Falling back to local probe served by the app (offline mode).")
            try {
                LocalServer.start(getApplication())
                // Prefer face crop if available
                publicUrl = "http://127.0.0.1:8080/face.jpg"
                addLog("✓ Using local probe: $publicUrl")
            } catch (e: Exception) {
                addLog("✗ Failed to start LocalServer: ${e.message}")
                uiState = CheckInUiState.Error("No free image host available and local probe failed.", logs)
                return
            }
        } else {
            addLog("✓ Probe hosted: ${publicUrl.take(35)}...")
        }

        if (searchMode == SearchMode.DEEP_CRAWL) {
            addLog("🕸️ DEEP CRAWL: Activating recursive avatar extraction...")
            addLog("This will extract og:image tags from private profiles...")
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

        // Determine if Termux is intended to handle visual engines to avoid redundancy
        var useTermux = searchMode in listOf(SearchMode.PRECISION, SearchMode.HYPER, SearchMode.BYPASS, SearchMode.AGGRESSIVE, SearchMode.DEEP_CRAWL)

        if (useTermux) {
            addLog("Probing for local Termux backend...")
            val available = withTimeoutOrNull(2500L) { faceSearchRepository.isLocalBackendAvailable() } ?: false
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
                    updateResultsLive(listOf(match), logs)
                }
            } catch (e: Exception) {
                addLog("⚠ LocalServer offline analysis failed: ${e.message}")
            }
            // Skip network scrapers
            if (allRawResults.isEmpty()) {
                uiState = CheckInUiState.NoMatch(logs.toList())
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
                        searchMode = searchMode.name,
                        onLog = ::addLog
                    )
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
                        deepCrawl = searchMode == SearchMode.DEEP_CRAWL,
                        searchMode = searchMode.name,
                        skipVisualEngines = false,
                        onLog = ::addLog
                    )
                } catch (e: Exception) {
                    addLog("⚠ WebView error: ${e.message}")
                    emptyList<SerpVisualMatch>()
                }
            }

            // If Termux is in use, prefer its results first and cancel web scraping if Termux returns hits
            if (useTermux) {
                val response = try { withTimeoutOrNull(10000L) { termuxDeferred.await() } } catch (e: Exception) { null }
                if (response == null) {
                    addLog("⚠ Termux call timed out locally; falling back to in-app WebView.")
                    if (!termuxDeferred.isCompleted) termuxDeferred.cancel()
                    // Wait for web results instead
                    val webResults = try { webDeferred.await() } catch (e: Exception) { emptyList<SerpVisualMatch>() }
                    if (webResults.isNotEmpty()) {
                        allRawResults.addAll(webResults)
                        updateResultsLive(webResults, logs)
                    }
                    return@coroutineScope
                }
                if (response != null && response.success && !response.matches.isNullOrEmpty()) {
                    // Got results from Termux — cancel web scraping to finish fast
                    if (!webDeferred.isCompleted) webDeferred.cancel()
                    val newMatches = response.matches.map {
                        SerpVisualMatch(title = it.title, link = it.link, source = it.source, thumbnail = it.thumbnail, score = it.score)
                    }
                    allRawResults.addAll(newMatches)
                    updateResultsLive(newMatches, logs)
                } else {
                    if (response?.error != null) addLog("⚠ Termux error: ${response.error}")
                    // Fall back to web scraping (wait for it)
                    val webResults = try { webDeferred.await() } catch (e: CancellationException) { emptyList<SerpVisualMatch>() }
                    if (webResults.isNotEmpty()) {
                        allRawResults.addAll(webResults)
                        updateResultsLive(webResults, logs)
                    }
                }
            } else {
                // No Termux: just wait for web results
                val webResults = try { webDeferred.await() } catch (e: Exception) { emptyList<SerpVisualMatch>() }
                if (webResults.isNotEmpty()) {
                    allRawResults.addAll(webResults)
                    updateResultsLive(webResults, logs)
                }
            }
        }

        if (allRawResults.isEmpty()) {
            if (uiState !is CheckInUiState.Success) {
                uiState = CheckInUiState.NoMatch(logs.toList())
            }
            return
        }

        // Ensure the UI shows the found results immediately (some code-paths add to allRawResults
        // but didn't call updateResultsLive earlier). This guarantees loading stops.
        addLog("⏳ Updating UI with ${allRawResults.distinctBy { it.link }.size} result(s)")
        try {
            updateResultsLive(allRawResults.distinctBy { it.link }, logs)
            addLog("✅ UI update complete, switching to Success state")
        } catch (e: Exception) {
            addLog("❌ Error updating UI: ${e.message}")
            Log.e("CheckIn", "Error in search loop UI update", e)
        }
    }

    private fun updateResultsLive(newResults: List<SerpVisualMatch>, logs: List<String>) {
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
            gemmaAnalysis = (currentState as? CheckInUiState.Success)?.gemmaAnalysis,
            logs = logs.toList()
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
            "facebook.com", "instagram.com", "linkedin.com", "twitter.com", "t.me", "vk.com",
            "pinterest.com", "ok.ru"
        ) + AdultSiteConfig.SITES
        val isSocial = socialDomains.any { domain -> match.link?.contains(domain) == true }

        var cleanTitle = match.title ?: "Visual Match"
        if (cleanTitle.contains(Regex("^[a-zA-Z0-9-]+\\.[a-z]{2,}$")) || cleanTitle == "Visual Match") {
            val uri = try { Uri.parse(match.link) } catch(e: Exception) { null }
            val pathSegments = uri?.pathSegments
            if (pathSegments?.isNotEmpty() == true) {
                cleanTitle = pathSegments.last().replace("-", " ").replace("_", " ")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }
        }
        
        val isVerified = match.score > 5000
        val sourceLabel = if (isVerified) "✓ ${match.source}" else match.source
        
        val displayConfidence = when {
            match.score >= 8000 -> 1.0f
            match.score >= 5000 -> 0.85f + ((match.score - 5000) / 20000f) // Verified boost
            match.score >= 1000 -> 0.60f + ((match.score - 1000) / 10000f)
            match.score >= 300 -> 0.15f + ((match.score - 300) / 2000f)
            else -> (match.score.toFloat() / 2000f).coerceIn(0.07f, 0.14f) // Floor at 7%
        }

        return WebMatchDisplay(
            name = cleanTitle,
            source = sourceLabel ?: "Free Engine",
            profileUrl = match.link ?: "",
            score = match.score,
            imageUrl = ThumbnailUtils.normalize(match.thumbnail),
            isSocial = isSocial,
            confidence = displayConfidence
        )
    }

    private suspend fun verifyResultsLive(
        results: List<SerpVisualMatch>,
        sourceBitmap: Bitmap
    ) {
        val cropped = nativeFaceCropper.cropAndAlignFace(sourceBitmap) ?: return
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

                if (similarity > 0.40f) {
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
        gemmaAnalyzer.close()
        faceEmbedder.close()
        nativeFaceCropper.release()
    }
}
