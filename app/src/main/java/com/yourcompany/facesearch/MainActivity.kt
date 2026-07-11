package com.yourcompany.facesearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourcompany.facesearch.ui.CheckInScreen
import com.yourcompany.facesearch.ui.CheckInViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val viewModel: CheckInViewModel = viewModel()
                CheckInScreen(
                    capturedBitmap = viewModel.capturedBitmap,
                    uiState = viewModel.uiState,
                    onCapturePhotoClick = {
                        // TODO: Implement actual camera capture
                        // For now, this is just a placeholder action
                    },
                    onOpenDirectoryClick = { url ->
                        // TODO: Handle URL opening
                    },
                    onRetryClick = {
                        viewModel.onRetry()
                    }
                )
            }
        }
    }
}
