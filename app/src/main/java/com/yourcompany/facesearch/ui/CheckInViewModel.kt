package com.yourcompany.facesearch.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.facesearch.network.ApiClient
import com.yourcompany.facesearch.network.FaceSearchRepository
import com.yourcompany.facesearch.network.ImageUploadRepository
import com.yourcompany.facesearch.network.Secrets
import com.yourcompany.facesearch.vision.ImageEnhancer
import com.yourcompany.facesearch.vision.NativeFaceCropper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchMode {
    PRECISION,  // Tight face crop
    BYPASS,     // Yandex Engine + Camouflage Filter (Deep OSINT)
    SOCIAL,     // Square crop, High Contrast, Social Priority
    HYPER,      // Multi-Probe Composite (Bypass All Filters)
    RAW         // Full image
}

class CheckInViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val nativeFaceCropper = NativeFaceCropper()
    private val faceSearchRepository = FaceSearchRepository()
    private val imageUploadRepository = ImageUploadRepository()

    var uiState by mutableStateOf<CheckInUiState>(CheckInUiState.Idle)
        private set

    var capturedBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var targetHint by mutableStateOf("")

    var searchMode by mutableStateOf(SearchMode.PRECISION)

    fun onPhotoCaptured(bitmap: Bitmap) {
        capturedBitmap = bitmap
        
        viewModelScope.launch {
            val logs = mutableListOf("Initializing local optics...")
            uiState = CheckInUiState.Loading(0.1f, logs.toList())
            
            // Step 1: Local face detection / Optimization
            try {
                val processedBitmap = when (searchMode) {
                    SearchMode.RAW -> {
                        logs.add("Raw mode: Using full image...")
                        bitmap
                    }
                    SearchMode.BYPASS -> {
                        logs.add("Engaging Deep Dorking Bypass...")
                        logs.add("Switching to Yandex/Bing OSINT nodes...")
                        withContext(Dispatchers.Default) {
                            val cropped = nativeFaceCropper.cropContextual(bitmap)
                            ImageEnhancer.applyCamouflage(cropped)
                        }
                    }
                    SearchMode.HYPER -> {
                        logs.add("Engaging Cyber-Security Hyper-Probe...")
                        logs.add("Extracting structural facial fingerprint...")
                        withContext(Dispatchers.Default) {
                            val probe = nativeFaceCropper.createHyperProbe(bitmap)
                            ImageEnhancer.applyStructuralFingerprint(probe)
                        }
                    }
                    SearchMode.SOCIAL -> {
                        logs.add("Social mode: Boosting profile markers...")
                        withContext(Dispatchers.Default) {
                            val socialCrop = nativeFaceCropper.cropSocial(bitmap)
                            ImageEnhancer.applyDeepOSINT(socialCrop)
                        }
                    }
                    SearchMode.PRECISION -> {
                        logs.add("Precision mode: Tight face alignment...")
                        withContext(Dispatchers.Default) {
                            nativeFaceCropper.cropAndAlignFace(bitmap)
                        }
                    }
                }
                
                if (searchMode != SearchMode.RAW) {
                    logs.add("Optimization complete. Probe ready.")
                }
                uiState = CheckInUiState.Loading(0.25f, logs.toList())
                
                logs.add("Hosting probe image safely (ImgBB)...")
                uiState = CheckInUiState.Loading(0.4f, logs.toList())
                
                val publicUrl = imageUploadRepository.uploadImage(processedBitmap)
                
                if (publicUrl != null) {
                    logs.add("Cloud hosting successful.")
                    performWebSearch(publicUrl, logs)
                } else {
                    uiState = CheckInUiState.Error("Image hosting failed. Please try again.")
                }
            } catch (e: Exception) {
                uiState = CheckInUiState.Error("Face detection failed: ${e.message}")
            }
        }
    }

    private suspend fun performWebSearch(imageUrl: String, logs: MutableList<String>) {
        val engine = if (searchMode == SearchMode.BYPASS || searchMode == SearchMode.HYPER) "yandex_images" else "google_lens"
        
        logs.add("Engaging Deep OSINT Waterfall...")
        uiState = CheckInUiState.Loading(0.7f, logs.toList())

        val visualMatches = faceSearchRepository.performFaceSearch(
            uploadedImageUrl = imageUrl, 
            engine = engine,
            keywordHint = if (targetHint.isNotBlank()) targetHint else null,
            onLog = { logMsg ->
                logs.add(logMsg)
                // Dynamically update progress as we pivot
                val newProgress = (uiState as? CheckInUiState.Loading)?.progress?.plus(0.05f)?.coerceAtMost(0.95f) ?: 0.8f
                uiState = CheckInUiState.Loading(newProgress, logs.toList())
            }
        )

        if (visualMatches.isNotEmpty()) {
            logs.add("Analysis complete. Targets located.")
            uiState = CheckInUiState.Loading(1.0f, logs.toList())

            val mappedMatches = visualMatches.map { result ->
                WebMatchDisplay(
                    name = result.title ?: "Matched Profile",
                    source = result.source ?: "Public Record",
                    profileUrl = result.link ?: "",
                    score = result.score,
                    imageUrl = result.thumbnail
                )
            }.sortedByDescending { it.score }

            uiState = CheckInUiState.Success(matches = mappedMatches)
        } else {
            uiState = CheckInUiState.NoMatch
        }
    }

    fun onRetry() {
        uiState = CheckInUiState.Idle
        capturedBitmap = null
    }

    override fun onCleared() {
        super.onCleared()
    }
}
