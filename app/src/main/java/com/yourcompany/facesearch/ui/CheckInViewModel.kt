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
import com.yourcompany.facesearch.network.SerpVisualMatch
import com.yourcompany.facesearch.vision.FaceEmbedder
import com.yourcompany.facesearch.vision.FreeFaceSearchHelper
import com.yourcompany.facesearch.vision.GemmaAnalyzer
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
    HYPER,      // ULTIMATE: Combined Biometric + OSINT Cross-Correlation (Screenshot Tools)
    RAW,        // Full image
    FREE,       // Multi-Engine Web Browser Search (No API Cost)
    SOCIAL_OPTIMIZED,  // Optimized for finding you on social media (1:1 profile crop)
    AGGRESSIVE  // NEW: FaceCheck.ID Biometric Scan (Bypasses Search Engines)
}

class CheckInViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val nativeFaceCropper = NativeFaceCropper()
    private val faceSearchRepository = FaceSearchRepository()
    private val imageUploadRepository = ImageUploadRepository()
    private val apifyRepository = ApifyRepository()
    private val faceEmbedder = FaceEmbedder(application)
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

    // For Fragment-based navigation support
    fun startCamera() {
        // Logic to trigger camera in Activity/Fragment
    }

    fun openGallery() {
        // Logic to trigger gallery in Activity/Fragment
    }

    fun onImageReady(bitmap: Bitmap, nameHint: String?) {
        targetHint = nameHint?.trim() ?: ""
        when (searchMode) {
            SearchMode.FREE -> {
                viewModelScope.launch {
                    freeSearch.searchMyPhoto(bitmap, nameHint)
                }
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
            
            // Log memory state
            val maxMemory = Runtime.getRuntime().maxMemory() / 1024 / 1024
            val totalMemory = Runtime.getRuntime().totalMemory() / 1024 / 1024
            logs.add("System: Memory $totalMemory MB / $maxMemory MB")
            uiState = CheckInUiState.Loading(0.15f, logs.toList())
            
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
                            val enhanced = ImageEnhancer.applyCamouflage(cropped)
                            if (cropped != bitmap) cropped.recycle()
                            enhanced
                        }
                    }
                    SearchMode.HYPER -> {
                        logs.add("Engaging Cyber-Security Hyper-Probe...")
                        withContext(Dispatchers.Default) {
                            val probe = nativeFaceCropper.createHyperProbe(bitmap)
                            val fingerprinted = ImageEnhancer.applyStructuralFingerprint(probe)
                            if (probe != bitmap) probe.recycle()
                            fingerprinted
                        }
                    }
                    SearchMode.SOCIAL -> {
                        logs.add("Social mode: Natural Context Scoping...")
                        withContext(Dispatchers.Default) {
                            // Contextual crop helps engines recognize "real person" vibes
                            nativeFaceCropper.cropSocial(bitmap)
                        }
                    }
                    SearchMode.AGGRESSIVE -> {
                        logs.add("AGGRESSIVE mode: Deep Biometric & Social Probe...")
                        withContext(Dispatchers.Default) {
                            // Using a 1:1 profile crop is actually better for both biometric and social matching
                            nativeFaceCropper.cropForSocialProfile(bitmap)
                        }
                    }
                    
                    SearchMode.SOCIAL_OPTIMIZED -> {
                        logs.add("Social Optimized mode: 1:1 Profile Picture Alignment...")
                        withContext(Dispatchers.Default) {
                            // Square crop matches standard profile pic aspect ratios
                            nativeFaceCropper.cropForSocialProfile(bitmap)
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
                    logs.add("Maximum search probing - skipping local verification...")
                    
                    var finalBitmap = processedBitmap
                    var shouldRecycleProcessed = false
                    
                    if (Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory() < 150 * 1024 * 1024) {
                        logs.add("Low memory detected - using lower resolution crop.")
                        val targetSize = 600
                        if (finalBitmap.width > targetSize) {
                            val scale = targetSize.toFloat() / finalBitmap.width
                            val scaledBitmap = Bitmap.createScaledBitmap(
                                finalBitmap,
                                (finalBitmap.width * scale).toInt(),
                                (finalBitmap.height * scale).toInt(),
                                true
                            )
                            if (finalBitmap != bitmap) {
                                finalBitmap.recycle()
                                shouldRecycleProcessed = false
                            }
                            finalBitmap = scaledBitmap
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
                
                val uploadBitmap = nativeFaceCropper.prepareFaceForSearch(processedBitmap)
                
                if (searchMode == SearchMode.HYPER) {
                    logs.add("Engaging Multi-Engine OSINT Waterfall (Free Mode)...")
                    // Removed FaceCheck.ID (Paid) - switching to standard high-depth OSINT
                }

                logs.add("Hosting probe image safely (ImgBB)...")
                uiState = CheckInUiState.Loading(0.4f, logs.toList())
                
                val publicUrl = imageUploadRepository.uploadImage(uploadBitmap)
                
                if (publicUrl != null) {
                    logs.add("Cloud hosting successful.")
                    android.util.Log.d("NetworkDebug", "-----------------------------------------")
                    android.util.Log.d("NetworkDebug", "TEST URL FOR SERPAPI PLAYGROUND: $publicUrl")
                    android.util.Log.d("NetworkDebug", "-----------------------------------------")
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

        val trimmedHint = targetHint.trim()
        val isDeepSearch = searchMode == SearchMode.HYPER || searchMode == SearchMode.AGGRESSIVE
        
        val visualMatches = mutableListOf<SerpVisualMatch>()

        if (isDeepSearch) {
            logs.add("ULTIMATE PROBE: Engaging Biometric Scan (FaceCheck.ID)...")
            val faceCheckResults = faceSearchRepository.performFaceCheckSearch(
                bitmap = capturedBitmap!!,
                onLog = { logMsg ->
                    logs.add(logMsg)
                    val currentProgress = (uiState as? CheckInUiState.Loading)?.progress ?: 0.5f
                    uiState = CheckInUiState.Loading((currentProgress + 0.01f).coerceAtMost(0.95f), logs.toList())
                }
            )
            visualMatches.addAll(faceCheckResults)
            
            if (visualMatches.isEmpty()) {
                logs.add("Biometric results negative. Expanding search to Visual OSINT Engines...")
            } else {
                logs.add("Biometric hits confirmed. Cross-correlating with Visual Engines...")
            }
        }

        // Always run standard engines for HYPER, or if AGGRESSIVE failed
        if (searchMode != SearchMode.AGGRESSIVE || visualMatches.isEmpty()) {
            val standardResults = faceSearchRepository.performFaceSearch(
                uploadedImageUrl = imageUrl,
                keywordHint = if (trimmedHint.isNotBlank()) trimmedHint else null,
                onLog = { logMsg ->
                    logs.add(logMsg)
                    val currentProgress = (uiState as? CheckInUiState.Loading)?.progress ?: 0.5f
                    uiState = CheckInUiState.Loading((currentProgress + 0.02f).coerceAtMost(0.95f), logs.toList())
                }
            )
            visualMatches.addAll(standardResults)
        }

        if (visualMatches.isNotEmpty()) {
            logs.add("Analysis complete. ${visualMatches.size} targets located.")
            
            // Map matches first to handle sorting later
            val mappedMatches = visualMatches.map { result ->
                WebMatchDisplay(
                    name = result.title ?: "Matched Profile",
                    source = result.source ?: "Public Record",
                    profileUrl = result.link ?: "",
                    score = result.score,
                    imageUrl = result.thumbnail
                )
            }.toMutableList()

            // DEEP SCRAPE BYPASS: If in HYPER or AGGRESSIVE mode, scrape top profiles
            if (searchMode == SearchMode.HYPER || searchMode == SearchMode.AGGRESSIVE) {
                // Try to scrape the top 2 social matches
                val socialIndices = mappedMatches.mapIndexedNotNull { index, match ->
                    if (match.profileUrl.contains("instagram") || 
                        match.profileUrl.contains("facebook") ||
                        match.profileUrl.contains("linkedin") ||
                        match.profileUrl.contains("tiktok")) index else null
                }.take(3)

                if (socialIndices.isNotEmpty()) {
                    logs.add("Social Targets Identified. Engaging Scraper Bypass...")
                    socialIndices.forEach { index ->
                        val match = mappedMatches[index]
                        logs.add("Scraping ${match.source} directly...")
                        val extraPhotos = apifyRepository.deepScrapeProfile(match.profileUrl) { logs.add(it) }
                        if (extraPhotos.isNotEmpty()) {
                            logs.add("✓ Harvested ${extraPhotos.size} extra photos from ${match.source}.")
                            mappedMatches[index] = match.copy(extraImages = extraPhotos)
                        }
                    }
                }
            }

            uiState = CheckInUiState.Loading(1.0f, logs.toList())

            val finalMatches = mappedMatches.sortedByDescending { it.score }

            uiState = CheckInUiState.Success(matches = finalMatches, gemmaAnalysis = null)
        } else {
            uiState = CheckInUiState.NoMatch(logs.toList())
        }
    }

    fun onRetry() {
        uiState = CheckInUiState.Idle
        capturedBitmap = null
    }

    fun onConfirmFreeSearch(bitmap: Bitmap) {
        viewModelScope.launch {
            val original = capturedBitmap ?: bitmap
            
            if (searchMode == SearchMode.AGGRESSIVE || searchMode == SearchMode.HYPER) {
                // User wants Deep Search / Social Bypass - use the API flow instead of redirecting to browser
                uiState = CheckInUiState.Loading(0.1f, listOf("INITIATING DEEP SOCIAL BYPASS..."))
                onPhotoCaptured(original)
            } else {
                // Use browser-based FREE search
                uiState = CheckInUiState.Loading(1.0f, listOf("Launching browser-based search nodes..."))
                freeSearch.searchMyPhoto(original, targetHint)
                delay(1000)
                uiState = CheckInUiState.Idle
            }
        }
    }

    fun onGoogleLensOnlySearch(bitmap: Bitmap, nameHint: String? = null) {
        if (nameHint != null) targetHint = nameHint.trim()
        viewModelScope.launch {
            val original = capturedBitmap ?: bitmap
            uiState = CheckInUiState.Loading(1.0f, listOf("Launching Google Lens node..."))
            freeSearch.openGoogleLensOnly(original, targetHint)
            delay(1000)
            uiState = CheckInUiState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        gemmaAnalyzer.close()
    }
}
