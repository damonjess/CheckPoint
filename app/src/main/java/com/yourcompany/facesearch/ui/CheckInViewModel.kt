package com.yourcompany.facesearch.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.facesearch.network.FaceSearchOutcome
import com.yourcompany.facesearch.network.FaceSearchRepository
import com.yourcompany.facesearch.vision.FaceDetectionResult
import com.yourcompany.facesearch.vision.FaceDetectorHelper
import kotlinx.coroutines.launch

class CheckInViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val faceDetectorHelper = FaceDetectorHelper(application)
    private val faceSearchRepository = FaceSearchRepository()

    var uiState by mutableStateOf<CheckInUiState>(CheckInUiState.Idle)
        private set

    var capturedBitmap by mutableStateOf<Bitmap?>(null)
        private set

    fun onPhotoCaptured(bitmap: Bitmap) {
        capturedBitmap = bitmap
        
        viewModelScope.launch {
            uiState = CheckInUiState.Loading(0f, listOf("Initializing local optics..."))
            
            // Step 1: Local face detection
            when (faceDetectorHelper.detectAndCropFace(bitmap)) {
                is FaceDetectionResult.Success -> {
                    // For now, using a placeholder URL. 
                    // In a real app, you'd upload the bitmap to a service like Imgur/ImgBB first.
                    performWebSearch("https://upload.wikimedia.org/wikipedia/commons/8/89/Portrait_Placeholder.png")
                }
                is FaceDetectionResult.NoFaceFound -> {
                    uiState = CheckInUiState.NoMatch
                }
                is FaceDetectionResult.Error -> {
                    uiState = CheckInUiState.Error("Face detection failed. Try again.")
                }
            }
        }
    }

    private suspend fun performWebSearch(imageUrl: String) {
        val logs = mutableListOf<String>()
        
        val outcome = faceSearchRepository.searchTheWebForFree(imageUrl) { progress, newLog ->
            if (newLog.isNotBlank() && (logs.isEmpty() || logs.last() != newLog)) {
                logs.add(newLog)
                if (logs.size > 8) logs.removeAt(0) // Keep last 8 logs
            }
            uiState = CheckInUiState.Loading(progress, logs.toList())
        }

        when (outcome) {
            is FaceSearchOutcome.Success -> {
                uiState = CheckInUiState.Success(
                    matches = outcome.matches.map { match ->
                        val sourceDomain = try {
                            val url = match.webLink ?: ""
                            if (url.startsWith("http")) {
                                url.split("/")[2].replace("www.", "")
                            } else {
                                "Visual Match"
                            }
                        } catch (e: Exception) {
                            "Visual Match"
                        }

                        WebMatchDisplay(
                            name = match.title ?: "Search Result",
                            source = sourceDomain,
                            profileUrl = match.webLink ?: "",
                            confidence = 0.95,
                            imageUrl = match.displayImageUrl
                        )
                    }
                )
            }
            is FaceSearchOutcome.NoMatches -> {
                uiState = CheckInUiState.NoMatch
            }
            is FaceSearchOutcome.Error -> {
                uiState = CheckInUiState.Error(outcome.message)
            }
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
