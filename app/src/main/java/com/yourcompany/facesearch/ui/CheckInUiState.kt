package com.yourcompany.facesearch.ui

import android.graphics.Bitmap
import com.yourcompany.facesearch.ui.models.WebMatchDisplay

enum class ScanStage(val title: String) {
    ISOLATING("Isolating Facial ROI"),
    UPLOADING("Uploading Probe & Hashing"),
    DORKING("Executing Batched Dorks & Search4faces"),
    VERIFYING("Running Biometric Verification")
}

sealed class CheckInUiState {
    object Idle : CheckInUiState()
    data class Loading(
        val progress: Float = 0f,
        val logs: List<String> = emptyList(),
        val isolatedFace: Bitmap? = null,
        val currentStage: ScanStage = ScanStage.ISOLATING
    ) : CheckInUiState()
    data class Success(
        val matches: List<WebMatchDisplay>,
        val tinEyeMatches: List<WebMatchDisplay> = emptyList(),
        val adultMatches: List<WebMatchDisplay> = emptyList(),
        val logs: List<String> = emptyList(),
        val termuxAvailable: Boolean = true,
        val isolatedFace: Bitmap? = null
    ) : CheckInUiState()
    data class Confirming(val faceBitmap: Bitmap, val sceneBitmap: Bitmap) : CheckInUiState()
    data class NoFaceDetected(
        val reasons: List<String> = emptyList(),
        val logs: List<String> = emptyList()
    ) : CheckInUiState()
    data class NoMatch(
        val logs: List<String> = emptyList(),
        val message: String = "No locally verified face match was found.",
        val hasAccessChallenge: Boolean = false,
        val termuxAvailable: Boolean = true
    ) : CheckInUiState()
    data class Error(val message: String, val logs: List<String> = emptyList()) : CheckInUiState()
}




