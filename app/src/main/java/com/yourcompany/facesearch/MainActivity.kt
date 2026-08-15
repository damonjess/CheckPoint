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
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.yourcompany.facesearch.network.LocalServer
import com.yourcompany.facesearch.ui.CameraCaptureScreen
import com.yourcompany.facesearch.ui.CheckInScreen
import com.yourcompany.facesearch.ui.CheckInViewModel
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private enum class Screen { SEARCH, CAMERA }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Configure Global Image Loader for OSINT thumbnails
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(OkHttpNetworkFetcherFactory(OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .addInterceptor { chain ->
                            val requestUrl = chain.request().url
                            val referer = "https://${requestUrl.host}/"
                            val request = chain.request().newBuilder()
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                                .header("Referer", referer)
                                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                                .build()
                            chain.proceed(request)
                        }
                        .build()))
                }
                .build()
        }

        // Start Local Hosting Service (face probe bypass for visual engines)
        LocalServer.start(this)

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

                val gemmaReady by checkInViewModel.gemmaReady.collectAsState()
                val gemmaError by checkInViewModel.gemmaError.collectAsState()

                when (screen) {
                    Screen.SEARCH -> CheckInScreen(
                        capturedBitmap = checkInViewModel.capturedBitmap,
                        uiState = checkInViewModel.uiState,
                        searchMode = checkInViewModel.searchMode,
                        sensitivity = checkInViewModel.sensitivity,
                        fullFaceMode = checkInViewModel.fullFaceMode,
                        isSearching = checkInViewModel.isSearching,
                        targetHint = checkInViewModel.targetHint,
                        debugMode = checkInViewModel.debugMode,
                        onTargetHintChange = { checkInViewModel.onTargetHintChange(it) },
                        onSearchModeChange = { checkInViewModel.searchMode = it },
                        onSensitivityChange = { checkInViewModel.sensitivity = it },
                        onFullFaceModeChange = { checkInViewModel.fullFaceMode = it },
                        onDebugModeChange = { checkInViewModel.debugMode = it },
                        onCapturePhotoClick = { screen = Screen.CAMERA },
                        onSelectGalleryClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRetryClick = { checkInViewModel.onRetry() },
                        onConfirmFreeSearch = { checkInViewModel.onConfirmFreeSearch(it) },
                        onGoogleLensOnlySearch = { checkInViewModel.onGoogleLensOnlySearch(it) },
                        onLoadHighRes = { checkInViewModel.loadHighRes(it) },
                        gemmaReady = gemmaReady,
                        gemmaError = gemmaError
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
