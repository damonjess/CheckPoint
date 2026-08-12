package com.yourcompany.facesearch.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourcompany.facesearch.ui.components.*
import com.yourcompany.facesearch.ui.models.WebMatchDisplay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(
    capturedBitmap: Bitmap?,
    uiState: CheckInUiState,
    searchMode: SearchMode,
    isSearching: Boolean,
    targetHint: String,
    debugMode: Boolean,
    onTargetHintChange: (String) -> Unit,
    onSearchModeChange: (SearchMode) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onCapturePhotoClick: () -> Unit,
    onSelectGalleryClick: () -> Unit,
    onRetryClick: () -> Unit,
    onConfirmFreeSearch: (Bitmap) -> Unit,
    onGoogleLensOnlySearch: (Bitmap) -> Unit,
    onLoadHighRes: (WebMatchDisplay) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val isLoading = uiState is CheckInUiState.Loading || isSearching

    Scaffold(
        containerColor = Color(0xFFFBFBFB),
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        "sherlock", 
                        fontWeight = FontWeight.Black, 
                        fontSize = 22.sp,
                        letterSpacing = (-1).sp
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onRetryClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, 
                            contentDescription = "Back",
                            modifier = Modifier.size(20.dp),
                            tint = Color.Gray
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            item {
                if (!isLoading) {
                    PhotoPreview(
                        bitmap = capturedBitmap,
                        isScanning = false,
                        size = if (uiState is CheckInUiState.Success) 100.dp else 180.dp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    PhotoCaptureActions(
                        hasPhoto = capturedBitmap != null,
                        isLoading = false,
                        onCapturePhotoClick = onCapturePhotoClick,
                        onSelectGalleryClick = onSelectGalleryClick
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OsintHintField(
                        value = targetHint,
                        onValueChange = onTargetHintChange,
                        isEnabled = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    SearchModeSelector(
                        searchMode = searchMode,
                        debugMode = debugMode,
                        isLoading = false,
                        onSearchModeChange = onSearchModeChange,
                        onDebugModeChange = onDebugModeChange
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                val isFreeMode = searchMode == SearchMode.FREE || searchMode == SearchMode.AGGRESSIVE || searchMode == SearchMode.HYPER
                if ((isFreeMode && capturedBitmap != null) && !isLoading) {
                    Button(
                        onClick = { onConfirmFreeSearch(capturedBitmap) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(bottom = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF66BB6A)),
                        shape = RoundedCornerShape(28.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Bolt, 
                            contentDescription = null, 
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = when (searchMode) {
                                SearchMode.FREE -> "Search Social Media"
                                SearchMode.AGGRESSIVE -> "Launch Biometric Scan"
                                SearchMode.HYPER -> "Execute Deep OSINT Search"
                                SearchMode.DEEP_CRAWL -> "Execute Deep OSINT Search"
                                else -> "Launch Social Search"
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }
            }

            // Main Content Area
            item {
                when (uiState) {
                    is CheckInUiState.Idle -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(200.dp), 
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Select mode & scan to begin search",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    }

                    is CheckInUiState.Loading -> {
                        LoadingContent(
                            uiState = uiState,
                            capturedBitmap = capturedBitmap
                        )
                    }

                    is CheckInUiState.Confirming -> {
                        FaceSearchConfirmScreen(
                            croppedBitmap = uiState.faceBitmap,
                            nameHint = targetHint,
                            searchMode = searchMode,
                            onConfirm = { onConfirmFreeSearch(uiState.faceBitmap) },
                            onGoogleLensOnly = { onGoogleLensOnlySearch(uiState.faceBitmap) },
                            onCancel = onRetryClick
                        )
                    }

                    is CheckInUiState.Success -> {
                        SuccessContent(
                            uiState = uiState,
                            debugMode = debugMode,
                            onLoadHighRes = onLoadHighRes,
                            onMatchClick = { match -> uriHandler.openUri(match.profileUrl) }
                        )
                    }

                    is CheckInUiState.NoFaceDetected -> {
                        NoFaceContent(
                            logs = uiState.logs,
                            onRetryClick = onRetryClick
                        )
                    }

                    is CheckInUiState.NoMatch -> {
                        NoMatchContent(
                            targetHint = targetHint,
                            logs = uiState.logs,
                            onRetryClick = onRetryClick,
                            onConfirmFreeSearch = { capturedBitmap?.let { onConfirmFreeSearch(it) } }
                        )
                    }

                    is CheckInUiState.Error -> {
                        ErrorContent(
                            message = uiState.message,
                            logs = uiState.logs,
                            onRetryClick = onRetryClick
                        )
                    }
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
