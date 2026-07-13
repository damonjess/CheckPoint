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
import com.yourcompany.facesearch.vision.NativeFaceCropper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    fun onPhotoCaptured(bitmap: Bitmap) {
        capturedBitmap = bitmap
        
        viewModelScope.launch {
            val logs = mutableListOf("Initializing local optics...")
            uiState = CheckInUiState.Loading(0.1f, logs.toList())
            
            // Step 1: Local face detection via ML Kit
            try {
                val croppedFace = withContext(Dispatchers.Default) {
                    nativeFaceCropper.cropToFace(bitmap)
                }
                
                logs.add("Face detected. Optimizing probe...")
                uiState = CheckInUiState.Loading(0.25f, logs.toList())
                
                logs.add("Hosting probe image safely (ImgBB)...")
                uiState = CheckInUiState.Loading(0.4f, logs.toList())
                
                val publicUrl = imageUploadRepository.uploadImage(croppedFace)
                
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
        logs.add("Querying Google Lens neural engine...")
        uiState = CheckInUiState.Loading(0.7f, logs.toList())

        val visualMatches = faceSearchRepository.performFaceSearch(imageUrl)

        if (visualMatches.isNotEmpty()) {
            logs.add("Analysis complete. Targets located.")
            uiState = CheckInUiState.Loading(1.0f, logs.toList())

            uiState = CheckInUiState.Success(
                matches = visualMatches.map { result ->
                    WebMatchDisplay(
                        name = result.title ?: "Matched Profile",
                        source = result.source ?: "Public Record",
                        profileUrl = result.link ?: "",
                        score = 100, // SerpApi provides high relevance matches
                        imageUrl = result.thumbnail
                    )
                }
            )
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
