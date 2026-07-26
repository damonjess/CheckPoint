package com.yourcompany.facesearch.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.imageLoader
import coil3.toBitmap
import coil3.request.allowHardware
import com.yourcompany.facesearch.network.ApiClient
import com.yourcompany.facesearch.network.ApifyRepository
import com.yourcompany.facesearch.network.FaceSearchRepository
import com.yourcompany.facesearch.network.ImageUploadRepository
import com.yourcompany.facesearch.network.Secrets
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

class CheckInViewModel(
    application: Application
) : AndroidViewModel(application) {

    var isSearching by mutableStateOf(false)
        private set

    private val nativeFaceCropper = NativeFaceCropper()
    private val faceSearchRepository = FaceSearchRepository()
    private val imageUploadRepository = ImageUploadRepository()
    private val apifyRepository = ApifyRepository()
    private val faceEmbedder = FaceEmbedder(application)
    private val faceVerifier = FaceVerifier(application)
    private val freeSearch = FreeFaceSearchHelper(application, nativeFaceCropper)
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

    private var sourceEmbedding: FloatArray? = null

    fun onPhotoCaptured(bitmap: Bitmap) {
        if (isSearching) return
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
        val logs = mutableListOf("Initializing local optics...")
        fun addLog(msg: String) {
            logs.add(msg)
            uiState = when (val current = uiState) {
                is CheckInUiState.Loading -> current.copy(logs = logs.toList())
                is CheckInUiState.Error -> current.copy(logs = logs.toList())
                is CheckInUiState.NoFaceDetected -> current.copy(logs = logs.toList())
                else -> CheckInUiState.Loading(0.2f, logs.toList())
            }
        }

        uiState = CheckInUiState.Loading(0.1f, logs.toList())
        
        val maxMemory = Runtime.getRuntime().maxMemory() / 1024 / 1024
        val totalMemory = Runtime.getRuntime().totalMemory() / 1024 / 1024
        addLog("System: Memory $totalMemory MB / $maxMemory MB")
        
        if (searchMode != SearchMode.RAW) {
            addLog("Running Quality Gate...")
            val quality = nativeFaceCropper.validateFaceQuality(bitmap)
            if (!quality.isGood) {
                uiState = if (quality.message.contains("No face", ignoreCase = true)) {
                    CheckInUiState.NoFaceDetected(logs.toList())
                } else {
                    CheckInUiState.Error(quality.message, logs.toList())
                }
                return
            }
        }

        val processedBitmap = when (searchMode) {
            SearchMode.HYPER, SearchMode.AGGRESSIVE -> {
                addLog("Applying structural fingerprints...")
                val base = nativeFaceCropper.cropForSocialProfile(bitmap)
                ImageEnhancer.applyStructuralFingerprint(base)
            }
            SearchMode.BYPASS -> {
                addLog("Applying camouflage filters...")
                withContext(Dispatchers.Default) {
                    ImageEnhancer.applyCamouflage(nativeFaceCropper.cropContextual(bitmap))
                }
            }
            else -> {
                addLog("Aligning biometric plane...")
                nativeFaceCropper.cropAndAlignFace(bitmap)
            }
        }

        if (searchMode != SearchMode.RAW) {
            addLog("Extracting biometric signature...")
            sourceEmbedding = withContext(Dispatchers.Default) {
                val align = nativeFaceCropper.cropAndAlignFace(bitmap)
                faceEmbedder.getEmbedding(align)
            }
        }

        uiState = CheckInUiState.Loading(0.25f, logs.toList())
        addLog("Initiating probe hosting...")

        // 1. Stage locally for Termux Bypass
        addLog("✓ Local probe staged.")

        // 2. Attempt Public Upload
        val uploadBitmap = nativeFaceCropper.prepareFaceForSearch(processedBitmap)
        val publicUrl = imageUploadRepository.uploadImage(uploadBitmap, ::addLog)

        if (publicUrl != null) {
            addLog("✓ Probe active at ${publicUrl.take(30)}...")
            performWebSearch(publicUrl, targetHint.trim(), logs)
        } else {
            addLog("⚠ Public hosting failed. Using local probe only.")
            performWebSearch("", targetHint.trim(), logs)
        }
    }

    private suspend fun performWebSearch(
        publicImageUrl: String, 
        hintText: String, 
        logs: MutableList<String>
    ) {
        fun addLog(msg: String) {
            logs.add(msg)
            uiState = when (val current = uiState) {
                is CheckInUiState.Loading -> current.copy(logs = logs.toList())
                is CheckInUiState.Error -> current.copy(logs = logs.toList())
                is CheckInUiState.NoMatch -> current.copy(logs = logs.toList())
                else -> CheckInUiState.Loading(0.8f, logs.toList())
            }
        }

        addLog("Routing search request through automation cluster...")
        uiState = CheckInUiState.Loading(0.7f, logs.toList())

        try {
            val visualMatches = try {
                faceSearchRepository.performFaceSearch(
                    uploadedImageUrl = publicImageUrl,
                    keywordHint = hintText.trim().ifBlank { null },
                    onLog = ::addLog
                )
            } catch (e: Exception) {
                addLog("✗ CRITICAL SEARCH ERROR: ${e.message}")
                emptyList()
            }

            addLog("Processing results: ${visualMatches.size} candidates found.")
            uiState = CheckInUiState.Loading(0.9f, logs.toList())

            // BRANCHING: If mode is AGGRESSIVE/HYPER, apply strict local signature alignment
            var displayMatches = if (searchMode == SearchMode.AGGRESSIVE || searchMode == SearchMode.HYPER) {
                addLog("Deep Mode verified. Extracting target matrices...")
                uiState = CheckInUiState.Loading(0.95f, logs.toList())
                verifyResultsLocally(visualMatches, ::addLog)
            } else {
                // Standard mode processing fallback
                visualMatches.map { result ->
                    val cleanName = result.title?.replace(Regex("\\d+\\s*[×x]\\s*\\d+"), "")?.trim()
                    WebMatchDisplay(
                        name = if (!cleanName.isNullOrBlank()) cleanName else "Visual Match Profile",
                        source = result.source ?: "Stealth Engine",
                        profileUrl = result.link ?: "",
                        score = result.score,
                        imageUrl = result.thumbnail
                    )
                }
            }

            // FALLBACK: If filtering was too strict, show at least top 3 raw matches
            if (displayMatches.isEmpty() && visualMatches.isNotEmpty()) {
                addLog("⚠ High-confidence filter rejected all leads. Showing raw visual matches instead.")
                displayMatches = visualMatches.take(5).map { result ->
                    WebMatchDisplay(
                        name = result.title ?: "Visual Match",
                        source = result.source ?: "Crawl",
                        profileUrl = result.link ?: "",
                        score = 500, // Lower score for unverified
                        imageUrl = result.thumbnail
                    )
                }
            }

            addLog("Search phase complete. ${displayMatches.size} visual leads ready.")

            if (displayMatches.isNotEmpty()) {
                uiState = CheckInUiState.Success(
                    matches = displayMatches.sortedByDescending { it.score },
                    gemmaAnalysis = null,
                    logs = logs.toList()
                )
            } else {
                // Fallback assistance to help users pivot if zero leads clear the threshold
                if (visualMatches.isNotEmpty()) {
                    addLog("ℹ Hint: ${visualMatches.size} traces found but filtered out by local signature limits.")
                }
                uiState = CheckInUiState.NoMatch(logs.toList())
            }

        } catch (e: Exception) {
            addLog("Crash prevented: ${e.message}")
            uiState = CheckInUiState.Error("Search failed - fallback to FREE mode suggested", logs.toList())
        }
    }

    private suspend fun verifyResultsLocally(
        matches: List<SerpVisualMatch>, 
        onLog: (String) -> Unit
    ): List<WebMatchDisplay> {
        val verified = mutableListOf<WebMatchDisplay>()
        val hint = targetHint.lowercase()
        
        onLog("Biometric Verification: Processing top ${minOf(matches.size, 30)} leads...")
        
        for (match in matches.take(30)) {
            if (match.thumbnail.isNullOrBlank()) {
                if (debugMode) onLog("× Skipping: No thumbnail for ${match.source}")
                continue
            }
            onLog("🔍 Checking: ${match.title?.take(30)}... [${match.source}]")
            try {
                val thumb = loadThumbnailBitmap(match.thumbnail)
                if (thumb == null) {
                    onLog("× Error: Failed to load lead image.")
                    continue
                }
                
                val similarity = faceVerifier.verifyFaceMatch(thumb, sourceEmbedding) ?: 0f
                
                val nameScore = if (hint.isNotBlank() && match.title?.lowercase()?.contains(hint) == true) 0.25f else 0f
                val finalScore = similarity + nameScore
                
                val platform = SocialMediaDetector.detectPlatform(match.link)
                val threshold = 0.35f // Relaxed threshold for better discovery
                
                if (finalScore >= threshold) { 
                    val cleanName = match.title?.replace(Regex("\\d+\\s*[×x]\\s*\\d+"), "")?.trim()
                    verified.add(WebMatchDisplay(
                        name = if (!cleanName.isNullOrBlank()) cleanName else "Match",
                        source = match.source ?: platform.name,
                        profileUrl = match.link ?: "",
                        score = match.score + (finalScore * 18000).toInt(),
                        imageUrl = match.thumbnail
                    ))
                    onLog("✓ Match verified: ${"%.2f".format(similarity)} similarity [${match.source}]")
                } else {
                    onLog("× Low similarity: ${"%.2f".format(similarity)} [${match.source}]")
                }
                // DON'T recycle - let Coil handle it
            } catch (e: Exception) {
                if (debugMode) onLog("× Verify error: ${e.message}")
            }
        }
        return verified
    }

    fun onRetry() {
        isSearching = false
        uiState = CheckInUiState.Idle
        capturedBitmap = null
    }

    fun onConfirmFreeSearch(bitmap: Bitmap) {
        viewModelScope.launch {
            // Prevent multiple searches
            if (isSearching) {
                uiState = CheckInUiState.Error("Search already in progress...")
                return@launch
            }
            
            isSearching = true
            val original = capturedBitmap ?: bitmap
            
            try {
                // FORCE FREE MODE: Skip ALL uploads, just open browser
                if (searchMode == SearchMode.FREE) {
                    val uri = freeSearch.saveImageDirect(original)
                    freeSearch.openBrowsersDirect(uri, targetHint)
                    uiState = CheckInUiState.Idle
                    return@launch
                }
                
                // AGGRESSIVE / HYPER MODE - with safety timeout
                if (searchMode == SearchMode.AGGRESSIVE || searchMode == SearchMode.HYPER) {
                    uiState = CheckInUiState.Loading(0.1f, listOf("Starting deep search..."))
                    // Run with a timeout to prevent hanging
                    withTimeoutOrNull(60000) {
                        performSearchPipeline(original)
                    } ?: run {
                        uiState = CheckInUiState.Error("Search timed out. Try a different mode.")
                        return@launch
                    }
                } else {
                    freeSearch.searchMyPhoto(original, targetHint)
                    delay(1000)
                    uiState = CheckInUiState.Idle
                }
            } catch (e: Exception) {
                uiState = CheckInUiState.Error("Search failed: ${e.message}")
            } finally {
                isSearching = false
            }
        }
    }

    fun onGoogleLensOnlySearch(bitmap: Bitmap) {
        viewModelScope.launch {
            freeSearch.openGoogleLensOnly(bitmap, targetHint)
            delay(1000)
            uiState = CheckInUiState.Idle
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


private suspend fun CheckInViewModel.loadThumbnailBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val request = ImageRequest.Builder(getApplication())
            .data(url)
            .size(400)
            .allowHardware(false)
            .build()
        getApplication<Application>().imageLoader.execute(request).image?.toBitmap()
    } catch (e: Exception) {
        null
    }
}
