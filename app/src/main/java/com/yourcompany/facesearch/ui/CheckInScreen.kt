package com.yourcompany.facesearch.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

// High-contrast Amber for better readability
private val Amber = Color(0xFFFFB000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(
    capturedBitmap: Bitmap?,
    uiState: CheckInUiState,
    searchMode: SearchMode,
    targetHint: String,
    debugMode: Boolean,
    onTargetHintChange: (String) -> Unit,
    onSearchModeChange: (SearchMode) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onCapturePhotoClick: () -> Unit,
    onSelectGalleryClick: () -> Unit,
    onRetryClick: () -> Unit,
    onConfirmFreeSearch: (Bitmap) -> Unit,
    onGoogleLensOnlySearch: (Bitmap) -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sherlock Deep Search") }
            )
        }
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            PhotoPreview(
                bitmap = capturedBitmap,
                isScanning = uiState is CheckInUiState.Loading,
                size = if (uiState is CheckInUiState.Success || uiState is CheckInUiState.Loading) 100.dp else 180.dp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCapturePhotoClick,
                    modifier = Modifier.weight(1f),
                    enabled = uiState !is CheckInUiState.Loading
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (capturedBitmap == null) "Camera" else "New", maxLines = 1)
                }

                OutlinedButton(
                    onClick = onSelectGalleryClick,
                    modifier = Modifier.weight(1f),
                    enabled = uiState !is CheckInUiState.Loading
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Gallery", maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // OSINT Keyword Pivot
            OutlinedTextField(
                value = targetHint,
                onValueChange = onTargetHintChange,
                label = { Text("OSINT TARGET HINT (Name, City, ID)") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. John Doe Facebook") },
                singleLine = true,
                enabled = uiState !is CheckInUiState.Loading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Amber,
                    focusedLabelColor = Amber
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SEARCH ENGINE PROFILE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DEBUG", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Amber)
                    Switch(
                        checked = debugMode,
                        onCheckedChange = onDebugModeChange,
                        modifier = Modifier.scale(0.6f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeChip(
                    label = "Precision",
                    icon = Icons.Default.FilterCenterFocus,
                    selected = searchMode == SearchMode.PRECISION,
                    onClick = { onSearchModeChange(SearchMode.PRECISION) },
                    enabled = uiState !is CheckInUiState.Loading
                )
                ModeChip(
                    label = "Bypass",
                    icon = Icons.Default.Security,
                    selected = searchMode == SearchMode.BYPASS,
                    onClick = { onSearchModeChange(SearchMode.BYPASS) },
                    enabled = uiState !is CheckInUiState.Loading
                )
                ModeChip(
                    label = "Hyper",
                    icon = Icons.Default.Bolt,
                    selected = searchMode == SearchMode.HYPER,
                    onClick = { onSearchModeChange(SearchMode.HYPER) },
                    enabled = uiState !is CheckInUiState.Loading
                )
                ModeChip(
                    label = "Social",
                    icon = Icons.Default.People,
                    selected = searchMode == SearchMode.SOCIAL,
                    onClick = { onSearchModeChange(SearchMode.SOCIAL) },
                    enabled = uiState !is CheckInUiState.Loading
                )
                ModeChip(
                    label = "Social Opt",
                    icon = Icons.Default.Person,
                    selected = searchMode == SearchMode.SOCIAL_OPTIMIZED,
                    onClick = { onSearchModeChange(SearchMode.SOCIAL_OPTIMIZED) },
                    enabled = uiState !is CheckInUiState.Loading
                )
                ModeChip(
                    label = "🔥 Aggressive",
                    icon = Icons.Default.Bolt,
                    selected = searchMode == SearchMode.AGGRESSIVE,
                    onClick = { onSearchModeChange(SearchMode.AGGRESSIVE) },
                    enabled = uiState !is CheckInUiState.Loading
                )
                ModeChip(
                    label = "Raw",
                    icon = Icons.Default.Image,
                    selected = searchMode == SearchMode.RAW,
                    onClick = { onSearchModeChange(SearchMode.RAW) },
                    enabled = uiState !is CheckInUiState.Loading
                )
                ModeChip(
                    label = "Free",
                    icon = Icons.Default.FilterCenterFocus,
                    selected = searchMode == SearchMode.FREE,
                    onClick = { onSearchModeChange(SearchMode.FREE) },
                    enabled = uiState !is CheckInUiState.Loading
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val isFreeMode = searchMode == SearchMode.FREE || searchMode == SearchMode.AGGRESSIVE || searchMode == SearchMode.HYPER
            if (isFreeMode && capturedBitmap != null && uiState !is CheckInUiState.Loading) {
                Button(
                    onClick = { onConfirmFreeSearch(capturedBitmap) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        when (searchMode) {
                            SearchMode.FREE -> "🔍 Search Social Media (Free)"
                            SearchMode.AGGRESSIVE -> "🚀 Launch Biometric Social Scan"
                            SearchMode.HYPER -> "⚡ Execute Deep OSINT Search"
                            else -> "⚡ Launch Social Search"
                        },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Main Content Area
            Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                when (uiState) {
                    is CheckInUiState.Idle -> {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Select mode & scan to begin search",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    }

                    is CheckInUiState.Loading -> {
                        val consoleScrollState = rememberScrollState()
                        LaunchedEffect(uiState.logs.size) {
                            consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
                        }

                        Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                            LinearProgressIndicator(
                                progress = { uiState.progress },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = Amber,
                                trackColor = Amber.copy(alpha = 0.1f),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "SEARCH PROGRESS: ${(uiState.progress * 100).toInt()}% | MODE: ${searchMode.name}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Amber
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SherlockConsole(
                                logs = uiState.logs,
                                modifier = Modifier.fillMaxSize(),
                                showCursor = true,
                                scrollState = consoleScrollState
                            )
                        }
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
                        Column(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                            if (uiState.gemmaAnalysis != null) {
                                Text(
                                    text = "GEMMA-3 DEEP ANALYSIS",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Amber
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Amber.copy(alpha = 0.05f)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = uiState.gemmaAnalysis,
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            Text(
                                text = "FOUND ${uiState.matches.size} MATCHES",
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            uiState.matches.forEach { match ->
                                MatchCard(
                                    match = match,
                                    debugMode = debugMode,
                                    onClick = { uriHandler.openUri(match.profileUrl) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            // --- SCAN DIAGNOSTICS SECTION ---
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                "SCAN DIAGNOSTICS & LOGS",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = Amber
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SherlockConsole(
                                logs = uiState.logs,
                                modifier = Modifier.fillMaxWidth().height(150.dp)
                            )
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }

                    is CheckInUiState.NoFaceDetected -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ErrorState("No face detected", "Try HYPER or RAW mode if precision fails.", onRetryClick)
                            
                            if (uiState.logs.isNotEmpty()) {
                                Text(
                                    "DIAGNOSTIC CONSOLE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Amber,
                                    modifier = Modifier.align(Alignment.Start)
                                )
                                SherlockConsole(
                                    logs = uiState.logs,
                                    modifier = Modifier.fillMaxWidth().height(200.dp)
                                )
                            }
                        }
                    }

                    is CheckInUiState.NoMatch -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            ErrorState(
                                title = "No Results Found", 
                                message = "The search engines returned no visual matches for \"$targetHint\".", 
                                onRetry = onRetryClick
                            )

                            // Always show console logs if results are zero, so errors aren't hidden
                            Text(
                                "SHERLOCK OSINT CONSOLE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Amber,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SherlockConsole(
                                logs = uiState.logs,
                                modifier = Modifier.fillMaxWidth().height(200.dp)
                            )
                            
                Button(
                    onClick = { capturedBitmap?.let { onConfirmFreeSearch(it) } },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Switch to Browser Search (Free)", color = Color.White, fontWeight = FontWeight.Bold)
                }
                            
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }

                    is CheckInUiState.Error -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ErrorState("Search Error", uiState.message, onRetryClick)
                            
                            if (uiState.logs.isNotEmpty()) {
                                Text(
                                    "DIAGNOSTIC CONSOLE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Amber,
                                    modifier = Modifier.align(Alignment.Start)
                                )
                                SherlockConsole(
                                    logs = uiState.logs,
                                    modifier = Modifier.fillMaxWidth().height(200.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SherlockConsole(
    logs: List<String>,
    modifier: Modifier = Modifier,
    showCursor: Boolean = false,
    scrollState: ScrollState = rememberScrollState()
) {
    val clipboardManager = LocalClipboardManager.current
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = 0.3f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                Text(
                    "SHERLOCK OSINT CONSOLE v2.1",
                    color = Amber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                logs.forEach { log ->
                    Text(
                        text = "> $log",
                        color = Amber.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (showCursor) {
                    BlinkingCursor()
                }
            }

            // Copy Logs Button
            IconButton(
                onClick = {
                    val logText = logs.joinToString("\n")
                    clipboardManager.setText(AnnotatedString(logText))
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy Logs",
                    tint = Amber.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { 
            Text(
                text = label, 
                fontSize = 10.sp, 
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            ) 
        },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp)) },
        modifier = modifier
    )
}

@Composable
private fun BlinkingCursor() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Text(
        text = "> _",
        color = Amber.copy(alpha = alpha),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun ErrorState(title: String, message: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(message, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton(onClick = onRetry) { Text("Try Again") }
        }
    }
}

@Composable
private fun PhotoPreview(bitmap: Bitmap?, isScanning: Boolean = false, size: androidx.compose.ui.unit.Dp = 180.dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "scanning")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(
        modifier = Modifier.size(size + 16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isScanning) {
            Canvas(modifier = Modifier.fillMaxSize().rotate(rotation)) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(Amber.copy(alpha = 0.1f), Amber, Amber.copy(alpha = 0.1f))
                    ),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Your photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                
                if (isScanning) {
                    val scanLineY by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = size.value,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "scanLine"
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .offset(y = scanLineY.dp - (size / 2))
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Amber, Color.Transparent)
                                )
                            )
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.4f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MatchCard(match: WebMatchDisplay, debugMode: Boolean, onClick: () -> Unit) {
    val isHighConfidence = match.score > 5000
    
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isHighConfidence) 6.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighConfidence) Amber.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        border = if (isHighConfidence) androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = 0.5f)) else null
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(if (isHighConfidence) 80.dp else 64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (match.imageUrl != null) {
                    AsyncImage(
                        model = match.imageUrl,
                        contentDescription = "Profile photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = when {
                            match.source.contains("linkedin", ignoreCase = true) -> Icons.Default.People
                            match.source.contains("facebook", ignoreCase = true) -> Icons.Default.People
                            match.source.contains("instagram", ignoreCase = true) -> Icons.Default.CameraAlt
                            else -> Icons.Default.Person
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (isHighConfidence) {
                    Surface(
                        color = Amber,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Text(
                            "HIGH CONFIDENCE MATCH",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.Black
                        )
                    }
                }

                Text(
                    text = match.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isHighConfidence) 18.sp else 16.sp,
                    maxLines = 3,
                    lineHeight = 22.sp
                )
                
                if (debugMode) {
                    Text(
                        text = "SCORE: ${match.score}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Amber,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                if (match.extraImages.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        match.extraImages.take(5).forEach { extraUrl ->
                            AsyncImage(
                                model = extraUrl,
                                contentDescription = "Extra photo",
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }

                Surface(
                    color = when {
                        match.source.contains("facebook", ignoreCase = true) -> Color(0xFF1877F2).copy(alpha = 0.1f)
                        match.source.contains("instagram", ignoreCase = true) -> Color(0xFFE4405F).copy(alpha = 0.1f)
                        match.source.contains("linkedin", ignoreCase = true) -> Color(0xFF0A66C2).copy(alpha = 0.1f)
                        match.source.contains("twitter", ignoreCase = true) || match.source.contains(" x.", ignoreCase = true) -> Color(0xFF000000).copy(alpha = 0.1f)
                        match.source.contains("pinterest", ignoreCase = true) -> Color(0xFFE60023).copy(alpha = 0.1f)
                        match.source.contains("youtube", ignoreCase = true) -> Color(0xFFFF0000).copy(alpha = 0.1f)
                        match.source.contains("tiktok", ignoreCase = true) -> Color(0xFF000000).copy(alpha = 0.1f)
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = match.source.uppercase(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            match.source.contains("facebook", ignoreCase = true) -> Color(0xFF1877F2)
                            match.source.contains("instagram", ignoreCase = true) -> Color(0xFFE4405F)
                            match.source.contains("linkedin", ignoreCase = true) -> Color(0xFF0A66C2)
                            match.source.contains("pinterest", ignoreCase = true) -> Color(0xFFE60023)
                            match.source.contains("youtube", ignoreCase = true) -> Color(0xFFFF0000)
                            match.source.contains("tiktok", ignoreCase = true) -> Color(0xFFEE1D52)
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }
            }

            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Default.Bolt, 
                    contentDescription = "View Profile",
                    tint = if (isHighConfidence) Amber else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
