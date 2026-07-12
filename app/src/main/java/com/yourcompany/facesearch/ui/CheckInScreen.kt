package com.yourcompany.facesearch.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

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
                title = { Text("Social Media Search") }
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

            // Captured Photo Preview
            PhotoPreview(bitmap = capturedBitmap)

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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Progress Bar
                        LinearProgressIndicator(
                            progress = { uiState.progress },
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = "SEARCH PROGRESS: ${(uiState.progress * 100).toInt()}%",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Text(
                            "Sherlock Deep Search Active",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Analyzing internet-wide biometrics...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "This can take up to 3 minutes. Please keep the app open.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        val bypassSteps = listOf(
                            "Initializing headless browser...",
                            "Rotating proxy through residential IP...",
                            "Injecting Chrome User-Agent headers...",
                            "Bypassing Cloudflare/WAF restrictions...",
                            "Scraping public social media fragments...",
                            "Reconstructing facial identity..."
                        )
                        bypassSteps.forEach { step ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth(0.8f)
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Text(
                                    text = "> $step", 
                                    fontSize = 11.sp, 
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.Red.copy(alpha = 0.9f)
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

                is CheckInUiState.NoMatch -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("No public profiles found", fontWeight = FontWeight.Medium)
                            TextButton(onClick = onRetryClick) { Text("Try Again") }
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
private fun PhotoPreview(bitmap: Bitmap?) {
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
            // Profile Image
            AsyncImage(
                model = match.imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = match.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                
                Surface(
                    color = when(match.source.lowercase()) {
                        "facebook" -> Color(0xFF1877F2).copy(alpha = 0.1f)
                        "instagram" -> Color(0xFFE4405F).copy(alpha = 0.1f)
                        "linkedin" -> Color(0xFF0A66C2).copy(alpha = 0.1f)
                        "x (twitter)" -> Color(0xFF000000).copy(alpha = 0.1f)
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
                        color = when(match.source.lowercase()) {
                            "facebook" -> Color(0xFF1877F2)
                            "instagram" -> Color(0xFFE4405F)
                            "linkedin" -> Color(0xFF0A66C2)
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "Similarity: ${(match.confidence * 100).toInt()}%",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.Red
                )
            }

            TextButton(onClick = onClick) {
                Text("View Profile")
            }
        }
    }
}
