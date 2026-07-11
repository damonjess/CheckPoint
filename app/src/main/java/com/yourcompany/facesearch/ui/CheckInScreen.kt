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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
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
    onOpenDirectoryClick: (String) -> Unit, // renamed but kept for compatibility
    onRetryClick: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Scaffold(topBar = { TopAppBar(title = { Text("Sherlock Face Search") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(modifier = Modifier.height(16.dp))
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
                    Text("Take a photo to search the web", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                is CheckInUiState.Loading -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Searching the web...")
                }

                is CheckInUiState.Success -> {
                    if (uiState.matches.isEmpty()) {
                        Text("No matches found")
                    } else {
                        Text(
                            "Possible Matches Found",
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
                }

                is CheckInUiState.NoMatch -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No matches found on the web", fontWeight = FontWeight.Medium)
                            TextButton(onClick = onRetryClick) { Text("Try Again") }
                        }
                    }
                }

                is CheckInUiState.Error -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
            .size(180.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(72.dp))
        }
    }
}

@Composable
private fun MatchCard(match: WebMatchDisplay, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Match photo
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
                Text(match.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(match.source, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${(match.confidence * 100).toInt()}% confidence", fontSize = 13.sp)
            }

            Button(onClick = onClick) {
                Text("View Profile")
            }
        }
    }
}
