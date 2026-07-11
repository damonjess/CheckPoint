package com.yourcompany.facesearch.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.facesearch.network.FaceSearchOutcome
import com.yourcompany.facesearch.network.FaceSearchRepository
import com.yourcompany.facesearch.vision.FaceDetectionResult
import com.yourcompany.facesearch.vision.FaceDetectorHelper
import kotlinx.coroutines.launch

class CheckInViewModel(
    private val faceDetectorHelper: FaceDetectorHelper = FaceDetectorHelper(),
    private val faceSearchRepository: FaceSearchRepository = FaceSearchRepository()
) : ViewModel() {

    var uiState by mutableStateOf<CheckInUiState>(CheckInUiState.Idle)
        private set

    var capturedBitmap by mutableStateOf<Bitmap?>(null)
        private set

    /**
     * Entry point for a new capture.
     * First runs local face detection to ensure quality, then hits the backend.
     */
    fun onPhotoCaptured(bitmap: Bitmap) {
        capturedBitmap = bitmap
        
        viewModelScope.launch {
            uiState = CheckInUiState.Loading
            
            // 1. Local ML Kit Detection & Crop
            when (val detectionResult = faceDetectorHelper.detectAndCropFace(bitmap)) {
                is FaceDetectionResult.Success -> {
                    // Use the cropped face for the search
                    performCheckIn(detectionResult.croppedFace)
                }
                is FaceDetectionResult.NoFaceFound -> {
                    uiState = CheckInUiState.NoMatch
                }
                is FaceDetectionResult.Error -> {
                    uiState = CheckInUiState.Error("Face detection failed. Please try again.")
                }
            }
        }
    }

    private suspend fun performCheckIn(faceBitmap: Bitmap) {
        when (val outcome = faceSearchRepository.searchByFace(faceBitmap)) {
            is FaceSearchOutcome.Success -> {
                uiState = CheckInUiState.Success(
                    matches = outcome.matches.map { webMatch ->
                        WebMatchDisplay(
                            name = webMatch.name ?: "Unknown Person",
                            source = webMatch.source ?: "Web",
                            profileUrl = webMatch.profileUrl ?: "",
                            confidence = webMatch.confidence ?: 0.0,
                            imageUrl = webMatch.imageUrl
                        )
                    }
                )
            }
            is FaceSearchOutcome.NoMatches -> {
                uiState = CheckInUiState.NoMatch
            }
            is FaceSearchOutcome.ApiError -> {
                uiState = CheckInUiState.Error(outcome.message)
            }
            is FaceSearchOutcome.NetworkError -> {
                uiState = CheckInUiState.Error("Connection error. Check your network.")
            }
            is FaceSearchOutcome.UnknownError -> {
                uiState = CheckInUiState.Error("Something went wrong.")
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
