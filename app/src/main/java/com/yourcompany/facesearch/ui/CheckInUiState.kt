package com.yourcompany.facesearch.ui

sealed class CheckInUiState {
    object Idle : CheckInUiState()
    data class Loading(val progress: Float = 0f) : CheckInUiState()
    data class Success(val matches: List<WebMatchDisplay>) : CheckInUiState()
    object NoMatch : CheckInUiState()
    data class Error(val message: String) : CheckInUiState()
}

data class WebMatchDisplay(
    val name: String,
    val source: String,
    val profileUrl: String,
    val confidence: Double,
    val imageUrl: String? = null
)
