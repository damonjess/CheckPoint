package com.yourcompany.facesearch.ui

import android.app.Application
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

enum class SearchMode {
    PRECISION, BYPASS, SOCIAL, HYPER, RAW, FREE, SOCIAL_OPTIMIZED, AGGRESSIVE
}

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
    
    private var sourceEmbedding: FloatArray? = null

    fun onPhotoCaptured(bitmap: Bitmap) {
        capturedBitmap = bitmap
        
        viewModelScope.launch {
            val logs = mutableListOf("Initializing local optics...")
            uiState = CheckInUiState.Loading(0.1f, logs.toList())
            
            val maxMemory = Runtime.getRuntime().maxMemory() / 1024 / 1024
            val totalMemory = Runtime.getRuntime().totalMemory() / 1024 / 1024
            logs.add("System: Memory $totalMemory MB / $maxMemory MB")
            uiState = CheckInUiState.Loading(0.15f, logs.toList())
            
            if (searchMode != SearchMode.RAW) {
                logs.add("Running Quality Gate...")
                val quality = nativeFaceCropper.validateFaceQuality(bitmap)
                if (!quality.isGood) {
                    uiState = CheckInUiState.Error(quality.message)
                    return@launch
                }
            }

            try {
                val processedBitmap = when (searchMode) {
                    SearchMode.HYPER, SearchMode.AGGRESSIVE -> {
                        val base = nativeFaceCropper.cropForSocialProfile(bitmap)
                        // Make it more unique for better discrimination
                        ImageEnhancer.applyStructuralFingerprint(base)
                    }
                    SearchMode.BYPASS -> withContext(Dispatchers.Default) {
                        ImageEnhancer.applyCamouflage(nativeFaceCropper.cropContextual(bitmap))
                    }
                    else -> nativeFaceCropper.cropAndAlignFace(bitmap)
                }

                if (searchMode != SearchMode.RAW) {
                    logs.add("Extracting biometric signature...")
                    sourceEmbedding = withContext(Dispatchers.Default) {
                        val align = nativeFaceCropper.cropAndAlignFace(bitmap)
                        faceEmbedder.getEmbedding(align)
                    }
                }

                uiState = CheckInUiState.Loading(0.25f, logs.toList())
                logs.add("Hosting probe image...")

                val uploadBitmap = nativeFaceCropper.prepareFaceForSearch(processedBitmap)
                val publicUrl = imageUploadRepository.uploadImage(uploadBitmap)

                if (publicUrl != null) {
                    logs.add("Upload successful. Starting search...")
                    performWebSearch(publicUrl, logs)
                } else {
                    uiState = CheckInUiState.Error("Image hosting failed.")
                }
            } catch (e: Exception) {
                uiState = CheckInUiState.Error("Processing failed: ${e.message}")
            }
        }
    }

    private suspend fun performWebSearch(imageUrl: String, logs: MutableList<String>) {
        logs.add("Starting ultra-safe search...")
        uiState = CheckInUiState.Loading(0.7f, logs.toList())

        try {
            logs.add("Running visual search...")

            val visualMatches = try {
                faceSearchRepository.performFaceSearch(
                    uploadedImageUrl = imageUrl,
                    keywordHint = targetHint.trim().ifBlank { null },
                    onLog = { logs.add(it) }
                )
            } catch (e: Exception) {
                logs.add("Search engines failed: ${e.message}")
                emptyList()
            }

            logs.add("Got ${visualMatches.size} raw results.")

            // Show raw results without verification or heavy processing
            val displayMatches = visualMatches.map { result ->
                WebMatchDisplay(
                    name = result.title ?: "Result",
                    source = result.source ?: "Web",
                    profileUrl = result.link ?: "",
                    score = result.score,
                    imageUrl = result.thumbnail
                )
            }

            if (displayMatches.isNotEmpty()) {
                uiState = CheckInUiState.Success(
                    matches = displayMatches.sortedByDescending { it.score },
                    gemmaAnalysis = null
                )
            } else {
                uiState = CheckInUiState.NoMatch(logs.toList())
            }

        } catch (e: Exception) {
            logs.add("Crash prevented: ${e.message}")
            uiState = CheckInUiState.Error("Search failed - try HYPER or FREE mode")
        }
    }

    private suspend fun verifyResultsLocally(
        matches: List<SerpVisualMatch>, 
        logs: MutableList<String>
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
                
                if (finalScore > 0.62f) {
                    verified.add(WebMatchDisplay(
                        name = match.title ?: "Match",
                        source = match.source ?: "Social",
                        profileUrl = match.link ?: "",
                        score = match.score + (finalScore * 18000).toInt(),
                        imageUrl = match.thumbnail
                    ))
                    logs.add("Good match! Face: ${similarity} + Name boost")
                }
                thumb.recycleSafely()
            } catch (e: Exception) {}
        }
        return verified
    }

    fun onRetry() {
        uiState = CheckInUiState.Idle
        capturedBitmap = null
    }

    fun onConfirmFreeSearch(bitmap: Bitmap) {
        viewModelScope.launch {
            val original = capturedBitmap ?: bitmap
            if (searchMode == SearchMode.AGGRESSIVE || searchMode == SearchMode.HYPER) {
                uiState = CheckInUiState.Loading(0.1f, listOf("Starting deep search..."))
                onPhotoCaptured(original)
            } else {
                freeSearch.searchMyPhoto(original, targetHint)
                delay(1000)
                uiState = CheckInUiState.Idle
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
