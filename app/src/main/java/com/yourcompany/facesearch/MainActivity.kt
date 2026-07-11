package com.yourcompany.facesearch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourcompany.facesearch.ui.CameraCaptureScreen
import com.yourcompany.facesearch.ui.CheckInScreen
import com.yourcompany.facesearch.ui.CheckInViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val viewModel: CheckInViewModel = viewModel()
                val context = LocalContext.current
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
                        onOpenDirectoryClick = { url ->
                            if (url.isNotEmpty()) {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            }
                        },
                        onRetryClick = {
                            viewModel.onRetry()
                        }
                    )
                }
            }
        }
    }
}
