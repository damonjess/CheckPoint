package com.yourcompany.facesearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.tflite.java.TfLite
import com.yourcompany.facesearch.network.LocalServer
import com.yourcompany.facesearch.ui.CameraCaptureScreen
import com.yourcompany.facesearch.ui.CheckInScreen
import com.yourcompany.facesearch.ui.CheckInViewModel

private enum class Screen { SEARCH, CAMERA }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isTfLiteInitialized = mutableStateOf(false)
        TfLite.initialize(this).addOnCompleteListener {
            isTfLiteInitialized.value = true
        }

        setContent {
            if (isTfLiteInitialized.value) {
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
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    override fun onDestroy() {
        LocalServer.stop()
        super.onDestroy()
    }
}
