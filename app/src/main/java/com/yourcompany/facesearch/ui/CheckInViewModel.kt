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
    
    var isSearching by mutableStateOf(false)
        private set

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
                        return@launch
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
                val localUrl = imageUploadRepository.stageLocalProbe(processedBitmap)
                addLog("✓ Local probe staged.")

                // 2. Attempt Public Upload
                val uploadBitmap = nativeFaceCropper.prepareFaceForSearch(processedBitmap)
                val publicUrl = imageUploadRepository.uploadImage(uploadBitmap, ::addLog)

                if (publicUrl != null) {
                    addLog("✓ Probe active at ${publicUrl.take(30)}...")
                    performWebSearch(publicUrl, localUrl, targetHint.trim(), logs)
                } else {
                    addLog("⚠ Public hosting failed. Using local probe only.")
                    performWebSearch("", localUrl, targetHint.trim(), logs)
                }
            } catch (e: Exception) {
                uiState = CheckInUiState.Error("Processing failed: ${e.message}", emptyList())
            } finally {
                isSearching = false
            }
        }
    }

    private suspend fun performWebSearch(
        publicImageUrl: String, 
        localImageUrl: String?,
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
                    localImageUrl = localImageUrl,
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
        
        for (match in matches.take(15)) {
            if (match.thumbnail.isNullOrBlank()) continue
            try {
                val thumb = loadThumbnailBitmap(match.thumbnail) ?: continue
                val similarity = faceVerifier.verifyFaceMatch(thumb, sourceEmbedding)
                
                val nameScore = if (hint.isNotBlank() && match.title?.lowercase()?.contains(hint) == true) 0.25f else 0f
                
                val finalScore = (similarity ?: 0f) + nameScore
                
                if (finalScore > 0.40f) { // Further relaxed
                    val cleanName = match.title?.replace(Regex("\\d+\\s*[×x]\\s*\\d+"), "")?.trim()
                    verified.add(WebMatchDisplay(
                        name = if (!cleanName.isNullOrBlank()) cleanName else "Match",
                        source = match.source ?: "Social Profile",
                        profileUrl = match.link ?: "",
                        score = match.score + (finalScore * 18000).toInt(),
                        imageUrl = match.thumbnail
                    ))
                    onLog("✓ Match verified: ${"%.2f".format(similarity ?: 0f)} similarity")
                } else {
                    if (debugMode) onLog("× Low similarity: ${"%.2f".format(similarity ?: 0f)}")
                }
                thumb.recycleSafely()
            } catch (e: Exception) {}
        }
        return verified
    }

    fun onRetry() {
        uiState = CheckInUiState.Idle
        capturedBitmap = null
        isSearching = false
    }

    fun onConfirmFreeSearch(bitmap: Bitmap) {
        if (isSearching) return
        isSearching = true
        viewModelScope.launch {
            val original = capturedBitmap ?: bitmap
            
            try {
                // FREE mode: Skip upload entirely, just open browser tabs
                if (searchMode == SearchMode.FREE) {
                    uiState = CheckInUiState.Loading(0.5f, listOf("Opening search engines...", "Preparing local image for sharing..."))
                    freeSearch.searchMyPhotoDirect(original, targetHint)
                    delay(1500)
                    uiState = CheckInUiState.Idle
                    return@launch
                }
                
                if (searchMode == SearchMode.AGGRESSIVE || searchMode == SearchMode.HYPER) {
                    uiState = CheckInUiState.Loading(0.1f, listOf("Starting deep search..."))
                    onPhotoCaptured(original)
                } else {
                    freeSearch.searchMyPhoto(original, targetHint)
                    delay(1000)
                    uiState = CheckInUiState.Idle
                }
            } catch (e: Exception) {
                uiState = CheckInUiState.Error("Search operation failed: ${e.message}", listOf("Engine error"))
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

fun Bitmap?.recycleSafely() {
    try { if (this != null && !isRecycled) recycle() } catch (_: Exception) {}
}

private suspend fun CheckInViewModel.loadThumbnailBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val request = ImageRequest.Builder(getApplication())
            .data(url)
            .size(400)
            .build()
        getApplication<Application>().imageLoader.execute(request).image?.toBitmap()
    } catch (e: Exception) {
        null
    }
}
