package com.yourcompany.facesearch

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.tflite.java.TfLite
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourcompany.facesearch.network.LocalServer
import com.yourcompany.facesearch.ui.CameraCaptureScreen
import com.yourcompany.facesearch.ui.CheckInScreen
import com.yourcompany.facesearch.ui.CheckInViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private enum class Screen { SEARCH, CAMERA }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize LiteRT (TensorFlow Lite) from Google Play Services
        TfLite.initialize(this)

        // Connectivity Probe
        lifecycleScope.launch(Dispatchers.IO) {
            val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()
            val urls = listOf("http://127.0.0.1:3000/ping", "http://10.0.2.2:3000/ping")
            for (url in urls) {
                try {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Log.i("FaceSearch", "✓ Backend Cluster Detected at $url")
                        }
                    }
                } catch (e: Exception) { /* Silent fail */ }
            }
        }

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
                        onTargetHintChange = { checkInViewModel.onTargetHintChange(it) },
                        onSearchModeChange = { checkInViewModel.searchMode = it },
                        onDebugModeChange = { checkInViewModel.debugMode = it },
                        onCapturePhotoClick = { screen = Screen.CAMERA },
                        onSelectGalleryClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRetryClick = { checkInViewModel.onRetry() },
                        onConfirmFreeSearch = { checkInViewModel.onConfirmFreeSearch(it) },
                        onGoogleLensOnlySearch = { checkInViewModel.onGoogleLensOnlySearch(it) }
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
