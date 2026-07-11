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
import com.yourcompany.facesearch.ui.EnrollScreen
import com.yourcompany.facesearch.ui.EnrollViewModel

private enum class Screen { CHECK_IN, CAMERA_FOR_CHECK_IN, ENROLL, CAMERA_FOR_ENROLL }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        LocalServer.start(this)

        setContent {
            MaterialTheme {
                val checkInViewModel: CheckInViewModel = viewModel()
                val enrollViewModel: EnrollViewModel = viewModel()
                var screen by remember { mutableStateOf(Screen.CHECK_IN) }

                when (screen) {
                    Screen.CHECK_IN -> CheckInScreen(
                        capturedBitmap = checkInViewModel.capturedBitmap,
                        uiState = checkInViewModel.uiState,
                        onCapturePhotoClick = { screen = Screen.CAMERA_FOR_CHECK_IN },
                        onRetryClick = { checkInViewModel.onRetry() },
                        onManagePeopleClick = { screen = Screen.ENROLL }
                    )

                    Screen.CAMERA_FOR_CHECK_IN -> CameraCaptureScreen(
                        onPhotoCaptured = { bitmap ->
                            checkInViewModel.onPhotoCaptured(bitmap)
                            screen = Screen.CHECK_IN
                        },
                        onCancel = { screen = Screen.CHECK_IN }
                    )

                    Screen.ENROLL -> EnrollScreen(
                        viewModel = enrollViewModel,
                        onBack = { screen = Screen.CHECK_IN },
                        onCapturePhotoClick = { screen = Screen.CAMERA_FOR_ENROLL }
                    )

                    Screen.CAMERA_FOR_ENROLL -> CameraCaptureScreen(
                        onPhotoCaptured = { bitmap ->
                            enrollViewModel.onPhotoCaptured(bitmap)
                            screen = Screen.ENROLL
                        },
                        onCancel = { screen = Screen.ENROLL }
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