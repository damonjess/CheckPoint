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
            uiState = CheckInUiState.Loading(0f)
            
            // Step 1: Local face detection
            when (val detectionResult = faceDetectorHelper.detectAndCropFace(bitmap)) {
                is FaceDetectionResult.Success -> {
                    performWebSearch(bitmap)
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

    private suspend fun performWebSearch(faceBitmap: Bitmap) {
        val outcome = faceSearchRepository.searchByFace(faceBitmap) { progress ->
            uiState = CheckInUiState.Loading(progress)
        }

        when (outcome) {
            is FaceSearchOutcome.Success -> {
                uiState = CheckInUiState.Success(
                    matches = outcome.matches.map { match ->
                        WebMatchDisplay(
                            name = match.name ?: "Unknown Person",
                            source = match.source ?: "Web",
                            profileUrl = match.profileUrl ?: "",
                            confidence = match.confidence ?: 0.75,
                            imageUrl = match.imageUrl
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
                uiState = CheckInUiState.Error("Something went wrong. Please try again.")
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
