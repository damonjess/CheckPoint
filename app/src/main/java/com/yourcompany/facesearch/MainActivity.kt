package com.yourcompany.facesearch

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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

        setContent {
            MaterialTheme {
                val checkInViewModel: CheckInViewModel = viewModel()
                var screen by remember { mutableStateOf(Screen.SEARCH) }

                val galleryLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    uri?.let {
                        try {
                            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                val source = ImageDecoder.createSource(contentResolver, it)
                                ImageDecoder.decodeBitmap(source)
                            } else {
                                @Suppress("DEPRECATION")
                                MediaStore.Images.Media.getBitmap(contentResolver, it)
                            }
                            val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                            checkInViewModel.onPhotoCaptured(softwareBitmap)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                when (screen) {
                    Screen.SEARCH -> CheckInScreen(
                        capturedBitmap = checkInViewModel.capturedBitmap,
                        uiState = checkInViewModel.uiState,
                        searchMode = checkInViewModel.searchMode,
                        targetHint = checkInViewModel.targetHint,
                        debugMode = checkInViewModel.debugMode,
                        onTargetHintChange = { checkInViewModel.targetHint = it },
                        onSearchModeChange = { checkInViewModel.searchMode = it },
                        onDebugModeChange = { checkInViewModel.debugMode = it },
                        onCapturePhotoClick = { screen = Screen.CAMERA },
                        onSelectGalleryClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRetryClick = { checkInViewModel.onRetry() },
                        onConfirmFreeSearch = { checkInViewModel.onConfirmFreeSearch(it) }
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
