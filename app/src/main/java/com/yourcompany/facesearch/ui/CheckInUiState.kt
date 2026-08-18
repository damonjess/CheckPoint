package com.yourcompany.facesearch.ui

import android.graphics.Bitmap
import com.yourcompany.facesearch.ui.models.WebMatchDisplay

sealed class CheckInUiState {
    object Idle : CheckInUiState()
    data class Loading(
        val progress: Float = 0f,
        val logs: List<String> = emptyList()
    ) : CheckInUiState()
    data class Success(
        val matches: List<WebMatchDisplay>,
        val logs: List<String> = emptyList()
    ) : CheckInUiState()
    data class Confirming(val faceBitmap: Bitmap) : CheckInUiState()
    data class NoFaceDetected(val logs: List<String> = emptyList()) : CheckInUiState()
    data class NoMatch(
        val logs: List<String> = emptyList(),
        val message: String = "No locally verified face match was found.",
        val hasAccessChallenge: Boolean = false
    ) : CheckInUiState()
    data class Error(val message: String, val logs: List<String> = emptyList()) : CheckInUiState()
}




