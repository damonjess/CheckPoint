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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

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
        
        // Stage BOTH probes for Termux bypass
        var faceCrop: Bitmap? = null
        viewModelScope.launch {
            LocalServer.stageProbe(bitmap, isFaceCrop = false)
            faceCrop = nativeFaceCropper.cropAndAlignFace(bitmap)
            LocalServer.stageProbe(faceCrop!!, isFaceCrop = true)
        }
        
        isSearching = true
        viewModelScope.launch {
            try {
                if (faceCrop == null) faceCrop = nativeFaceCropper.cropAndAlignFace(bitmap)

                // Unified pipeline for all modes - handles Termux failure gracefully
                performSearchPipeline(bitmap, faceCrop!!)
            } catch (e: Exception) {
                Log.e("CheckIn", "Auto-search failed", e)
                uiState = CheckInUiState.Error("Search failed: ${e.message}")
            } finally {
                isSearching = false
            }
        }
    }

    private suspend fun performSearchPipeline(bitmap: Bitmap, faceBitmap: Bitmap) {
        Log.e("CheckIn", "!!! CRITICAL LOG !!! Starting performSearchPipeline")
        val logs = mutableListOf("Initializing free-only pipeline (Face-Centric)...")
        fun addLog(msg: String) {
            logs.add(msg)
            Log.e("CheckIn", "CONSOLE_LOG: $msg")
            uiState = CheckInUiState.Loading(0.3f, logs.toList())
        }

        uiState = CheckInUiState.Loading(0.1f, logs.toList())

        // Always use free hosting chain - upload FACE CROP for visual search
        addLog("Uploading face probe to free hosting...")
        val publicUrl = freeImageHost.upload(faceBitmap, ::addLog)

        if (publicUrl == null) {
            addLog("✗ All free hosts failed. Switching to direct browser launch...")
            uiState = CheckInUiState.Error("No free image host available. Use FREE mode to search directly.", logs)
            return
        }

        addLog("✓ Probe hosted: ${publicUrl.take(35)}...")

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

        // Run the free scraper
        val rawResults = faceSearchRepository.performFaceSearch(
            bitmap = bitmap,
            faceBitmap = faceBitmap,
            keywordHint = combinedHint,
            imageUrl = publicUrl, // Pass the URL we already have!
            deepCrawl = searchMode == SearchMode.DEEP_CRAWL,
            searchMode = searchMode.name,
            onLog = ::addLog
        )

        if (rawResults.isEmpty()) {
            uiState = CheckInUiState.NoMatch(logs.toList())
            return
        }

        addLog("Verifying matches via local biometric embedding...")
        val results = verifyResults(rawResults, bitmap)

        val display = results.map { match ->
            // Social detection: Domain + presence of a path/profile ID
            val socialDomains = listOf(
                "facebook.com", "instagram.com", "linkedin.com", "twitter.com", "t.me", "vk.com",
                "pinterest.com", "ok.ru"
            ) + AdultSiteConfig.SITES
            val isSocial = socialDomains.any { domain -> match.link?.contains(domain) == true }

            // Improve title logic
            var cleanTitle = match.title ?: "Visual Match"
            if (cleanTitle.contains(Regex("^[a-zA-Z0-9-]+\\.[a-z]{2,}$")) || cleanTitle == "Visual Match") {
                val uri = try { Uri.parse(match.link) } catch(e: Exception) { null }
                val pathSegments = uri?.pathSegments
                if (pathSegments?.isNotEmpty() == true) {
                    cleanTitle = pathSegments.last().replace("-", " ").replace("_", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                }
            }
            
            // Add verification status to source name if verified
            val isVerified = match.score > 5000
            val sourceLabel = if (isVerified) "✓ ${match.source}" else match.source
            
            // Re-map confidence to be more "human"
            // Raw scores are usually 100-600. Verified scores are 2000-7000+.
            val displayConfidence = when {
                match.score >= 8000 -> 1.0f
                match.score >= 5000 -> 0.90f + ((match.score - 5000) / 30000f) // 5k -> 90%, 8k -> 100%
                match.score >= 1000 -> 0.50f + ((match.score - 1000) / 10000f) // 1k -> 50%, 5k -> 90%
                else -> (match.score.toFloat() / 2000f).coerceIn(0.01f, 0.49f) // 100 -> 5%
            }

            WebMatchDisplay(
                name = cleanTitle,
                source = sourceLabel ?: "Free Engine",
                profileUrl = match.link ?: "",
                score = match.score,
                imageUrl = ThumbnailUtils.normalize(match.thumbnail),
                isSocial = isSocial,
                confidence = displayConfidence
            )
        }

        uiState = CheckInUiState.Success(
            matches = display.sortedByDescending { it.score },
            gemmaAnalysis = null,
            logs = logs.toList()
        )
    }

    fun onConfirmFreeSearch(bitmap: Bitmap) {
        Log.d("CheckIn", "onConfirmFreeSearch clicked. Mode: ${searchMode.name}")
        if (isSearching) return
        isSearching = true

        viewModelScope.launch {
            try {
                when (searchMode) {
                    SearchMode.FREE -> {
                        // Pure browser intent mode — no servers needed
                        val exifHints = extractExifHints(bitmap)
                        val combinedHint = listOf(targetHint, exifHints)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                        
                        freeSearchHelper.launchDirectSearch(bitmap, combinedHint)
                        delay(1500)
                        uiState = CheckInUiState.Idle
                    }
                    else -> {
                        // Try robust scraper pipeline which handles Termux + Web
                        val faceCrop = nativeFaceCropper.cropAndAlignFace(bitmap)
                        performSearchPipeline(bitmap, faceCrop)
                    }
                }
            } catch (e: Exception) {
                uiState = CheckInUiState.Error("Free search failed: ${e.message}")
            } finally {
                isSearching = false
            }
        }
    }

    fun onGoogleLensOnlySearch(bitmap: Bitmap) {
        viewModelScope.launch {
            // Simplified Google Lens call using new helper's cache logic
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
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com/upload"))
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(browserIntent)
            }
            delay(1000)
            uiState = CheckInUiState.Idle
        }
    }

    fun onRetry() {
        isSearching = false
        uiState = CheckInUiState.Idle
        capturedBitmap = null
    }

    private suspend fun verifyResults(
        results: List<SerpVisualMatch>,
        sourceBitmap: Bitmap
    ): List<SerpVisualMatch> {
        if (results.isEmpty()) return emptyList()

        val cropped = nativeFaceCropper.cropAndAlignFace(sourceBitmap)
        val sourceEmbedding = faceEmbedder.getEmbedding(cropped)
        
        if (sourceEmbedding == null) return results

        val verified = mutableListOf<SerpVisualMatch>()

        for (match in results.take(40)) { // check all results (up to 40)
            if (match.thumbnail.isNullOrBlank()) continue

            try {
                val thumb = loadThumbnailBitmap(match.thumbnail) ?: continue
                val similarity = faceVerifier.verifyFaceMatch(thumb, sourceEmbedding) ?: 0f

                if (similarity > 0.40f) {
                    // Boost score based on face similarity
                    verified.add(match.copy(score = match.score + (similarity * 5000).toInt()))
                }
            } catch (e: Exception) { /* skip bad thumbnail */ }
        }

        return if (verified.isEmpty()) results else verified.sortedByDescending { it.score }
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

    private suspend fun loadThumbnailBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(getApplication())
                .data(url)
                .allowHardware(false) // Important for face processing
                .build()
            // This will use the SingletonImageLoader configured in MainActivity
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
