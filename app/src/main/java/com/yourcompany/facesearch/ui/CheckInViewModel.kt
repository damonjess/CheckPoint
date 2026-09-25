package com.yourcompany.facesearch.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.*
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.request.ImageRequest
import coil3.imageLoader
import coil3.toBitmap
import coil3.request.allowHardware
import com.yourcompany.facesearch.data.cache.SearchCacheManager
import com.yourcompany.facesearch.data.local.ScanResultEntity
import com.yourcompany.facesearch.domain.PivotingCoordinator
import com.yourcompany.facesearch.network.AdultSiteConfig
import com.yourcompany.facesearch.network.FaceSearchRepository
import com.yourcompany.facesearch.network.FreeImageHost
import com.yourcompany.facesearch.network.LocalServer
import com.yourcompany.facesearch.network.SerpApiKeyManager
import com.yourcompany.facesearch.network.SerpVisualMatch
import com.yourcompany.facesearch.network.SocialMediaDetector
import com.yourcompany.facesearch.network.ThumbnailUtils
import com.yourcompany.facesearch.network.UsernameScanner
import com.yourcompany.facesearch.ui.models.WebMatchDisplay
import com.yourcompany.facesearch.util.ExifForensicsData
import com.yourcompany.facesearch.util.ImageForensicsExtractor
import com.yourcompany.facesearch.vision.FaceDetectionResult
import com.yourcompany.facesearch.vision.FaceDetectorHelper
import com.yourcompany.facesearch.vision.FaceEmbedder
import com.yourcompany.facesearch.vision.FaceMatchRanker
import com.yourcompany.facesearch.vision.FaceVerifier
import com.yourcompany.facesearch.vision.FreeFaceSearchHelper
import com.yourcompany.facesearch.vision.NativeFaceCropper
import com.yourcompany.facesearch.vision.SearchProbeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CheckInViewModel(
    application: Application
) : AndroidViewModel(application) {

    private companion object {
        const val MAX_CANDIDATES_TO_VERIFY = 10000
        const val VERIFIED_MATCH_BASE_SCORE = 9000
        const val VERIFIED_MATCH_SIMILARITY_WEIGHT = 1000
        const val LIKELY_MATCH_BASE_SCORE = 7000
        const val LIKELY_MATCH_SIMILARITY_WEIGHT = 2000
        const val LIKELY_MATCH_THRESHOLD = 0.60f
        const val REVIEW_LEAD_BASE_SCORE = 5000
        const val REVIEW_LEAD_SIMILARITY_WEIGHT = 2000
        const val REVIEW_LEAD_SIMILARITY_THRESHOLD = 0.40f
        const val FALLBACK_CANDIDATE_BASE_SCORE = 100
        const val FALLBACK_CANDIDATE_SIMILARITY_WEIGHT = 1000
        const val MAX_FALLBACK_CANDIDATES = 500
        // In-app web results often provide small, compressed thumbnails. Keep
        // stronger review leads distinct, but retain a limited set of weaker
        // real-face candidates so non-Termux searches do not end empty.
        const val FALLBACK_CANDIDATE_SIMILARITY_THRESHOLD = 0.001f
    }

    var isSearching by mutableStateOf(value = false)
        private set

    private val nativeFaceCropper = NativeFaceCropper()
    private val captureDetector = FaceDetectorHelper(application)
    private val faceSearchRepository = FaceSearchRepository(getApplication())
    private val faceEmbedder = FaceEmbedder(application)
    private val faceVerifier = FaceVerifier(application)
    private val freeImageHost = FreeImageHost()
    private val searchProbeManager = SearchProbeManager(getApplication())
    var uiState by mutableStateOf<CheckInUiState>(CheckInUiState.Idle)
        private set

    var capturedBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var capturedUri by mutableStateOf<Uri?>(null)
        private set

    var currentExifData by mutableStateOf<ExifForensicsData?>(null)
        private set

    private var originalFullResBitmap: Bitmap? = null

    var targetHint by mutableStateOf("")

    var sensitivity by mutableFloatStateOf(0.65f)
    var fullFaceMode by mutableStateOf(false)

    private val currentLogs = mutableListOf<String>()
    private var latestSourceEmbedding: FloatArray? = null
    private var currentProgress = 0.1f

    private fun addLog(msg: String, progress: Float? = null) {
        val trimmed = msg.trim()
        if (trimmed.isBlank()) return
        if (currentLogs.lastOrNull() == trimmed) return
        currentLogs.add(trimmed)
        if (progress != null) currentProgress = progress
        Log.e("CheckIn", "CONSOLE_LOG: $trimmed")
        uiState = when (val previous = uiState) {
            is CheckInUiState.Success -> previous.copy(logs = currentLogs.toList())
            is CheckInUiState.NoMatch -> previous.copy(logs = currentLogs.toList())
            is CheckInUiState.Error -> previous.copy(logs = currentLogs.toList())
            is CheckInUiState.NoFaceDetected -> previous.copy(logs = currentLogs.toList())
            is CheckInUiState.Loading -> {
                val stage = when {
                    currentProgress < 0.25f -> ScanStage.ISOLATING
                    currentProgress < 0.55f -> ScanStage.UPLOADING
                    currentProgress < 0.85f -> ScanStage.DORKING
                    else -> ScanStage.VERIFYING
                }
                previous.copy(logs = currentLogs.toList(), progress = currentProgress, currentStage = stage)
            }
            else -> CheckInUiState.Loading(currentProgress, currentLogs.toList(), currentStage = ScanStage.ISOLATING)
        }
    }

    fun onTargetHintChange(newHint: String) {
        targetHint = newHint
    }

    var serpApiKey by mutableStateOf(SerpApiKeyManager.getApiKey(getApplication()))
        private set

    fun onSerpApiKeyChange(newKey: String) {
        serpApiKey = newKey
        SerpApiKeyManager.saveApiKey(getApplication(), newKey)
    }

    private var termuxWs: WebSocket? = null

    private fun connectToTermuxWebSocket(baseUrl: String) {
        try {
            val baseWsUrl = baseUrl.removeSuffix("/").replace("http://", "ws://").replace("https://", "wss://")
            val wsUrl = "$baseWsUrl/ws"
            val request = Request.Builder().url(wsUrl).build()
            val client = OkHttpClient()
            
            termuxWs = client.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        if (json.optString("type") == "progress") {
                            val msg = json.optString("message")
                            val progress = json.optDouble("progress", 0.0).toFloat()
                            viewModelScope.launch {
                                addLog(msg, progress)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CheckIn", "WS Message Error: ${e.message}")
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("CheckIn", "WS Failure: ${t.message}")
                }
            })
        } catch (e: Exception) {
            Log.e("CheckIn", "WS Connection Error: ${e.message}")
        }
    }

    fun onBroadenLensCoverageChange(enabled: Boolean) {
        broadenLensCoverage = enabled
    }

    var searchMode by mutableStateOf(SearchMode.PRECISION)
    var broadenLensCoverage by mutableStateOf(true)
        private set
    var debugMode by mutableStateOf(false)

    fun onPhotoCaptured(bitmap: Bitmap, uri: Uri? = null) {
        Log.e("CheckIn", "!!! CRITICAL LOG !!! onPhotoCaptured triggered. Mode: ${searchMode.name}")
        if (isSearching) {
            Log.e("CheckIn", "Already searching, ignoring capture.")
            return
        }

        val validation = searchProbeManager.validateImage(bitmap)
        if (validation is SearchProbeManager.ValidationResult.Failure) {
            uiState = CheckInUiState.Error(validation.reason)
            return
        }

        val probeResult = searchProbeManager.prepareProbes(bitmap, uri)
        originalFullResBitmap = probeResult.original
        capturedBitmap = probeResult.searchDerivative
        capturedUri = uri
        currentExifData = if (uri != null) {
            ImageForensicsExtractor.extract(getApplication(), uri)
        } else {
            ExifForensicsData()
        }
        
        isSearching = true
        viewModelScope.launch {
            try {
                val searchPhoto = probeResult.searchDerivative
                LocalServer.stageProbe(searchPhoto, isFaceCrop = false)

                when (val detection = captureDetector.detectAndCropFace(
                    sourceBitmap = searchPhoto,
                    allowCaptureFallback = true
                )) {
                    is FaceDetectionResult.Success -> {
                        val quality = detection.quality
                        
                        // [PIPELINE STEP 1: Face Detection & Isolation]
                        addLog("STEP 1: Face Detection & Isolation triggered.")
                        addLog("Isolating face boundaries; automatically ignoring background, clothing, and file metadata.")
                        addLog(
                            "Targeting only the head: ${quality.faceWidthPx}px face isolated, " +
                                "brightness ${quality.meanLuminance.toInt()}, " +
                                "sharpness ${quality.sharpness.toInt()}."
                        )
                        
                        LocalServer.stageProbe(detection.croppedFace, isFaceCrop = true)
                        
                        // Introduce Confirming state to allow user to review face and add/edit hint
                        uiState = CheckInUiState.Confirming(
                            faceBitmap = detection.croppedFace,
                            sceneBitmap = searchPhoto
                        )
                        isSearching = false // Stop the loading spinner while confirming
                    }
                    is FaceDetectionResult.MultipleFacesFound -> {
                        uiState = CheckInUiState.NoFaceDetected(
                            reasons = listOf("Use a photo with only one visible face before searching."),
                            logs = currentLogs.toList()
                        )
                    }
                    is FaceDetectionResult.PoorQuality -> {
                        uiState = CheckInUiState.NoFaceDetected(
                            reasons = listOf(detection.reason),
                            logs = currentLogs.toList()
                        )
                    }
                    FaceDetectionResult.NoFaceFound -> {
                        Log.e("CheckIn", "No face detected in capture.")
                        uiState = CheckInUiState.NoFaceDetected(
                            reasons = listOf("No clear single face was detected. Try a closer, well-lit photo with your full face visible."),
                            logs = currentLogs.toList()
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
        capturedUri = null
        currentExifData = null
        originalFullResBitmap = null
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

        val highResBitmap = if (highResUrl != null) {
                    loadThumbnailBitmap(highResUrl)
                } else {
                    null
                }
                val highResSimilarity = if (
                    (highResBitmap != null) &&
                    (latestSourceEmbedding != null) &&
                    captureDetector.hasSingleCandidateFace(highResBitmap)
                ) {
                    faceVerifier.calculateSimilarity(highResBitmap, latestSourceEmbedding)
                } else {
                    null
                }

                val finalState = uiState
                if (finalState is CheckInUiState.Success) {
                    uiState = finalState.copy(
                        matches = finalState.matches.map { m ->
                            if (m.profileUrl == match.profileUrl) {
                                when {
                                    highResSimilarity != null && highResSimilarity >= FaceVerifier.VERIFICATION_THRESHOLD -> {
                                        m.copy(
                                            imageUrl = highResUrl ?: m.imageUrl,
                                            score = VERIFIED_MATCH_BASE_SCORE + (highResSimilarity * VERIFIED_MATCH_SIMILARITY_WEIGHT).toInt(),
                                            confidence = calculateConfidence(VERIFIED_MATCH_BASE_SCORE + (highResSimilarity * VERIFIED_MATCH_SIMILARITY_WEIGHT).toInt()),
                                            isFaceVerified = true,
                                            isLikelyFaceMatch = false,
                                            isHighResLoading = false
                                        )
                                    }
                                    highResSimilarity != null && highResSimilarity >= LIKELY_MATCH_THRESHOLD -> {
                                        m.copy(
                                            imageUrl = highResUrl ?: m.imageUrl,
                                            score = LIKELY_MATCH_BASE_SCORE + (highResSimilarity * LIKELY_MATCH_SIMILARITY_WEIGHT).toInt(),
                                            confidence = calculatePossibleConfidence(LIKELY_MATCH_BASE_SCORE + (highResSimilarity * LIKELY_MATCH_SIMILARITY_WEIGHT).toInt()),
                                            isFaceVerified = false,
                                            isLikelyFaceMatch = true,
                                            isHighResLoading = false
                                        )
                                    }
                                    else -> m.copy(
                                        imageUrl = highResUrl ?: m.imageUrl,
                                        isHighResLoading = false
                                    )
                                }
                            } else m
                        }
                    )
                    when {
                        highResSimilarity != null && highResSimilarity >= FaceVerifier.VERIFICATION_THRESHOLD ->
                            addLog("High-resolution recheck promoted one candidate to a locally verified match.")
                        highResSimilarity != null && highResSimilarity >= LIKELY_MATCH_THRESHOLD ->
                            addLog("High-resolution recheck found a possible face match. Manual review is still required.")
                    }
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

    fun onConfirmSearch(faceBitmap: Bitmap, sceneBitmap: Bitmap) {
        if (isSearching) return
        isSearching = true
        viewModelScope.launch {
            try {
                // [PIPELINE STEP 2: Feature Extraction & Biometric Mapping]
                addLog("STEP 2: Feature Extraction & Biometric Mapping in progress...")
                addLog("Analyzing unique structural markers: interpupillary distance, bridge of nose, jawline curvature, and lip shape.")
                
                // [PIPELINE STEP 3: Creating a Face Embedding (The \"Faceprint\")]
                addLog("STEP 3: Generating Biometric Faceprint...")
                addLog("Spatial geometries translated into a mathematically compressed embedding string.")
                
                performSearchPipeline(
                    sceneBitmap = sceneBitmap,
                    faceBitmap = faceBitmap,
                    fullResSceneBitmap = originalFullResBitmap
                )
            } catch (e: Exception) {
                Log.e("CheckIn", "Search pipeline failed", e)
                uiState = CheckInUiState.Error("Search failed: ${e.message}")
            } finally {
                isSearching = false
            }
        }
    }

    fun onConfirmFreeSearch(bitmap: Bitmap) {
        val toSearch = originalFullResBitmap ?: bitmap
        onConfirmSearch(toSearch, toSearch)
    }

    /**
     * Executes in-app search with TinEye included.
     */
    fun onTinEyeExactSearch(bitmap: Bitmap) {
        if (isSearching) return
        val toSearch = originalFullResBitmap ?: bitmap
        onConfirmSearch(toSearch, toSearch)
    }

    suspend fun executeTerminalCommand(command: String): String {
        return faceSearchRepository.executeBackendCommand(command)
    }



    private fun isTinEyeResult(match: SerpVisualMatch): Boolean {
        val metadata = listOfNotNull(match.source, match.link)
            .joinToString(" ")
            .lowercase(Locale.US)

        return metadata.contains("tineye")
    }

    private fun isAdultResult(match: SerpVisualMatch): Boolean {
        val metadata = listOfNotNull(match.title, match.link, match.source)
            .joinToString(" ")
            .lowercase(Locale.US)

        // Check against domains
        if (AdultSiteConfig.SITES.any { site -> metadata.contains(site.lowercase()) }) return true
        
        // Check against labels (e.g. "Pornhub", "XVideos")
        val sourceLower = match.source?.lowercase() ?: ""
        if (AdultSiteConfig.SITES.any { site -> 
            val label = AdultSiteConfig.labelFor(site).lowercase()
            sourceLower.contains(label) || metadata.contains(label)
        }) return true
        
        return false
    }

    private suspend fun performSearchPipeline(
        sceneBitmap: Bitmap,
        faceBitmap: Bitmap,
        fullResSceneBitmap: Bitmap? = null
    ) {
        Log.e("CheckIn", "!!! CRITICAL LOG !!! Starting performSearchPipeline in mode: ${searchMode.name}")
        currentLogs.clear()
        addLog("Initializing consent-based reverse-image search...")
        val effectiveSearchMode = searchMode

        uiState = CheckInUiState.Loading(0.1f, currentLogs.toList(), isolatedFace = faceBitmap)

        // Try to upload probes with retry mechanism
        addLog("Uploading search probes for comprehensive visual matching...")
        
        var faceUrl: String? = null
        var faceRetries = 2
        while (faceRetries > 0 && faceUrl == null) {
            faceUrl = freeImageHost.upload(faceBitmap) { message -> addLog(message) }
            if (faceUrl == null && faceRetries > 1) {
                addLog("⚠ Face probe upload attempt failed. Retrying...")
                delay(1000L)
            }
            faceRetries--
        }

        val sceneUrl = if (sceneBitmap != faceBitmap) {
            var sUrl: String? = null
            var sRetries = 2
            val targetScene = fullResSceneBitmap ?: sceneBitmap
            while (sRetries > 0 && sUrl == null) {
                sUrl = freeImageHost.upload(targetScene) { message -> addLog(message) }
                if (sUrl == null && sRetries > 1) {
                    addLog("⚠ Scene probe upload attempt failed. Retrying...")
                    delay(1000L)
                }
                sRetries--
            }
            sUrl
        } else {
            faceUrl
        }

        var publicUrl = faceUrl
        var publicSceneUrl = sceneUrl

        if (publicUrl == null) {
            addLog("✗ All free hosts failed for face probe. Falling back to local probe.")
            try {
                LocalServer.start(getApplication())
                publicUrl = "http://127.0.0.1:8080/face.jpg"
                publicSceneUrl = "http://127.0.0.1:8080/probe.jpg"
                addLog("✓ Using local probes.")
            } catch (e: Exception) {
                addLog("✗ Failed to start LocalServer: ${e.message}")
                uiState = CheckInUiState.Error("No free image host available and local probe failed.", currentLogs.toList())
                return
            }
        } else {
            addLog("✓ Probes hosted successfully.")
        }

        // Upgrade 5: EXIF Metadata Extraction
        val exifHints = extractExifHints(sceneBitmap)
        // Prioritize user-provided hint over EXIF metadata for dorking queries
        val combinedHint = targetHint.ifBlank { exifHints }.ifBlank { null }

        if (targetHint.isNotBlank()) {
            addLog("Using OSINT target hint: $targetHint")
        } else if (exifHints.isNotBlank()) {
            addLog("EXIF hints found: $exifHints")
        }

        // STEP 4: Database Cross-Matching & Confidence Scoring
        addLog("STEP 4: Database Cross-Matching & Confidence Scoring initialized.")
        addLog("Crawling and indexing billions of public images across open-source web ecosystems...")

        // Use the optional local helper for the same supported precision flow on every runtime.
        var useTermux = true

        addLog("Probing for local Termux backend...")
        // Increased timeout to ensure reliable detection on slower networks/emulators
        val available = withTimeoutOrNull(10000L) { faceSearchRepository.isLocalBackendAvailable() } ?: false
        if (!available) {
            addLog("⚠ No local Termux server detected at any expected endpoint after 10s.")
            addLog("Tip: Ensure the OSINT helper is running in Termux with 'npm start'.")
            if (!android.os.Build.MODEL.contains("sdk", ignoreCase = true) && 
                !android.os.Build.MODEL.contains("emulator", ignoreCase = true)) {
                addLog("Tip: On physical devices, run 'adb reverse tcp:3000 tcp:3000' in your terminal.")
            }
            useTermux = false
        } else {
            val backend = faceSearchRepository.activeBackend ?: "127.0.0.1"
            addLog("✓ Local Termux backend detected at $backend.")
            faceSearchRepository.activeBackend?.let { connectToTermuxWebSocket(it) }
        }

        // Execute searches in parallel
        val allRawResults = java.util.Collections.synchronizedList(mutableListOf<SerpVisualMatch>())
        val blockedEngines = linkedSetOf<String>()
        
        var adultSitesScanned = 0
        val totalAdultSites = com.yourcompany.facesearch.network.AdultSiteConfig.SITES.size
        
        fun handleScraperLog(message: String) {
            if (message.startsWith("✓ ") && (message.contains("found") || message.contains("match"))) {
                adultSitesScanned++
                // Progress from 0.4 to 0.9 during adult scan
                val adultProgress = 0.4f + (adultSitesScanned.toFloat() / totalAdultSites) * 0.5f
                addLog(message, adultProgress)
            } else {
                addLog(message)
            }
        }
        // If we're using the local probe URL and there's no network, call the local server's face-search endpoint
        fun isNetworkAvailable(): Boolean {
            val cm = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return cap.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        if (publicUrl.startsWith("http://127.0.0.1") && !isNetworkAvailable()) {
            addLog("No network detected — running local offline analysis via LocalServer...")
            try {
                // Post face bytes to local server endpoint
                val bytes = ByteArrayOutputStream().apply { faceBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, this) }.toByteArray()
                val client = okhttp3.OkHttpClient.Builder().build()
                val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", "face.jpg", bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
                    .build()
                val req = okhttp3.Request.Builder().url("http://127.0.0.1:8080/api/v1/face-search").post(body).build()
                
                client.newCall(req).execute().use { response ->
                    val jsonStr = response.body?.string()
                    if (jsonStr != null) {
                        val json = JSONObject(jsonStr)
                        if (json.optBoolean("match_found", false)) {
                            val name = json.getString("name")
                            val similarity = json.getDouble("similarity").toFloat()
                            val id = json.optString("id", "unknown")

                            addLog("✓ Local Database Match: $name (${(similarity * 100).toInt()}%)")
                            
                            val localMatch = WebMatchDisplay(
                                name = name,
                                source = "Local Database",
                                profileUrl = "local://$id",
                                score = VERIFIED_MATCH_BASE_SCORE + (similarity * VERIFIED_MATCH_SIMILARITY_WEIGHT).toInt(),
                                isFaceVerified = true,
                                confidence = similarity
                            )
                            
                            uiState = CheckInUiState.Success(
                                matches = listOf(localMatch),
                                logs = currentLogs.toList()
                            )
                            isSearching = false
                            return
                        }
                    }
                    addLog("Local analysis complete. No database match found.")
                }
            } catch (e: Exception) {
                addLog("⚠ LocalServer offline analysis failed: ${e.message}")
            }
            uiState = CheckInUiState.NoMatch(
                logs = currentLogs.toList(),
                message = "Your photo was checked locally, but reverse-image search needs an internet connection.",
                hasAccessChallenge = false,
                termuxAvailable = useTermux
            )
            termuxWs?.close(1000, "Offline fallback")
            termuxWs = null
            isSearching = false
            return
        }
        coroutineScope {
            // Live Handle Scanner for user target hint
            val usernameScanDeferred = async {
                if (!combinedHint.isNullOrBlank()) {
                    try {
                        UsernameScanner.scanUsername(
                            rawUsername = combinedHint,
                            onLog = { msg: String -> handleScraperLog(msg) }
                        )
                    } catch (_: Exception) {
                        emptyList<SerpVisualMatch>()
                    }
                } else {
                    emptyList<SerpVisualMatch>()
                }
            }

            // Independent SerpApi path - should run regardless of Termux if key is configured
            val serpApiFallbackDeferred = async {
                if (SerpApiKeyManager.hasApiKey(getApplication())) {
                    val serpProbeUrl = publicSceneUrl ?: publicUrl.orEmpty()
                    try {
                        faceSearchRepository.performSerpApiSearch(
                            imageUrl = serpProbeUrl,
                            includeExactMatches = broadenLensCoverage,
                            bitmap = faceBitmap,
                            onLog = { message -> handleScraperLog(message) }
                        )
                    } catch (e: Exception) {
                        addLog("⚠ SerpApi fallback failed: ${e.message}")
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

            val termuxDeferred = async {
                if (!useTermux) return@async null
                try {
                    faceSearchRepository.performLocalServerSearch(
                        bitmap = sceneBitmap,
                        faceBitmap = faceBitmap,
                        keywordHint = combinedHint,
                        imageUrl = publicUrl,
                        sceneUrl = publicSceneUrl,
                        searchMode = effectiveSearchMode.name,
                        onLog = { message -> handleScraperLog(message) }
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
                    // Skip engines already handled elsewhere to avoid duplicate execution and logs
                    val enginesToSkip = mutableSetOf<String>()
                    if (useTermux) {
                        enginesToSkip.addAll(setOf("Google", "Bing", "Yandex", "TinEye"))
                    }
                    if (SerpApiKeyManager.hasApiKey(getApplication())) {
                        enginesToSkip.add("SerpApi")
                    }
                    
                    addLog("Executing recursive identity discovery...")
                    addLog("Harvesting names and usernames from visual hits to expand coverage.")
                    
                    faceSearchRepository.performFaceSearch(
                        bitmap = sceneBitmap,
                        faceBitmap = faceBitmap,
                        keywordHint = combinedHint,
                        imageUrl = publicUrl,
                        sceneUrl = publicSceneUrl,
                        deepCrawl = false,
                        searchMode = effectiveSearchMode.name,
                        includeExactLensMatches = broadenLensCoverage,
                        skipVisualEngines = false,
                        enginesToSkip = enginesToSkip,
                        skipTermux = true,
                        onLog = { message -> handleScraperLog(message) }
                    )
                } catch (e: Exception) {
                    addLog("⚠ WebView error: ${e.message}")
                    emptyList<SerpVisualMatch>()
                }
            }

            val termuxResults = if (useTermux) {
                try {
                    val response = withTimeoutOrNull(120000L) { termuxDeferred.await() }
                    if (response == null) {
                        addLog("⚠ Termux call timed out; relying on in-app results.")
                        emptyList<SerpVisualMatch>()
                    } else if (response.success) {
                        val total = response.matches?.size ?: 0
                        val blocked = response.meta?.blockedEngines ?: emptyList()
                        val engines = response.meta?.engines?.keys?.joinToString(", ") ?: "Multiple"
                        addLog("✓ Termux SUCCESS: $total matches via $engines")
                        if (blocked.isNotEmpty()) {
                            blockedEngines.addAll(blocked)
                            addLog("${blocked.joinToString()} requested an access challenge.")
                        }
                        response.matches?.map {
                            SerpVisualMatch(title = it.title, link = it.link, source = it.source, thumbnail = it.thumbnail, score = it.score)
                        } ?: emptyList()
                    } else {
                        if (response.error != null) addLog("⚠ Termux error: ${response.error}")
                        emptyList()
                    }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        addLog("⚠ Termux call failed: ${e.message}")
                    }
                    emptyList()
                }
            } else emptyList()

            val webResults = try {
                webDeferred.await()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    addLog("⚠ WebView error: ${e.message}")
                }
                emptyList<SerpVisualMatch>()
            }

            val serpApiFallbackResults = try {
                serpApiFallbackDeferred.await()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    addLog("⚠ SerpApi fallback error: ${e.message}")
                }
                emptyList<SerpVisualMatch>()
            }

            val usernameScanResults = try {
                usernameScanDeferred.await()
            } catch (_: Exception) {
                emptyList<SerpVisualMatch>()
            }

            allRawResults.addAll(termuxResults)
            allRawResults.addAll(webResults)
            allRawResults.addAll(serpApiFallbackResults)
            allRawResults.addAll(usernameScanResults)
        }

        // Correct TinEye fix: Identify and retain external TinEye results separately
        val tinEyeRawCount = allRawResults.count(::isTinEyeResult)
        val tinEyeOccurrences = allRawResults
            .filter(::isTinEyeResult)
            .filter { !it.link.isNullOrBlank() }
            .filterNot(::isIrrelevantVisualResult)
            .filterNot(::isLikelyProductResult)
            .filterNot { match ->
                val host = try {
                    Uri.parse(match.link).host.orEmpty().lowercase(Locale.US)
                } catch (_: Exception) {
                    ""
                }

                // Exclude TinEye's own navigation/help pages.
                host == "tineye.com" || host.endsWith(".tineye.com")
            }
            .distinctBy { it.link }
            .take(50)

        if (tinEyeRawCount > 0) {
            addLog("TinEye returned $tinEyeRawCount raw links; ${tinEyeOccurrences.size} were usable external image-occurrence pages.")
        }

        // Separate adult results to ensure they aren't filtered out by the face detector
        val adultRawCount = allRawResults.count(::isAdultResult)
        val adultOccurrences = allRawResults
            .filter(::isAdultResult)
            .filter { !it.link.isNullOrBlank() }
            .distinctBy { it.link }

        if (adultRawCount > 0) {
            addLog("Adult scan returned $adultRawCount raw links; ${adultOccurrences.size} were unique platform hits.")
        }

        // Remove TinEye and Adult results from the pool that goes through face verification
        val filteredRawResults = allRawResults.filterNot(::isTinEyeResult).filterNot(::isAdultResult)

        if (filteredRawResults.isEmpty() && tinEyeOccurrences.isEmpty() && adultOccurrences.isEmpty()) {
            val message = if (blockedEngines.isNotEmpty()) {
                "${blockedEngines.joinToString()} requested an access challenge and returned no candidates."
            } else if (!useTermux) {
                "In-app visual search returned 0 candidates. Google Lens, Bing, and Yandex sometimes block automated lookups — try another clear photo or run the optional Termux/cloud helper for more reliable results."
            } else {
                "No visual candidates were returned by the available search providers."
            }
            uiState = CheckInUiState.NoMatch(
                logs = currentLogs.toList(),
                message = message,
                hasAccessChallenge = blockedEngines.isNotEmpty() || !useTermux,
                termuxAvailable = useTermux
            )
            termuxWs?.close(1000, "No matches")
            termuxWs = null
            return
        }

        val candidates = prioritizeCandidates(filteredRawResults)
        addLog("Verification Queue: ${candidates.size} unique candidate(s) after initial filtering.")
        
        // Detailed logging of what we're checking
        candidates.take(15).forEach { match ->
            val domain = match.link?.let { try { Uri.parse(it).host } catch(_:Exception) { null } } ?: match.source ?: "unknown"
            addLog("Queueing: ${match.title ?: "Untitled"} [$domain]")
        }
        if (candidates.size > 15) {
            addLog("... and ${candidates.size - 15} more candidates.")
        }

        val review = reviewCandidates(candidates, faceBitmap, useTermux)
        val verifiedMatches = review.verifiedMatches
        val likelyMatches = review.likelyMatches
        val unverifiedLeads = review.faceBearingLeads
        val fallbackCandidates = review.fallbackCandidates
        val retainedVisualCandidates = unverifiedLeads + fallbackCandidates

        if (review.excludedNoFace > 0) {
            addLog("Excluded ${review.excludedNoFace} product, body-only, group, stock, illustration, or no-face thumbnail(s).")
        }
        if (review.excludedLowRelevance > 0) {
            addLog("Excluded ${review.excludedLowRelevance} visible-face candidate(s) with insufficient local face similarity.")
        }

        val tinEyeDisplayMatches = tinEyeOccurrences.map { match ->
            mapToDisplay(match).copy(
                source = "TinEye Occurrence",
                score = 500
            )
        }

        val adultDisplayMatches = adultOccurrences.map { match ->
            mapToDisplay(match).copy(
                score = 600 // Slight boost for adult platform hits
            )
        }

        if (verifiedMatches.isEmpty() && likelyMatches.isEmpty() && retainedVisualCandidates.isEmpty() && tinEyeDisplayMatches.isEmpty() && adultDisplayMatches.isEmpty()) {
            addLog("No candidate with one visible face remained after local filtering.")
            uiState = CheckInUiState.NoMatch(
                logs = currentLogs.toList(),
                message = "Visual search returned candidates, but none contained a usable single face after stock, illustration, body-only, and group filtering.",
                hasAccessChallenge = blockedEngines.isNotEmpty(),
                termuxAvailable = useTermux
            )
            termuxWs?.close(1000, "Filtered to zero")
            termuxWs = null
            return
        }

        if (verifiedMatches.isEmpty() && likelyMatches.isEmpty() && retainedVisualCandidates.isEmpty() && (tinEyeDisplayMatches.isNotEmpty() || adultDisplayMatches.isNotEmpty())) {
            addLog("No locally verified face match was found, but ${tinEyeDisplayMatches.size} TinEye and ${adultDisplayMatches.size} adult platform hit(s) were identified.")
            updateResultsLive(emptyList(), useTermux, tinEyeDisplayMatches, adultDisplayMatches)
            termuxWs?.close(1000, "Occurrence hits only")
            termuxWs = null
            return
        }

        if (verifiedMatches.isEmpty() && likelyMatches.isEmpty()) {
            addLog("No locally verified or possible face match was found. Showing ${unverifiedLeads.size} review lead(s), ${fallbackCandidates.size} visual candidate(s), ${tinEyeDisplayMatches.size} TinEye, and ${adultDisplayMatches.size} adult hit(s).")
            updateResultsLive(retainedVisualCandidates, useTermux, tinEyeDisplayMatches, adultDisplayMatches)
            termuxWs?.close(1000, "Leads only")
            termuxWs = null
            return
        }

        updateResultsLive(verifiedMatches + likelyMatches + retainedVisualCandidates, useTermux, tinEyeDisplayMatches, adultDisplayMatches)

        val verifiedHits = verifiedMatches
        if (verifiedHits.isNotEmpty()) {
            try {
                val cropper = NativeFaceCropper()
                val searchBitmap = cropper.prepareFaceForSearch(faceBitmap)
                val stream = ByteArrayOutputStream()
                searchBitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                val byteArray = stream.toByteArray()
                val probeHash = MessageDigest.getInstance("SHA-256")
                    .digest(byteArray)
                    .joinToString("") { "%02x".format(it) }

                SearchCacheManager.saveResults(getApplication(), probeHash, allRawResults.distinctBy { it.link })
                addLog("✓ Saved ${verifiedHits.size} verified matches to local cache.")
            } catch (e: Exception) {
                addLog("⚠ Failed to save verified results to cache: ${e.message}")
            }
        }

        // --- NEW KNOWLEDGE GRAPH PIVOTING BLOCK ---
        if (verifiedHits.isNotEmpty() && useTermux) {
            addLog("Initiating Knowledge Graph Pivot on ${verifiedHits.size} verified visual matches...")

            // If we have at least one verified hit, we know we have the right person.
            // So, let's harvest usernames from ALL visual leads, even the unverified ones!
            val allScrapedLeads = verifiedHits + likelyHits + visualLeads

            val pivotEntities = allScrapedLeads.map {
                ScanResultEntity(
                    queryTarget = targetHint.ifBlank { "unknown" },
                    platform = it.source ?: "Unknown",
                    profileUrl = it.profileUrl,
                    timestamp = System.currentTimeMillis()
                )
            }.filter { it.profileUrl.isNotBlank() }

            // Pass the titles of all scraped links so the regex can find the @handles
            val titlesMap = allScrapedLeads.associate { it.profileUrl to it.name }

            val tasks = PivotingCoordinator.extractPivotTasks(pivotEntities, titlesMap)
            if (tasks.isNotEmpty()) {
                val pivotResults = PivotingCoordinator.executePivots(
                    tasks = tasks,
                    backendUrl = faceSearchRepository.activeBackend ?: "http://127.0.0.1:3000",
                    onLog = { msg -> addLog(msg) }
                )

                if (pivotResults.isNotEmpty()) {
                    addLog("Knowledge Graph Expansion Complete: Found ${pivotResults.size} secondary accounts linked to these usernames.")

                    // Map the pivot discoveries into WebMatchDisplay objects so the UI can render them
                    val mappedPivotDisplays = pivotResults.map { entity ->
                        WebMatchDisplay(
                            name = "Discovered Account (@${entity.queryTarget})",
                            source = "Pivot Discovery (${entity.platform})",
                            profileUrl = entity.profileUrl,
                            score = 6500,
                            isFaceVerified = false,
                            isLikelyFaceMatch = true,
                            isSocial = true
                        )
                    }

                    // Update the UI live with the newly discovered pivot nodes
                    updateResultsLive(
                        mappedPivotDisplays.map {
                            SerpVisualMatch(
                                title = it.name,
                                link = it.profileUrl,
                                source = it.source,
                                thumbnail = null,
                                score = it.score
                            )
                        },
                        useTermux
                    )
                }
            } else {
                addLog("No viable usernames extracted for pivoting.")
            }
        }

        addLog("Showing ${verifiedMatches.size} verified, ${likelyMatches.size} possible face match(es), ${unverifiedLeads.size} review lead(s), ${fallbackCandidates.size} visual candidate(s), ${tinEyeDisplayMatches.size} TinEye, and ${adultDisplayMatches.size} adult hit(s).")
        termuxWs?.close(1000, "Success")
        termuxWs = null
    }

    private fun updateResultsLive(
        newResults: List<SerpVisualMatch>,
        termuxAvailable: Boolean = true,
        tinEyeMatches: List<WebMatchDisplay> = emptyList(),
        adultMatches: List<WebMatchDisplay> = emptyList()
    ) {
        Log.e("CheckIn", "CONSOLE_LOG: updateResultsLive called with ${newResults.size} new results, ${tinEyeMatches.size} TinEye, and ${adultMatches.size} Adult matches")
        viewModelScope.launch(Dispatchers.Default) {
            val currentState = uiState
            val existingMatches = if (currentState is CheckInUiState.Success) {
                currentState.matches
            } else {
                emptyList()
            }

            val mappedNewResults = newResults.map(::mapToDisplay)
            val allMatches = deduplicateDisplayMatches(
                existingMatches + mappedNewResults
            ).sortedByDescending { it.score }

            val isolatedFace = when (val s = uiState) {
                is CheckInUiState.Loading -> s.isolatedFace
                is CheckInUiState.Success -> s.isolatedFace
                else -> null
            }

            withContext(Dispatchers.Main) {
                uiState = CheckInUiState.Success(
                    matches = allMatches,
                    tinEyeMatches = (if (currentState is CheckInUiState.Success) currentState.tinEyeMatches else emptyList()) + tinEyeMatches,
                    adultMatches = (if (currentState is CheckInUiState.Success) currentState.adultMatches else emptyList()) + adultMatches,
                    logs = currentLogs.toList(),
                    termuxAvailable = termuxAvailable,
                    isolatedFace = isolatedFace
                )
                // Ensure scanning state is cleared when results are displayed
                isSearching = false
            }
        }
    }

    /**
     * Collapses duplicate leads and performs visual deduplication.
     * Near-identical photos (same person, same shot) are grouped to show
     * more variety in the results.
     */
    private fun deduplicateDisplayMatches(matches: List<WebMatchDisplay>): List<WebMatchDisplay> {
        // 1. Primary deduplication by URL/Thumbnail
        val groupedByUrl = matches.groupBy(::displayDeduplicationKey).values.map { duplicates ->
            val best = duplicates.maxWithOrNull(
                compareBy<WebMatchDisplay> {
                    when {
                        it.isFaceVerified -> 2
                        it.isLikelyFaceMatch -> 1
                        else -> 0
                    }
                }.thenBy { it.score }
            ) ?: return@map duplicates.first()

            val alternateImages = duplicates
                .mapNotNull { it.imageUrl as? String }
                .filter { it != best.imageUrl }
                .distinct()

            best.copy(
                extraImages = (best.extraImages + alternateImages).distinct(),
                duplicateCount = duplicates.sumOf { it.duplicateCount }
            )
        }

        // 2. Secondary visual deduplication by face embedding
        val finalMatches = mutableListOf<WebMatchDisplay>()
        val processed = mutableSetOf<Int>()

        for (i in groupedByUrl.indices) {
            if (i in processed) continue
            val current = groupedByUrl[i]
            val currentEmb = current.faceEmbedding
            
            val duplicates = mutableListOf(current)
            processed.add(i)

            // Cosine similarity > 0.99 indicates identical visual content.
            // Relaxed from 0.96 to allow more "same person, different shot" variety.
            if (currentEmb != null) {
                for (j in i + 1 until groupedByUrl.size) {
                    if (j in processed) continue
                    val other = groupedByUrl[j]
                    val otherEmb = other.faceEmbedding ?: continue
                    
                    // Relaxed from 0.99f to 0.999f to only merge near-exact byte-level duplicates.
                    // This ensures we show "all of them" as requested.
                    if (com.yourcompany.facesearch.vision.FaceMatcherExt.cosineSimilarity(currentEmb, otherEmb) > 0.999f) {
                        duplicates.add(other)
                        processed.add(j)
                    }
                }
            }

            if (duplicates.size > 1) {
                val best = duplicates.maxBy { it.score }
                val allExtraImages = duplicates.flatMap { it.extraImages + listOfNotNull(it.imageUrl as? String) }
                    .filter { it != best.imageUrl }
                    .distinct()
                
                finalMatches.add(best.copy(
                    extraImages = allExtraImages,
                    duplicateCount = duplicates.sumOf { it.duplicateCount }
                ))
            } else {
                finalMatches.add(current)
            }
        }
        return finalMatches
    }

    private fun displayDeduplicationKey(match: WebMatchDisplay): String {
        val normalizedProfileUrl = try {
            val uri = Uri.parse(match.profileUrl)
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path.orEmpty().trimEnd('/').lowercase()
            if (host.isNotBlank()) "$host$path" else match.profileUrl.substringBefore('?').trimEnd('/').lowercase()
        } catch (_: Exception) {
            match.profileUrl.substringBefore('?').trimEnd('/').lowercase()
        }
        if (normalizedProfileUrl.isNotBlank()) return "profile:$normalizedProfileUrl"

        val thumbnailKey = ThumbnailUtils.canonicalKey(match.imageUrl as? String)
        if (thumbnailKey != null) return "image:$thumbnailKey"

        // We no longer merge by name only; this prevents losing distinct visual leads
        // from different sources that share generic titles like "Visual Match".
        return "fallback:${match.source.lowercase(Locale.US)}:${match.score}:${java.util.UUID.randomUUID()}"
    }

    private fun prioritizeCandidates(results: List<SerpVisualMatch>): List<SerpVisualMatch> {
        val socialHosts = listOf(
            "instagram.com", "facebook.com", "linkedin.com", "x.com", "twitter.com",
            "tiktok.com", "youtube.com", "reddit.com", "onlyfans.com", "fansly.com",
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
            "threads.net", "bsky.app", "mastodon.social", "snapchat.com",
            "quora.com", "behance.net", "dribbble.com", "soundcloud.com",
            "spotify.com", "keybase.io", "patreon.com", "substack.com",
            "medium.com", "dev.to", "twitch.tv", "tumblr.com", "flickr.com",
            "vsco.co", "vk.com", "linktr.ee", "gitlab.com",
            "stackoverflow.com", "producthunt.com"
        )
        return results.asSequence()
            .filter { !it.link.isNullOrBlank() }
            .filterNot(::isIrrelevantVisualResult)
            .filterNot(::isLikelyProductResult)
            .filterNot { match -> 
                val metadata = listOfNotNull(match.title, match.link, match.source).joinToString(" ").lowercase()
                metadata.contains("favicon") || metadata.contains("spacer.gif")
            }
            .distinctBy { match -> match.link } // Deduplicate by URL only, not thumbnail
            .sortedByDescending { match ->
                val thumbnail = ThumbnailUtils.normalize(match.thumbnail, match.link)
                val hasUsableThumbnail = thumbnail != null &&
                    !thumbnail.contains("placeholder", ignoreCase = true) &&
                    !thumbnail.contains("default", ignoreCase = true)
                val isSocialProfile = socialHosts.any { host -> match.link?.contains(host, ignoreCase = true) == true }
                (if (hasUsableThumbnail) 1_000 else 0) +
                    (if (isSocialProfile) 300 else 0) +
                    match.score.coerceAtMost(999)
            }
            .take(MAX_CANDIDATES_TO_VERIFY)
            .toList()
    }

    private fun isLikelyProductResult(match: SerpVisualMatch): Boolean {
        val title = (match.title ?: "").lowercase(Locale.US)
        val link = (match.link ?: "").lowercase(Locale.US)
        val source = (match.source ?: "").lowercase(Locale.US)
        val metadata = "$title $link $source"

        // 1. YouTube Shorts Junk
        val isYoutubeJunk = link.contains("youtube.com") && 
                (link.contains("watch?v=") || link.contains("shorts/")) &&
                title.contains("shorts")
        if (isYoutubeJunk) return true

        // 2. Known E-Commerce / Shopping Domains
        val shoppingDomains = listOf(
            "amazon.", "ebay.", "faire.com", "iqueens.com", "aliexpress.", "etsy.com",
            "walmart.com", "target.com", "poshmark.com", "depop.com", "mercari.com",
            "taobao.com", "tmall.com", "shein.com", "asos.com", "nordstrom.com",
            "macys.com", "zara.com", "hm.com", "urbanoutfitters.com", "ralphlauren.com",
            "nike.com", "adidas.com", "puma.com", "underarmour.com", "shop.", "store."
        )
        if (shoppingDomains.any { metadata.contains(it) }) return true

        // 3. E-Commerce & Apparel Keywords in Title / Metadata
        val productKeywords = listOf(
            "buy ", "sale", "price", "shipping", "in stock", "cart", "dollar", "usd", "inr", "eur",
            "t-shirt", "t shirt", "tee", "shirt", "oversized", "unisex", "men's", "women's",
            "hoodie", "sweater", "jacket", "coat", "pants", "jeans", "dress", "apparel",
            "clothing", "wear", "outfit", "nwt", "wholesale", "fabric", "harlequin",
            "shop", "boutique", "generation women", "harlequin tee"
        )
        if (productKeywords.any { title.contains(it) }) return true

        return false
    }

    /** Excludes sources and metadata that are unsuitable as identity leads. */
    private fun isIrrelevantVisualResult(match: SerpVisualMatch): Boolean {
        val metadata = listOfNotNull(match.title, match.link, match.thumbnail, match.source)
            .joinToString(" ")
            .lowercase(Locale.US)
        val stockDomains = listOf(
            "shutterstock.", "istockphoto.", "gettyimages.", "adobestock."
        )
        val nonPhotographicTerms = listOf(
            "illustration", "vector", "cartoon", "clipart", "line art",
            "drawing", "anime", "avatar", "emoji"
        )
        return stockDomains.any { metadata.contains(it) } ||
            nonPhotographicTerms.any { term ->
                Regex("(?<![a-z0-9])${Regex.escape(term)}(?![a-z0-9])")
                    .containsMatchIn(metadata)
            }
    }

    private fun mapToDisplay(match: SerpVisualMatch): WebMatchDisplay {
        val platform = SocialMediaDetector.detectPlatform(match.link)
        val isSocial = platform.isProfileBased || SocialMediaDetector.isProfileUrl(match.link)

        val urlUsername = match.link?.let { WebMatchDisplay.extractUsernameFromUrl(it) }
        
        var cleanTitle = match.title ?: "Visual Match"
        
        // Only trust URL username if the link actually points to a profile page
        val isLikelyProfileUrl = isSocial || match.link?.let { link ->
            val lower = link.lowercase()
            lower.contains("/user/") || lower.contains("/in/") || lower.contains("/@")
        } ?: false

        if (urlUsername != null && isLikelyProfileUrl && (
                cleanTitle.contains("match", ignoreCase = true) 
                || cleanTitle.length < 4 
                || cleanTitle.contains("facebook", ignoreCase = true)
                || cleanTitle.contains("instagram", ignoreCase = true)
                || cleanTitle.contains("reddit", ignoreCase = true)
            )) {
            cleanTitle = urlUsername.replaceFirstChar { it.titlecase(Locale.US) }
        }
        
        cleanTitle = cleanTitle.replace(Regex("#\\w+"), "").trim()

        // NEW: Append the social media platform name to the title
        if (isSocial && platform.name != "Web" && !cleanTitle.contains(platform.name, ignoreCase = true)) {
            cleanTitle = "$cleanTitle (${platform.name})"
        }

        // Detect suspicious thumbnails (very small URLs often indicate bad crops)
        val thumb = ThumbnailUtils.normalize(match.thumbnail, match.link)
        val hasGoodThumbnail = thumb != null && 
            !thumb.contains("thumbnail") && 
            !thumb.contains("preview") && 
            thumb.length > 20

        // Use SocialMediaDetector to boost and identify platform
        val finalSource = if (platform.name != "Web") platform.name else (match.source ?: "Free Engine")
        val finalScore = match.score + platform.baseScore

        val isVerified = match.faceSimilarity?.let { it >= FaceVerifier.VERIFICATION_THRESHOLD }
            ?: (finalScore >= VERIFIED_MATCH_BASE_SCORE)
        val isLikely = !isVerified && (match.faceSimilarity?.let { it >= LIKELY_MATCH_THRESHOLD }
            ?: (finalScore >= LIKELY_MATCH_BASE_SCORE))
        val isReviewLead = !isVerified && !isLikely && (match.faceSimilarity?.let {
            it >= REVIEW_LEAD_SIMILARITY_THRESHOLD
        } ?: (finalScore >= REVIEW_LEAD_BASE_SCORE))
        val isFallbackCandidate = !isVerified && !isLikely && !isReviewLead &&
            (match.faceSimilarity?.let { it >= FALLBACK_CANDIDATE_SIMILARITY_THRESHOLD }
                ?: (finalScore >= FALLBACK_CANDIDATE_BASE_SCORE))
        val sourceLabel = when {
            isVerified -> "✓ $finalSource"
            isLikely -> "≈ $finalSource"
            else -> finalSource
        }

        return WebMatchDisplay(
            name = cleanTitle,
            source = sourceLabel,
            profileUrl = match.link ?: "",
            score = finalScore,
            imageUrl = match.faceCrop ?: thumb,
            isSocial = isSocial,
            confidence = match.faceSimilarity?.let { similarity ->
                when {
                    similarity >= FaceVerifier.VERIFICATION_THRESHOLD ->
                        (0.90f + ((similarity - FaceVerifier.VERIFICATION_THRESHOLD) /
                            (1f - FaceVerifier.VERIFICATION_THRESHOLD)) * 0.10f).coerceIn(0.90f, 1f)
                    similarity >= LIKELY_MATCH_THRESHOLD ->
                        (0.70f + ((similarity - LIKELY_MATCH_THRESHOLD) /
                            (FaceVerifier.VERIFICATION_THRESHOLD - LIKELY_MATCH_THRESHOLD)) * 0.19f)
                            .coerceIn(0.70f, 0.89f)
                    similarity >= REVIEW_LEAD_SIMILARITY_THRESHOLD ->
                        (0.50f + ((similarity - REVIEW_LEAD_SIMILARITY_THRESHOLD) /
                            (LIKELY_MATCH_THRESHOLD - REVIEW_LEAD_SIMILARITY_THRESHOLD)) * 0.19f)
                            .coerceIn(0.50f, 0.69f)
                    else -> 0f
                }
            } ?: when {
                isVerified -> calculateConfidence(finalScore)
                isLikely -> calculatePossibleConfidence(finalScore)
                isReviewLead -> calculateReviewLeadConfidence(finalScore)
                isFallbackCandidate -> calculateFallbackCandidateConfidence(finalScore)
                else -> 0f
            },
            isFaceVerified = isVerified,
            isLikelyFaceMatch = isLikely,
            isHighResLoading = (isVerified || isLikely) && !hasGoodThumbnail && isSocial,
            faceEmbedding = match.embedding
        )
    }

    private fun calculatePossibleConfidence(score: Int): Float =
        (0.70f + ((score - LIKELY_MATCH_BASE_SCORE) / LIKELY_MATCH_SIMILARITY_WEIGHT.toFloat()) * 0.19f)
            .coerceIn(0.70f, 0.89f)

    private fun calculateReviewLeadConfidence(score: Int): Float =
        (0.50f + ((score - REVIEW_LEAD_BASE_SCORE) / REVIEW_LEAD_SIMILARITY_WEIGHT.toFloat()) * 0.19f)
            .coerceIn(0.50f, 0.69f)

    private fun calculateFallbackCandidateConfidence(score: Int): Float =
        ((score - FALLBACK_CANDIDATE_BASE_SCORE) / FALLBACK_CANDIDATE_SIMILARITY_WEIGHT.toFloat())
            .coerceIn(0.01f, 0.49f)

    private fun calculateConfidence(score: Int): Float {
        return when {
            score >= 9000 -> (0.90f + ((score - 9000) / 1000f) * 0.10f).coerceIn(0.90f, 1.0f)
            score >= 7000 -> calculatePossibleConfidence(score)
            score >= 5000 -> calculateReviewLeadConfidence(score)
            else -> calculateFallbackCandidateConfidence(score)
        }
    }

    private data class CandidateReview(
        val verifiedMatches: List<SerpVisualMatch>,
        val likelyMatches: List<SerpVisualMatch>,
        val faceBearingLeads: List<SerpVisualMatch>,
        val fallbackCandidates: List<SerpVisualMatch>,
        val excludedNoFace: Int,
        val excludedLowRelevance: Int
    )

    /**
     * Reviews each visual candidate once. A candidate can only reach the lead
     * screen when ML Kit detects exactly one sufficiently visible face. Product
     * shots, torso images, groups, icons, and thumbnails without a face are
     * discarded before display. A face-bearing lead is upgraded only when the
     * embedding comparison also passes the local verification threshold.
     *
     * Uses FaceMatchRanker for final evidence-based scoring and ranking.
     */
    private suspend fun reviewCandidates(
        results: List<SerpVisualMatch>,
        sourceFaceBitmap: Bitmap,
        isTermuxAvailable: Boolean
    ): CandidateReview = coroutineScope {
        val sourceEmbedding = faceEmbedder.getEmbedding(sourceFaceBitmap)
            ?: return@coroutineScope CandidateReview(
                emptyList(), emptyList(), emptyList(), emptyList(),
                excludedNoFace = results.size,
                excludedLowRelevance = 0
            )
        latestSourceEmbedding = sourceEmbedding
        
        // Use the sensitivity slider as the absolute minimum threshold to suppress weak matches
        val minimumThreshold = sensitivity

        val verified = java.util.Collections.synchronizedList(mutableListOf<SerpVisualMatch>())
        val likely = java.util.Collections.synchronizedList(mutableListOf<SerpVisualMatch>())
        val faceBearingLeads = java.util.Collections.synchronizedList(mutableListOf<SerpVisualMatch>())
        val fallbackCandidates = java.util.Collections.synchronizedList(mutableListOf<SerpVisualMatch>())
        val excludedCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val lowRelevanceCounter = java.util.concurrent.atomic.AtomicInteger(0)

        // Dynamic thresholds scaled by the sensitivity floor
        val verifiedThreshold = max(minimumThreshold, if (isTermuxAvailable) FaceVerifier.VERIFICATION_THRESHOLD else 0.60f)
        val likelyThreshold = max(minimumThreshold, if (isTermuxAvailable) FaceVerifier.REVIEW_THRESHOLD else 0.50f)
        val reviewLeadThreshold = if (isTermuxAvailable) FaceVerifier.MINIMUM_SIMILARITY else 0.35f

        // Limit parallelism to avoid overwhelming the device (ML Kit/Embedder)
        val semaphore = kotlinx.coroutines.sync.Semaphore(8)

        results.map { match ->
            async {
                semaphore.withPermit {
                    try {
                        // Immediately drop e-commerce/apparel product candidates
                        if (isLikelyProductResult(match) || isIrrelevantVisualResult(match)) {
                            excludedCounter.incrementAndGet()
                            return@withPermit
                        }

                        val thumbnailUrl = match.thumbnail
                            ?: if (match.link != null) faceSearchRepository.extractMetadataThumbnail(match.link) else null
                        
                        if (thumbnailUrl == null) {
                            if (!isLikelyProductResult(match)) {
                                val fallbackMatch = match.copy(faceSimilarity = 0.05f)
                                fallbackCandidates += fallbackMatch
                            } else {
                                excludedCounter.incrementAndGet()
                            }
                        } else {
                            val thumbnail = loadThumbnailBitmap(thumbnailUrl)
                            if (thumbnail == null) {
                                if (!isLikelyProductResult(match)) {
                                    val fallbackMatch = match.copy(faceSimilarity = 0.05f)
                                    fallbackCandidates += fallbackMatch
                                } else {
                                    excludedCounter.incrementAndGet()
                                }
                            } else {
                                val faceCrop = try { nativeFaceCropper.cropAndAlignFace(thumbnail, fullJawline = false) } catch (_: Exception) { null }
                                val result = try { faceVerifier.calculateSimilarityAndEmbedding(thumbnail, sourceEmbedding, thumbnailUrl) } catch (_: Exception) { null }

                                if (result != null && result.first.isFinite()) {
                                    val (similarity, embedding) = result
                                    val faceBearingMatch = match.copy(
                                        thumbnail = thumbnailUrl,
                                        embedding = embedding,
                                        faceSimilarity = similarity,
                                        faceCrop = faceCrop ?: thumbnail
                                    )

                                    when {
                                        similarity >= verifiedThreshold -> {
                                            verified += faceBearingMatch
                                            Log.d("CheckIn", "✓ FACE VERIFIED: ${"%.1f".format(similarity * 100f)}% - ${match.title ?: match.link}")
                                        }
                                        similarity >= likelyThreshold -> {
                                            likely += faceBearingMatch
                                            Log.d("CheckIn", "△ FACE REVIEW: ${"%.1f".format(similarity * 100f)}% - ${match.title ?: match.link}")
                                        }
                                        similarity >= reviewLeadThreshold -> {
                                            faceBearingLeads += faceBearingMatch
                                            Log.d("CheckIn", "• FACE LEAD: ${"%.1f".format(similarity * 100f)}% - ${match.title ?: match.link}")
                                        }
                                        else -> {
                                            if (!isLikelyProductResult(match)) {
                                                fallbackCandidates += faceBearingMatch
                                                Log.d("CheckIn", "• FALLBACK CANDIDATE: ${"%.1f".format(similarity * 100f)}% - ${match.title ?: match.link}")
                                            } else {
                                                excludedCounter.incrementAndGet()
                                            }
                                        }
                                    }
                                } else {
                                    // Thumbnail does not contain a face or face verification failed
                                    if (!isLikelyProductResult(match)) {
                                        val fallbackMatch = match.copy(
                                            thumbnail = thumbnailUrl,
                                            faceSimilarity = 0.05f,
                                            faceCrop = faceCrop ?: thumbnail
                                        )
                                        fallbackCandidates += fallbackMatch
                                        Log.d("CheckIn", "• PRESERVED VISUAL LEAD: ${match.title ?: match.link}")
                                    } else {
                                        excludedCounter.incrementAndGet()
                                    }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        if (!isLikelyProductResult(match)) {
                            val fallbackMatch = match.copy(faceSimilarity = 0.05f)
                            fallbackCandidates += fallbackMatch
                        } else {
                            excludedCounter.incrementAndGet()
                        }
                        Log.w("CheckIn", "Candidate review error for ${match.link}, preserved as fallback lead", error)
                    }
                }
            }
        }.awaitAll()

        fun rankAndDeduplicate(matches: List<SerpVisualMatch>, baseScore: Int): List<SerpVisualMatch> {
            if (matches.isEmpty()) return emptyList()

            val grouped = matches.groupBy { normaliseCandidateUrl(it.link) }

            return grouped.map { (_, duplicates) ->
                val strongest = duplicates.maxByOrNull { it.faceSimilarity ?: 0f } ?: duplicates.first()
                val providerCount = duplicates.mapNotNull { it.source }.filter { it.isNotBlank() }.distinct().size.coerceAtLeast(1)
                val duplicateCount = duplicates.size

                val evidence = FaceMatchRanker.CandidateEvidence(
                    faceSimilarity = strongest.faceSimilarity,
                    providerCount = providerCount,
                    duplicateCount = duplicateCount,
                    exactImageMatch = isTinEyeResult(strongest)
                )

                val ranking = FaceMatchRanker.rank(evidence)

                strongest.copy(
                    score = baseScore + (ranking.score * 1000).toInt()
                )
            }.sortedWith(
                compareByDescending<SerpVisualMatch> { it.score }
                    .thenByDescending { it.faceSimilarity ?: 0f }
            )
        }

        CandidateReview(
            verifiedMatches = rankAndDeduplicate(verified, VERIFIED_MATCH_BASE_SCORE),
            likelyMatches = rankAndDeduplicate(likely, LIKELY_MATCH_BASE_SCORE),
            faceBearingLeads = rankAndDeduplicate(faceBearingLeads, REVIEW_LEAD_BASE_SCORE),
            fallbackCandidates = rankAndDeduplicate(fallbackCandidates, FALLBACK_CANDIDATE_BASE_SCORE)
                .take(MAX_FALLBACK_CANDIDATES),
            excludedNoFace = excludedCounter.get(),
            excludedLowRelevance = lowRelevanceCounter.get()
        )
    }

    private fun normaliseCandidateUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            Uri.parse(url)
                .buildUpon()
                .clearQuery()
                .fragment(null)
                .build()
                .toString()
                .trimEnd('/')
                .lowercase()
        } catch (_: Exception) {
            url.trim()
                .trimEnd('/')
                .lowercase()
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
            val trimmed = url.trim()
            if (trimmed.startsWith("data:image", ignoreCase = true)) {
                val base64Data = trimmed.substringAfter("base64,")
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            val request = ImageRequest.Builder(getApplication())
                .data(trimmed)
                .allowHardware(false) 
                .build()
            val result = getApplication<Application>().imageLoader.execute(request)
            val coilBitmap = result.image?.toBitmap()
            if (coilBitmap != null) return@withContext coilBitmap

            // Fallback: download via OkHttp with browser headers
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val httpReq = Request.Builder()
                .url(trimmed)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36")
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build()
            client.newCall(httpReq).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            }
            null
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
