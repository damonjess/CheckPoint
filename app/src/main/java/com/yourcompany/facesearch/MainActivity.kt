package com.yourcompany.facesearch

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.yourcompany.facesearch.network.LocalServer
import com.yourcompany.facesearch.ui.Amber
import com.yourcompany.facesearch.ui.CyberGreen
import com.yourcompany.facesearch.ui.TacticalSlate
import com.yourcompany.facesearch.ui.TacticalSurface
import com.yourcompany.facesearch.ui.CameraCaptureScreen
import com.yourcompany.facesearch.ui.CheckInScreen
import com.yourcompany.facesearch.ui.CheckInViewModel
import com.yourcompany.facesearch.ui.ProfileDiscoveryScreen
import com.yourcompany.facesearch.ui.ProfileDiscoveryViewModel
import com.yourcompany.facesearch.ui.components.ImageForensicsBottomSheet
import com.yourcompany.facesearch.ui.components.TerminalConsoleScreen
import com.yourcompany.facesearch.ui.components.WatchlistBottomSheet
import com.yourcompany.facesearch.worker.WatchlistScheduler
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

private enum class Screen { SEARCH, CAMERA, PROFILES }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Schedule Automated Background Watchlist Worker (Build 7)
        WatchlistScheduler.schedulePeriodicCheck(this)

        // Request POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        // Configure Global Image Loader for OSINT thumbnails
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(OkHttpNetworkFetcherFactory(OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .addInterceptor { chain ->
                            val request = chain.request().newBuilder()
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36")
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
            val tacticalColorScheme = darkColorScheme(
                primary = CyberGreen,
                secondary = Amber,
                background = TacticalSlate,
                surface = TacticalSurface,
                surfaceVariant = TacticalSurface,
                onPrimary = TacticalSlate,
                onSecondary = TacticalSlate,
                onBackground = Color.White,
                onSurface = Color.White,
                onSurfaceVariant = Color.LightGray
            )
            MaterialTheme(colorScheme = tacticalColorScheme) {
                val checkInViewModel: CheckInViewModel = viewModel()
                val profileDiscoveryViewModel: ProfileDiscoveryViewModel = viewModel()
                var screen by remember { mutableStateOf(Screen.SEARCH) }
                var showWatchlist by remember { mutableStateOf(intent?.getLongExtra("watchlist_target_id", -1L) != -1L) }
                var showForensics by remember { mutableStateOf(false) }

                val galleryLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    uri?.let {
                        try {
                            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                val source = ImageDecoder.createSource(contentResolver, it)
                                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                                    val longEdge = maxOf(info.size.width, info.size.height)
                                    val sampleSize = ceil(longEdge / 2048.0)
                                        .toInt()
                                        .coerceAtLeast(1)
                                    
                                    decoder.setTargetSampleSize(sampleSize)
                                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                }
                            } else {
                                val options = BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }
                                contentResolver.openInputStream(it)?.use { stream ->
                                    BitmapFactory.decodeStream(stream, null, options)
                                }
                                
                                val longEdge = maxOf(options.outWidth, options.outHeight)
                                val sampleSize = ceil(longEdge / 2048.0)
                                    .toInt()
                                    .coerceAtLeast(1)
                                    
                                val decodeOptions = BitmapFactory.Options().apply {
                                    inSampleSize = sampleSize
                                }
                                contentResolver.openInputStream(it)?.use { stream ->
                                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                                } ?: throw Exception("Failed to open stream")
                            }
                            checkInViewModel.onPhotoCaptured(bitmap, it)
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
                        broadenLensCoverage = checkInViewModel.broadenLensCoverage,
                        sensitivity = checkInViewModel.sensitivity,
                        fullFaceMode = checkInViewModel.fullFaceMode,
                        isSearching = checkInViewModel.isSearching,
                        targetHint = checkInViewModel.targetHint,
                        debugMode = checkInViewModel.debugMode,
                        onTargetHintChange = { checkInViewModel.onTargetHintChange(it) },
                        onBroadenLensCoverageChange = { checkInViewModel.onBroadenLensCoverageChange(it) },
                        onSearchModeChange = { checkInViewModel.searchMode = it },
                        onSensitivityChange = { checkInViewModel.sensitivity = it },
                        onFullFaceModeChange = { checkInViewModel.fullFaceMode = it },
                        onDebugModeChange = { checkInViewModel.debugMode = it },
                        onCapturePhotoClick = { screen = Screen.CAMERA },
                        onProfileDiscoveryClick = { screen = Screen.PROFILES },
                        onSelectGalleryClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRetryClick = { checkInViewModel.onRetry() },
                        onConfirmSearch = { face, scene -> checkInViewModel.onConfirmSearch(face, scene) },
                        onConfirmFreeSearch = { checkInViewModel.onConfirmFreeSearch(it) },
                        onTinEyeExactSearch = { checkInViewModel.onTinEyeExactSearch(it) },
                        onLoadHighRes = { checkInViewModel.loadHighRes(it) },
                        onOpenForensicsClick = { showForensics = true },
                        serpApiKey = checkInViewModel.serpApiKey,
                        onSerpApiKeyChange = { checkInViewModel.onSerpApiKeyChange(it) },
                        onOpenWatchlist = { showWatchlist = true },
                        onExecuteCommand = { checkInViewModel.executeTerminalCommand(it) }
                    )

                    Screen.CAMERA -> CameraCaptureScreen(
                        onPhotoCaptured = { bitmap ->
                            checkInViewModel.onPhotoCaptured(bitmap)
                            screen = Screen.SEARCH
                        },
                        onCancel = { screen = Screen.SEARCH }
                    )

                    Screen.PROFILES -> ProfileDiscoveryScreen(
                        viewModel = profileDiscoveryViewModel,
                        onBack = { screen = Screen.SEARCH }
                    )
                }

                if (showWatchlist) {
                    WatchlistBottomSheet(
                        capturedBitmap = checkInViewModel.capturedBitmap,
                        onDismiss = { showWatchlist = false }
                    )
                }

                if (showForensics) {
                    ImageForensicsBottomSheet(
                        forensicsData = checkInViewModel.currentExifData,
                        uri = checkInViewModel.capturedUri,
                        bitmap = checkInViewModel.capturedBitmap,
                        onDismiss = { showForensics = false }
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
