package com.yourcompany.facesearch.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.facesearch.network.ApiClient
import com.yourcompany.facesearch.network.ApifyFaceInput
import com.yourcompany.facesearch.network.FaceSearchOutcome
import com.yourcompany.facesearch.network.FaceSearchRepository
import com.yourcompany.facesearch.network.ImageUploadRepository
import com.yourcompany.facesearch.network.Secrets
import com.yourcompany.facesearch.vision.FaceDetectionResult
import com.yourcompany.facesearch.vision.FaceDetectorHelper
import kotlinx.coroutines.launch

class CheckInViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val faceDetectorHelper = FaceDetectorHelper(application)
    private val faceSearchRepository = FaceSearchRepository()
    private val imageUploadRepository = ImageUploadRepository()

    var uiState by mutableStateOf<CheckInUiState>(CheckInUiState.Idle)
        private set

    var capturedBitmap by mutableStateOf<Bitmap?>(null)
        private set

    fun onPhotoCaptured(bitmap: Bitmap) {
        capturedBitmap = bitmap
        
        viewModelScope.launch {
            val logs = mutableListOf("Initializing local optics...")
            uiState = CheckInUiState.Loading(0.1f, logs.toList())
            
            // Step 1: Local face detection
            when (val detectionResult = faceDetectorHelper.detectAndCropFace(bitmap)) {
                is FaceDetectionResult.Success -> {
                    logs.add("Face detected. Optimizing probe...")
                    uiState = CheckInUiState.Loading(0.25f, logs.toList())
                    
                    logs.add("Hosting probe image safely (ImgBB)...")
                    uiState = CheckInUiState.Loading(0.4f, logs.toList())
                    
                    val publicUrl = imageUploadRepository.uploadImage(detectionResult.croppedFace)
                    
                    if (publicUrl != null) {
                        logs.add("Cloud hosting successful.")
                        performWebSearch(publicUrl, logs)
                    } else {
                        uiState = CheckInUiState.Error("Image hosting failed. Please try again.")
                    }
                }
                is FaceDetectionResult.NoFaceFound -> {
                    uiState = CheckInUiState.NoFaceDetected
                }
                is FaceDetectionResult.Error -> {
                    uiState = CheckInUiState.Error("Face detection failed. Try again.")
                }
            }
        }
    }

    private suspend fun performWebSearch(imageUrl: String, logs: MutableList<String>) {
        logs.add("Connecting to Apify biometric crawler...")
        uiState = CheckInUiState.Loading(0.6f, logs.toList())

        try {
            logs.add("Awaiting biometric analysis (this may take 30-60s)...")
            uiState = CheckInUiState.Loading(0.85f, logs.toList())

            val apifyInput = ApifyFaceInput(imageUrl = imageUrl)
            
            // Make sure to clean any potential accidental quotes and format as a Bearer string
            val formattedToken = "Bearer ${Secrets.APIFY_API_TOKEN.trim().replace("\"", "")}"
            
            val searchResults = ApiClient.apifyApi.searchFace(
                bearerToken = formattedToken,
                input = apifyInput
            )

            if (searchResults.isNotEmpty()) {
                logs.add("Search complete. Found ${searchResults.size} matches.")
                uiState = CheckInUiState.Loading(1.0f, logs.toList())
                kotlinx.coroutines.delay(500)

                uiState = CheckInUiState.Success(
                    matches = searchResults.map { result ->
                        val sourceDomain = try {
                            val uri = android.net.Uri.parse(result.url)
                            uri.host?.replace("www.", "") ?: "Social Profile"
                        } catch (e: Exception) {
                            "Social Profile"
                        }

                        // Extract string if it's a primitive, otherwise pass the raw JsonElement
                        val imageSource = if (result.image?.isJsonPrimitive == true) {
                            result.image.asString
                        } else {
                            result.image
                        }

                        WebMatchDisplay(
                            name = "Matched Profile",
                            source = sourceDomain,
                            profileUrl = result.url,
                            score = result.score,
                            imageUrl = imageSource
                        )
                    }
                )
            } else {
                uiState = CheckInUiState.NoMatch
            }
        } catch (e: Exception) {
            uiState = CheckInUiState.Error(e.message ?: "Apify search failed")
        }
    }

    fun onRetry() {
        uiState = CheckInUiState.Idle
        capturedBitmap = null
    }

    override fun onCleared() {
        super.onCleared()
        faceDetectorHelper.release()
    }
}
