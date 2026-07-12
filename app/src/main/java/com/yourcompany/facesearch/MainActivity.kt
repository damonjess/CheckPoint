package com.yourcompany.facesearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourcompany.facesearch.network.LocalServer
import com.yourcompany.facesearch.ui.CameraCaptureScreen
import com.yourcompany.facesearch.ui.CheckInScreen
import com.yourcompany.facesearch.ui.CheckInViewModel

private enum class Screen { SEARCH, CAMERA }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // LocalServer.start(this) // Removed simulated server

        setContent {
            MaterialTheme {
                val checkInViewModel: CheckInViewModel = viewModel()
                var screen by remember { mutableStateOf(Screen.SEARCH) }

                when (screen) {
                    Screen.SEARCH -> CheckInScreen(
                        capturedBitmap = checkInViewModel.capturedBitmap,
                        uiState = checkInViewModel.uiState,
                        onCapturePhotoClick = { screen = Screen.CAMERA },
                        onRetryClick = { checkInViewModel.onRetry() }
                    )

                    Screen.CAMERA -> CameraCaptureScreen(
                        onPhotoCaptured = { bitmap ->
                            checkInViewModel.onPhotoCaptured(bitmap)
                            screen = Screen.SEARCH
                        },
                        onCancel = { screen = Screen.SEARCH }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        LocalServer.stop()
        super.onDestroy()
    }
}