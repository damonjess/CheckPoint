package com.yourcompany.facesearch.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.facesearch.network.ApiClient
import com.yourcompany.facesearch.network.ApifyRepository
import com.yourcompany.facesearch.network.FaceSearchRepository
import com.yourcompany.facesearch.network.ImageUploadRepository
import com.yourcompany.facesearch.network.Secrets
import com.yourcompany.facesearch.vision.FaceEmbedder
import com.yourcompany.facesearch.vision.FreeFaceSearchHelper
import com.yourcompany.facesearch.vision.ImageEnhancer
import com.yourcompany.facesearch.vision.NativeFaceCropper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchMode {
    PRECISION,  // Tight face crop
    BYPASS,     // Yandex Engine + Camouflage Filter (Deep OSINT)
    SOCIAL,     // Square crop, High Contrast, Social Priority
    HYPER,      // Multi-Probe Composite (Bypass All Filters)
    RAW,        // Full image
    FREE        // Multi-Engine Web Browser Search (No API Cost)
}

class CheckInViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val nativeFaceCropper = NativeFaceCropper()
    private val faceSearchRepository = FaceSearchRepository()
    private val imageUploadRepository = ImageUploadRepository()
    private val apifyRepository = ApifyRepository()
    private val faceEmbedder = FaceEmbedder(application)
    private val freeSearch = FreeFaceSearchHelper(application)

    var uiState by mutableStateOf<CheckInUiState>(CheckInUiState.Idle)
        private set

    var capturedBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var targetHint by mutableStateOf("")

    var searchMode by mutableStateOf(SearchMode.PRECISION)

    var debugMode by mutableStateOf(false)
    
    private var sourceEmbedding: FloatArray? = null

    // For Fragment-based navigation support
    fun startCamera() {
        // Logic to trigger camera in Activity/Fragment
    }

    fun openGallery() {
        // Logic to trigger gallery in Activity/Fragment
    }

    fun onImageReady(bitmap: Bitmap, nameHint: String?) {
        targetHint = nameHint ?: ""
        when (searchMode) {
            SearchMode.FREE -> {
                val helper = FreeFaceSearchHelper(getApplication())
                helper.searchMyPhoto(bitmap, nameHint)
            }
            else -> {
                onPhotoCaptured(bitmap)
            }
        }
    }

    fun onPhotoCaptured(bitmap: Bitmap) {
        capturedBitmap = bitmap
        
        viewModelScope.launch {
            val logs = mutableListOf("Initializing local optics...")
            uiState = CheckInUiState.Loading(0.1f, logs.toList())
            
            // Step 1: Quality Gate
            if (searchMode != SearchMode.RAW) {
                logs.add("Running Quality Gate analysis...")
                val quality = nativeFaceCropper.validateFaceQuality(bitmap)
                if (!quality.isGood) {
                    uiState = CheckInUiState.Error(quality.message)
                    return@launch
                }
            }

            // Step 2: Local face detection / Optimization
            try {
                val processedBitmap = when (searchMode) {
                    SearchMode.RAW -> {
                        logs.add("Raw mode: Using full image...")
                        bitmap
                    }
                    SearchMode.BYPASS -> {
                        logs.add("Engaging Deep Dorking Bypass...")
                        withContext(Dispatchers.Default) {
                            val cropped = nativeFaceCropper.cropContextual(bitmap)
                            ImageEnhancer.applyCamouflage(cropped)
                        }
                    }
                    SearchMode.HYPER -> {
                        logs.add("Engaging Cyber-Security Hyper-Probe...")
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
                    SearchMode.FREE -> {
                        logs.add("Free mode: Optimizing for web search...")
                        withContext(Dispatchers.Default) {
                            nativeFaceCropper.cropAndAlignFace(bitmap)
                        }
                    }
                }
                
                if (searchMode == SearchMode.FREE) {
                    logs.add("Face alignment complete.")
                    
                    var finalBitmap = processedBitmap
                    if (Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory() < 150 * 1024 * 1024) {
                        logs.add("Low memory detected - using lower resolution crop.")
                        val targetSize = 600
                        if (finalBitmap.width > targetSize) {
                            val scale = targetSize.toFloat() / finalBitmap.width
                            finalBitmap = Bitmap.createScaledBitmap(
                                finalBitmap,
                                (finalBitmap.width * scale).toInt(),
                                (finalBitmap.height * scale).toInt(),
                                true
                            )
                        }
                    }
                    
                    uiState = CheckInUiState.Confirming(finalBitmap)
                    return@launch
                }

                // Extract embedding for local verification later
                if (searchMode != SearchMode.RAW) {
                    logs.add("Extracting biometric signature...")
                    sourceEmbedding = withContext(Dispatchers.Default) {
                        // For embedding, we use the BEST face crop, usually PRECISION-like
                        val alignForEmbed = nativeFaceCropper.cropAndAlignFace(bitmap)
                        faceEmbedder.getEmbedding(alignForEmbed)
                    }
                    if (sourceEmbedding == null) {
                        logs.add("Warning: Biometric signature extraction failed (Low Quality).")
                    }
                }

                if (searchMode != SearchMode.RAW) {
                    logs.add("Optimization complete. Probe ready.")
                }
                uiState = CheckInUiState.Loading(0.25f, logs.toList())
                
                logs.add("Hosting probe image safely (ImgBB)...")
                uiState = CheckInUiState.Loading(0.4f, logs.toList())
                
                val uploadBitmap = ImageEnhancer.prepareImageForSearch(processedBitmap)
                val publicUrl = imageUploadRepository.uploadImage(uploadBitmap)
                
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
        logs.add("Engaging Deep OSINT Waterfall...")
        uiState = CheckInUiState.Loading(0.7f, logs.toList())

        val visualMatches = faceSearchRepository.performFaceSearch(
            uploadedImageUrl = imageUrl, 
            keywordHint = if (targetHint.isNotBlank()) targetHint else null,
            onLog = { logMsg ->
                logs.add(logMsg)
                val newProgress = (uiState as? CheckInUiState.Loading)?.progress?.plus(0.05f)?.coerceAtMost(0.95f) ?: 0.8f
                uiState = CheckInUiState.Loading(newProgress, logs.toList())
            }
        )

        if (visualMatches.isNotEmpty()) {
            logs.add("Analysis complete. Targets located.")
            
            // DEEP SCRAPE BYPASS: If in HYPER mode and we found a high-score social profile, 
            // scrape it directly to find more photos for verification.
            if (searchMode == SearchMode.HYPER) {
                val topSocialMatch = visualMatches.firstOrNull { 
                    it.score > 3000 && (it.link?.contains("instagram") == true || it.link?.contains("facebook") == true)
                }
                if (topSocialMatch != null) {
                    logs.add("High-Confidence Signal Detected.")
                    logs.add("Scraping site directly via Apify Bypass...")
                    val extraPhotos = apifyRepository.deepScrapeProfile(topSocialMatch.link!!) { logs.add(it) }
                    if (extraPhotos.isNotEmpty()) {
                        logs.add("Successfully bypassed site wall. ${extraPhotos.size} extra data points found.")
                    }
                }
            }

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

    fun onConfirmFreeSearch(bitmap: Bitmap) {
        viewModelScope.launch {
            uiState = CheckInUiState.Loading(1.0f, listOf("Launching browser-based search nodes..."))
            freeSearch.searchMyPhoto(bitmap, targetHint)
            delay(1000)
            uiState = CheckInUiState.Idle
        }
    }

    fun onGoogleLensOnlySearch(bitmap: Bitmap) {
        viewModelScope.launch {
            uiState = CheckInUiState.Loading(1.0f, listOf("Launching Google Lens node..."))
            freeSearch.openGoogleLensOnly(bitmap, targetHint)
            delay(1000)
            uiState = CheckInUiState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}
