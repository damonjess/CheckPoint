package com.yourcompany.facesearch.ui.components

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SearchOff
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
                faceBitmap = uiState.isolatedFace ?: capturedBitmap,
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
    val consoleScrollState = rememberScrollState()
    LaunchedEffect(uiState.logs.size) {
        consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
    }

    val verifiedMatches = remember(uiState.matches) {
        uiState.matches.filter { it.isFaceVerified }
            .sortedByDescending { it.score }
    }
    val likelyMatches = remember(uiState.matches) {
        uiState.matches.filter { it.isLikelyFaceMatch && !it.isFaceVerified }
            .sortedByDescending { it.score }
    }
    
    val hasStrongResults = (verifiedMatches.size + likelyMatches.size) > 0
    // Show visual candidates by default whenever there are no verified or likely face matches.
    var showReviewLeads by remember(uiState.matches) { mutableStateOf(!hasStrongResults) }
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
                val summary = (uiState.matches + uiState.tinEyeMatches).take(10).joinToString("\n\n") {
                    "${when {
                        it.isFaceVerified -> "Verified face match"
                        it.isLikelyFaceMatch -> "Possible face match — review manually"
                        it.source.contains("TinEye", ignoreCase = true) -> "Exact image occurrence — TinEye"
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

        if (uiState.tinEyeMatches.isNotEmpty()) {
            if (verifiedMatches.isNotEmpty() || likelyMatches.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
            }
            Text(
                text = "EXACT IMAGE OCCURRENCES — TINEYE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = Amber
            )
            Text(
                text = "The image or a related image was found on these webpages. These results are not filtered by local face similarity.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray
            )
            Spacer(modifier = Modifier.height(8.dp))
            uiState.tinEyeMatches.forEach { match ->
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
            if (verifiedMatches.isNotEmpty() || likelyMatches.isNotEmpty() || uiState.tinEyeMatches.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
            }
            Text(
                text = "IN-APP VISUAL CANDIDATES (${visualLeads.size})",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = Amber
            )
            Text(
                text = "These candidates contain one visible face and passed source filtering. They are ranked by local similarity only and are not identity matches.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray
            )

            if (!uiState.termuxAvailable) {
                val tipColor = if (!hasStrongResults) Color(0xFFFFCCBC) else Color(0xFFFFF9C4)
                val textColor = if (!hasStrongResults) Color(0xFFBF360C) else Color(0xFF5D4037)
                
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = tipColor),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (!hasStrongResults) 4.dp else 0.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Tip: Start the Termux OSINT helper for 5x deeper coverage and more verified matches.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            TextButton(onClick = { showReviewLeads = !showReviewLeads }) {
                Text(if (showReviewLeads) "Hide visual candidates" else "Show visual candidates")
            }
            if (showReviewLeads) {
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
            modifier = Modifier.fillMaxWidth().height(150.dp),
            scrollState = consoleScrollState
        )
    }
}

@Composable
fun NoFaceContent(
    reasons: List<String>,
    logs: List<String>,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val consoleScrollState = rememberScrollState()
    LaunchedEffect(logs.size) {
        consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ErrorState(
            "Face could not be used",
            reasons.joinToString("\n\n").ifBlank {
                "No clear single face was found. Use a closer, well-lit photo with one full face visible, or choose a different Gallery image."
            },
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
                modifier = Modifier.fillMaxWidth().height(200.dp),
                scrollState = consoleScrollState
            )
        }
    }
}

@Composable
fun NoMatchContent(
    uiState: CheckInUiState.NoMatch,
    targetHint: String,
    onRetryClick: () -> Unit,
    onTinEyeExactSearch: () -> Unit,
    onConfirmFreeSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = uiState.message
    val hasAccessChallenge = uiState.hasAccessChallenge
    val logs = uiState.logs
    
    val consoleScrollState = rememberScrollState()
    LaunchedEffect(logs.size) {
        consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            title = if (hasAccessChallenge) "Search Needs Your Action" else "No Confirmed Face Match",
            message = message.ifBlank {
                "No locally verified face match was found for \"$targetHint\"."
            },
            icon = if (hasAccessChallenge) Icons.Default.Info else Icons.Default.SearchOff,
            onRetry = onRetryClick
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (!uiState.termuxAvailable) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCCBC)),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFBF360C))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Tip: Results are limited because the Termux OSINT backend is not running. Start it to unlock deep web scanning.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFBF360C),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (hasAccessChallenge) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "A search provider requested an access check. Use the TinEye exact-image check below, or complete any provider prompt yourself; this app will not automate access checks.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        SearchTipsSection()
        
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "SHERLOCK OSINT CONSOLE",
            style = MaterialTheme.typography.labelSmall,
            color = Amber,
            modifier = Modifier.align(Alignment.Start).padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        SherlockConsole(
            logs = logs,
            modifier = Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 16.dp),
            scrollState = consoleScrollState
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onTinEyeExactSearch,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open TinEye Exact-Image Check", color = Color.White, fontWeight = FontWeight.Bold)
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
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SearchTipsSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "WHY NO MATCHES?",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        val tips = listOf(
            "Lighting & Angle" to "Ensure the face is well-lit and facing forward.",
            "Database Coverage" to "The person may not have a public digital footprint.",
            "Image Quality" to "Low-resolution images can hinder facial recognition."
        )
        
        tips.forEach { (title, desc) ->
            Row(modifier = Modifier.padding(bottom = 8.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Amber)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text(text = desc, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
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
    val consoleScrollState = rememberScrollState()
    LaunchedEffect(logs.size) {
        consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
    }

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
                modifier = Modifier.fillMaxWidth().height(200.dp),
                scrollState = consoleScrollState
            )
        }
    }
}
