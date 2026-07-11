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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start local embedded server
        LocalServer.start(this)

        setContent {
            MaterialTheme {
                val viewModel: CheckInViewModel = viewModel()
                var showCamera by remember { mutableStateOf(false) }

                if (showCamera) {
                    CameraCaptureScreen(
                        onPhotoCaptured = { bitmap ->
                            viewModel.onPhotoCaptured(bitmap)
                            showCamera = false
                        },
                        onCancel = { showCamera = false }
                    )
                } else {
                    CheckInScreen(
                        capturedBitmap = viewModel.capturedBitmap,
                        uiState = viewModel.uiState,
                        onCapturePhotoClick = {
                            showCamera = true
                        },
                        onRetryClick = {
                            viewModel.onRetry()
                        }
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
