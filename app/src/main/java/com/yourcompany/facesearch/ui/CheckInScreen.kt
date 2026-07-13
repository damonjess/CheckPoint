package com.yourcompany.facesearch.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// High-contrast Amber for better readability
private val Amber = Color(0xFFFFB000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(
    capturedBitmap: Bitmap?,
    uiState: CheckInUiState,
    onCapturePhotoClick: () -> Unit,
    onRetryClick: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sherlock Deep Search") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(modifier = Modifier.height(16.dp))

            PhotoPreview(
                bitmap = capturedBitmap,
                isScanning = uiState is CheckInUiState.Loading
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onCapturePhotoClick,
                modifier = Modifier.fillMaxWidth(0.8f),
                enabled = uiState !is CheckInUiState.Loading
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (capturedBitmap == null) "Take Photo" else "New Search")
            }

            Spacer(modifier = Modifier.height(32.dp))

            when (uiState) {
                is CheckInUiState.Idle -> {
                    Text(
                        text = "Take a photo to find social media profiles",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }

                is CheckInUiState.Loading -> {
                    val consoleScrollState = rememberScrollState()
                    
                    // Auto-scroll to bottom when new logs arrive
                    LaunchedEffect(uiState.logs.size) {
                        consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LinearProgressIndicator(
                            progress = { uiState.progress },
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Amber,
                            trackColor = Amber.copy(alpha = 0.1f),
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = "SEARCH PROGRESS: ${(uiState.progress * 100).toInt()}%",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Amber
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.Black),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = 0.3f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxSize()
                                    .verticalScroll(consoleScrollState)
                            ) {
                                Text(
                                    "SHERLOCK OSINT CONSOLE v2.1",
                                    color = Amber,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                uiState.logs.forEach { log ->
                                    Text(
                                        text = "> $log",
                                        color = Amber.copy(alpha = 0.9f),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                                
                                // Blinking cursor effect
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
                        }
                    }
                }

                is CheckInUiState.Success -> {
                    Text(
                        text = "Found ${uiState.matches.size} social media profiles",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn {
                        items(uiState.matches) { match ->
                            MatchCard(
                                match = match,
                                onClick = { uriHandler.openUri(match.profileUrl) }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                is CheckInUiState.NoFaceDetected -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("No face detected", fontWeight = FontWeight.Bold)
                            Text("Please make sure your face is clearly visible and well-lit.", fontSize = 14.sp)
                            TextButton(onClick = onRetryClick) { Text("Try Again") }
                        }
                    }
                }

                is CheckInUiState.NoMatch -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("No public profiles found", fontWeight = FontWeight.Bold)
                            Text("Search completed successfully, but no matching social media profiles were located on the public web.", fontSize = 14.sp)
                            TextButton(onClick = onRetryClick) { Text("Search Another Photo") }
                        }
                    }
                }

                is CheckInUiState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(uiState.message)
                            TextButton(onClick = onRetryClick) { Text("Retry") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoPreview(bitmap: Bitmap?, isScanning: Boolean = false) {
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
        modifier = Modifier.size(220.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isScanning) {
            Canvas(modifier = Modifier.fillMaxSize().rotate(rotation)) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Amber.copy(alpha = 0.1f),
                            Amber,
                            Amber.copy(alpha = 0.1f)
                        )
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
                .size(200.dp)
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
                        targetValue = 200.dp.value,
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
                            .offset(y = scanLineY.dp - 100.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Amber,
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MatchCard(match: WebMatchDisplay, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Bind item.imageUrl (from SerpApi thumbnail) to Coil AsyncImage
            AsyncImage(
                model = match.imageUrl,
                contentDescription = "Profile photo",
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = match.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1
                )
                
                // Bind item.source to the text badge for native social platform showcase
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
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "Source: ${match.source}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Bind item.profileUrl (from SerpApi link) to View Profile action
            TextButton(onClick = onClick) {
                Text("View Profile", fontWeight = FontWeight.Bold)
            }
        }
    }
}
