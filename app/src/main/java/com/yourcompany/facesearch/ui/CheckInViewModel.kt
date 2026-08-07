package com.yourcompany.facesearch.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import com.yourcompany.facesearch.network.FaceSearchRepository
import com.yourcompany.facesearch.network.FreeImageHost
import com.yourcompany.facesearch.network.SerpVisualMatch
import com.yourcompany.facesearch.network.SocialMediaDetector
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
        if (isSearching) {
            val current = (uiState as? CheckInUiState.Loading)?.logs ?: emptyList()
            uiState = CheckInUiState.Loading(0.3f, current + "Already searching...")
            return
        }
        capturedBitmap = bitmap
        
        // FREE MODE BYPASS: Don't start the loading sequence automatically
        if (searchMode == SearchMode.FREE) {
            uiState = CheckInUiState.Idle
            return
        }

        isSearching = true
        viewModelScope.launch {
            try {
                performSearchPipeline(bitmap)
            } finally {
                isSearching = false
            }
        }
    }

    private suspend fun performSearchPipeline(bitmap: Bitmap) {
        val logs = mutableListOf("Initializing free-only pipeline...")
        fun addLog(msg: String) {
            logs.add(msg)
            uiState = CheckInUiState.Loading(0.3f, logs.toList())
        }

        uiState = CheckInUiState.Loading(0.1f, logs.toList())

        // Always use free hosting chain
        addLog("Uploading to free hosting chain...")
        val publicUrl = freeImageHost.upload(bitmap, ::addLog)

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

        val display = results.map {
            WebMatchDisplay(
                name = it.title ?: "Visual Match",
                source = it.source ?: "Free Engine",
                profileUrl = it.link ?: "",
                score = it.score,
                imageUrl = it.thumbnail
            )
        }

        uiState = CheckInUiState.Success(
            matches = display.sortedByDescending { it.score },
            gemmaAnalysis = null,
            logs = logs.toList()
        )
    }

    fun onConfirmFreeSearch(bitmap: Bitmap) {
        if (isSearching) return
        isSearching = true

        viewModelScope.launch {
            try {
                val exifHints = extractExifHints(bitmap)
                val combinedHint = listOf(targetHint, exifHints)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")

                when (searchMode) {
                    SearchMode.FREE -> {
                        // Pure browser intent mode — no servers needed
                        freeSearchHelper.launchDirectSearch(bitmap, combinedHint)
                        delay(1500)
                        uiState = CheckInUiState.Idle
                    }
                    else -> {
                        // Try free scraper pipeline
                        performSearchPipeline(bitmap)
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

        for (match in results.take(15)) { // check top 15 only (speed)
            if (match.thumbnail.isNullOrBlank()) continue

            try {
                val thumb = loadThumbnailBitmap(match.thumbnail) ?: continue
                val similarity = faceVerifier.verifyFaceMatch(thumb, sourceEmbedding) ?: 0f

                if (similarity > 0.45f) {
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
