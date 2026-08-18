package com.yourcompany.facesearch.ui.components

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourcompany.facesearch.ui.Amber
import com.yourcompany.facesearch.ui.CheckInUiState
import com.yourcompany.facesearch.ui.SearchMode
import com.yourcompany.facesearch.ui.models.WebMatchDisplay

@Composable
fun LoadingContent(
    uiState: CheckInUiState.Loading,
    capturedBitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val consoleScrollState = rememberScrollState()
    LaunchedEffect(uiState.logs.size) {
        consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(360.dp),
            contentAlignment = Alignment.Center
        ) {
            SocialOrbitSearchScreen(
                faceBitmap = capturedBitmap,
                modifier = Modifier.fillMaxSize()
            )

            // ---- OVERLAY STATUS (Moved to bottom of box to avoid face overlap) ----
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = uiState.logs.lastOrNull() ?: "Scanning socials...",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.DarkGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = uiState.progress,
                    modifier = Modifier
                        .width(200.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF00E5FF),
                    trackColor = Color(0xFFE0E0E0)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "LIVE OSINT EXTRACTION CONSOLE",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = Amber
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        SherlockConsole(
            logs = uiState.logs,
            modifier = Modifier.fillMaxWidth().height(200.dp),
            showCursor = true,
            scrollState = consoleScrollState
        )
    }
}

@Composable
fun SuccessContent(
    uiState: CheckInUiState.Success,
    debugMode: Boolean,
    onLoadHighRes: (WebMatchDisplay) -> Unit,
    onMatchClick: (WebMatchDisplay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val verifiedMatches = remember(uiState.matches) {
        uiState.matches.filter { it.isFaceVerified }
            .sortedByDescending { it.score }
    }
    val likelyMatches = remember(uiState.matches) {
        uiState.matches.filter { it.isLikelyFaceMatch && !it.isFaceVerified }
            .sortedByDescending { it.score }
    }
    val visualLeads = remember(uiState.matches) {
        uiState.matches.filterNot { it.isFaceVerified || it.isLikelyFaceMatch }
            .sortedWith(compareByDescending<WebMatchDisplay> { it.isSocial }.thenByDescending { it.score })
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    verifiedMatches.isNotEmpty() -> "face-verified matches"
                    likelyMatches.isNotEmpty() -> "possible face matches"
                    else -> "visual leads"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.Black
            )

            IconButton(onClick = {
                val summary = uiState.matches.take(10).joinToString("\n\n") {
                    "${when {
                        it.isFaceVerified -> "Verified face match"
                        it.isLikelyFaceMatch -> "Possible face match — review manually"
                        else -> "Unverified visual lead"
                    }}: ${it.displayName} (${it.source})\n${it.profileUrl}"
                }
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, "Sherlock visual-search results:\n\n$summary")
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(sendIntent, null))
            }) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.Gray, modifier = Modifier.size(20.dp))
            }
        }

        if (verifiedMatches.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            verifiedMatches.forEachIndexed { index, match ->
                MatchCard(
                    match = match,
                    isPrimary = index == 0,
                    debugMode = debugMode,
                    onLoadHighRes = { onLoadHighRes(match) },
                    onClick = { onMatchClick(match) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        if (likelyMatches.isNotEmpty()) {
            if (verifiedMatches.isNotEmpty()) Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "POSSIBLE FACE MATCHES — REVIEW MANUALLY",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = Amber
            )
            Text(
                text = "These candidates have local face similarity below the confirmation threshold. They are not confirmed matches.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray
            )
            Spacer(modifier = Modifier.height(8.dp))
            likelyMatches.forEach { match ->
                MatchCard(
                    match = match,
                    isPrimary = false,
                    debugMode = debugMode,
                    onLoadHighRes = { onLoadHighRes(match) },
                    onClick = { onMatchClick(match) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        if (visualLeads.isNotEmpty()) {
            if (verifiedMatches.isNotEmpty() || likelyMatches.isNotEmpty()) Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "UNVERIFIED VISUAL LEADS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = Amber
            )
            Text(
                text = "These links were returned by visual search but did not pass local face verification. They are leads only, not confirmed matches.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray
            )
            Spacer(modifier = Modifier.height(8.dp))
            visualLeads.forEach { match ->
                MatchCard(
                    match = match,
                    isPrimary = false,
                    debugMode = debugMode,
                    onLoadHighRes = { onLoadHighRes(match) },
                    onClick = { onMatchClick(match) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
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
    }
}

@Composable
fun NoFaceContent(
    logs: List<String>,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ErrorState(
            "No face detected",
            "Use a closer, well-lit photo with one full face visible. You can also choose a different photo from Gallery.",
            onRetryClick
        )
        
        if (logs.isNotEmpty()) {
            Text(
                "DIAGNOSTIC CONSOLE",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                modifier = Modifier.align(Alignment.Start)
            )
            SherlockConsole(
                logs = logs,
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )
        }
    }
}

@Composable
fun NoMatchContent(
    targetHint: String,
    message: String,
    hasAccessChallenge: Boolean,
    logs: List<String>,
    onRetryClick: () -> Unit,
    onGoogleLensOnly: () -> Unit,
    onConfirmFreeSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ErrorState(
            title = if (hasAccessChallenge) "Search Needs Your Action" else "No Confirmed Face Match",
            message = message.ifBlank {
                "No locally verified face match was found for \"$targetHint\"."
            },
            onRetry = onRetryClick
        )

        if (hasAccessChallenge) {
            Text(
                text = "A search provider requested an access check. Open the photo in Lens and complete any prompt yourself; this app will not automate that step.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray
            )
        }

        Text(
            "SHERLOCK OSINT CONSOLE",
            style = MaterialTheme.typography.labelSmall,
            color = Amber,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(8.dp))
        SherlockConsole(
            logs = logs,
            modifier = Modifier.fillMaxWidth().height(200.dp)
        )
        
        Button(
            onClick = onGoogleLensOnly,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Photo in Google Lens", color = Color.White, fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onConfirmFreeSearch,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            border = BorderStroke(1.dp, Color(0xFF4CAF50)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF2E7D32))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share Photo to Another Search App", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ErrorContent(
    message: String,
    logs: List<String>,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ErrorState("Search Error", message, onRetryClick)
        
        if (logs.isNotEmpty()) {
            Text(
                "DIAGNOSTIC CONSOLE",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                modifier = Modifier.align(Alignment.Start)
            )
            SherlockConsole(
                logs = logs,
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )
        }
    }
}



