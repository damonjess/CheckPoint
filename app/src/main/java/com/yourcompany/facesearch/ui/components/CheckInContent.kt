package com.yourcompany.facesearch.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    modifier: Modifier = Modifier
) {
    val consoleScrollState = rememberScrollState()
    LaunchedEffect(uiState.logs.size) {
        consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SocialOrbitSearchScreen(
            faceBitmap = capturedBitmap,
            progress = uiState.progress,
            statusText = uiState.logs.lastOrNull() ?: "Scanning socials...",
            modifier = Modifier.fillMaxWidth().height(320.dp)
        )
        
        Spacer(modifier = Modifier.height(20.dp))
        
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
    onMatchClick: (WebMatchDisplay) -> Unit,
    modifier: Modifier = Modifier
) {
    val sortedMatches = remember(uiState.matches) {
        uiState.matches.sortedWith(
            compareByDescending<WebMatchDisplay> { it.isSocial }
                .thenByDescending { it.score }
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
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
                border = BorderStroke(1.dp, Amber.copy(alpha = 0.3f)),
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
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            color = Amber
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        // Match list
        sortedMatches.forEach { match ->
            MatchCard(
                match = match,
                debugMode = debugMode,
                onClick = { onMatchClick(match) }
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
    }
}

@Composable
fun NoFaceContent(
    logs: List<String>,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ErrorState("No face detected", "Try HYPER or RAW mode if precision fails.", onRetryClick)
        
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
    logs: List<String>,
    onRetryClick: () -> Unit,
    onConfirmFreeSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ErrorState(
            title = "No Results Found", 
            message = "The search engines returned no visual matches for \"$targetHint\".", 
            onRetry = onRetryClick
        )

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
            onClick = onConfirmFreeSearch,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Switch to Browser Search (Free)", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ErrorContent(
    message: String,
    logs: List<String>,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier
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
