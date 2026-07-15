package com.yourcompany.facesearch.ui

import android.graphics.Bitmap

sealed class CheckInUiState {
    object Idle : CheckInUiState()
    data class Loading(
        val progress: Float = 0f,
        val logs: List<String> = emptyList()
    ) : CheckInUiState()
    data class Success(
        val matches: List<WebMatchDisplay>,
        val gemmaAnalysis: String? = null
    ) : CheckInUiState()
    data class Confirming(val faceBitmap: Bitmap) : CheckInUiState()
    object NoFaceDetected : CheckInUiState()
    data class NoMatch(val logs: List<String> = emptyList()) : CheckInUiState()
    data class Error(val message: String) : CheckInUiState()
}

data class WebMatchDisplay(
    val name: String,
    val source: String,
    val profileUrl: String,
    val score: Int,
    val imageUrl: Any? = null
)
