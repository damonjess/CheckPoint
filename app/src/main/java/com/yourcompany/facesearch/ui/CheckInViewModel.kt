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
            uiState = CheckInUiState.Loading(0f, listOf("Initializing local optics..."))
            
            // Step 1: Local face detection
            when (val detectionResult = faceDetectorHelper.detectAndCropFace(bitmap)) {
                is FaceDetectionResult.Success -> {
                    uiState = CheckInUiState.Loading(0.1f, listOf("Hosting probe image safely..."))
                    val publicUrl = imageUploadRepository.uploadImage(detectionResult.croppedFace)
                    
                    if (publicUrl != null) {
                        performWebSearch(publicUrl)
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

    private suspend fun performWebSearch(imageUrl: String) {
        val logs = mutableListOf("Connecting to Apify biometric crawler...")
        uiState = CheckInUiState.Loading(0.4f, logs.toList())

        try {
            val apifyInput = ApifyFaceInput(imageUrl = imageUrl)
            
            // Make sure to clean any potential accidental quotes and format as a Bearer string
            val formattedToken = "Bearer ${Secrets.APIFY_API_TOKEN.trim().replace("\"", "")}"
            
            val searchResults = ApiClient.apifyApi.searchFace(
                bearerToken = formattedToken,
                input = apifyInput
            )

            if (searchResults.isNotEmpty()) {
                uiState = CheckInUiState.Success(
                    matches = searchResults.map { result ->
                        val sourceDomain = try {
                            val uri = android.net.Uri.parse(result.url)
                            uri.host?.replace("www.", "") ?: "Social Profile"
                        } catch (e: Exception) {
                            "Social Profile"
                        }

                        WebMatchDisplay(
                            name = "Matched Profile",
                            source = sourceDomain,
                            profileUrl = result.url,
                            score = result.score,
                            imageUrl = result.image
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
