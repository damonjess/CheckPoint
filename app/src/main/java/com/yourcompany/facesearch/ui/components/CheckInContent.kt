package com.yourcompany.facesearch.ui.components

import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.horizontalScroll
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourcompany.facesearch.ui.Amber
import com.yourcompany.facesearch.ui.CheckInUiState
import com.yourcompany.facesearch.ui.SearchMode
import com.yourcompany.facesearch.ui.models.WebMatchDisplay
import com.yourcompany.facesearch.util.ShareManager

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

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            contentAlignment = Alignment.Center
        ) {
            SocialOrbitSearchScreen(
                faceBitmap = uiState.isolatedFace ?: capturedBitmap,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---- STATUS (Below orbit search, zero overlap) ----
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedContent(
                targetState = uiState.currentStage,
                label = "scanStage"
            ) { stage ->
                Text(
                    text = stage.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.DarkGray
                )
            }
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
        
        Spacer(modifier = Modifier.height(16.dp))
        
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

enum class ResultsViewMode { LIST, GRID, GRAPH }

@Composable
fun SuccessContent(
    uiState: CheckInUiState.Success,
    debugMode: Boolean,
    onLoadHighRes: (WebMatchDisplay) -> Unit,
    onMatchClick: (WebMatchDisplay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var viewMode by rememberSaveable { mutableStateOf(ResultsViewMode.LIST) }
    var selectedFilter by rememberSaveable { mutableStateOf("All") }
    val filters = listOf("All", "Verified", "Socials", "Exact", "Adult")

    val consoleScrollState = rememberScrollState()
    LaunchedEffect(uiState.logs.size) {
        consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
    }

    var selectedLightboxUrl by remember { mutableStateOf<String?>(null) }
    var selectedLightboxTitle by remember { mutableStateOf<String?>(null) }

    val verifiedMatches = remember(uiState.matches) {
        uiState.matches.filter { it.isFaceVerified }
            .sortedByDescending { it.score }
    }
    val likelyMatches = remember(uiState.matches) {
        uiState.matches.filter { it.isLikelyFaceMatch && !it.isFaceVerified }
            .sortedByDescending { it.score }
    }
    
    val hasStrongResults = (verifiedMatches.size + likelyMatches.size) > 0
    
    val visualLeads = remember(uiState.matches) {
        uiState.matches.filterNot { it.isFaceVerified || it.isLikelyFaceMatch }
            .sortedWith(compareByDescending<WebMatchDisplay> { it.isSocial }.thenByDescending { it.score })
    }

    val filteredMatches = remember(uiState, selectedFilter) {
        val raw = uiState.matches + uiState.tinEyeMatches + uiState.adultMatches
        when (selectedFilter) {
            "Verified" -> raw.filter { it.isFaceVerified }
            "Socials" -> raw.filter { it.isSocial }
            "Exact" -> uiState.tinEyeMatches
            "Adult" -> uiState.adultMatches
            else -> raw
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    verifiedMatches.isNotEmpty() -> "Matches (${verifiedMatches.size})"
                    likelyMatches.isNotEmpty() -> "Possible (${likelyMatches.size})"
                    else -> "Visual Leads"
                },
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                color = Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // View Mode Toggle Pill (List vs Grid vs Network Graph)
                Surface(
                    color = Color(0xFFE2E8F0),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.padding(2.dp)) {
                        Surface(
                            onClick = { viewMode = ResultsViewMode.LIST },
                            color = if (viewMode == ResultsViewMode.LIST) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                            shadowElevation = if (viewMode == ResultsViewMode.LIST) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.List, contentDescription = "List", modifier = Modifier.size(12.dp), tint = Color.Black)
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("List", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }

                        Surface(
                            onClick = { viewMode = ResultsViewMode.GRID },
                            color = if (viewMode == ResultsViewMode.GRID) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                            shadowElevation = if (viewMode == ResultsViewMode.GRID) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = "Grid", modifier = Modifier.size(12.dp), tint = Color.Black)
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("Grid", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }

                        Surface(
                            onClick = { viewMode = ResultsViewMode.GRAPH },
                            color = if (viewMode == ResultsViewMode.GRAPH) Color(0xFF0F172A) else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                            shadowElevation = if (viewMode == ResultsViewMode.GRAPH) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Hub, contentDescription = "Graph", modifier = Modifier.size(12.dp), tint = if (viewMode == ResultsViewMode.GRAPH) Color(0xFF00E5FF) else Color.DarkGray)
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("Graph", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (viewMode == ResultsViewMode.GRAPH) Color.White else Color.DarkGray)
                            }
                        }
                    }
                }

                IconButton(onClick = {
                    val summary = (uiState.matches + uiState.tinEyeMatches + uiState.adultMatches).take(10).joinToString("\n\n") {
                        "${it.displayName} (${it.source})\n${it.profileUrl}"
                    }
                    ShareManager.shareText(
                        context = context,
                        text = "Sherlock visual-search results:\n\n$summary",
                        chooserTitle = "Share results",
                        mimeType = "text/plain",
                        fileName = "sherlock_results_${System.currentTimeMillis()}.txt"
                    )
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.Gray, modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- GLOBAL SOURCE FILTERING CHIPS ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filters.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selectedFilter == filter) Color.White else Color.Black) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.White,
                        labelColor = Color.Black,
                        selectedContainerColor = Amber,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (viewMode == ResultsViewMode.GRAPH) {
            IdentityNetworkGraph(
                targetFaceBitmap = uiState.isolatedFace,
                verifiedMatches = verifiedMatches,
                likelyMatches = likelyMatches,
                visualLeads = visualLeads,
                tinEyeMatches = uiState.tinEyeMatches,
                adultMatches = uiState.adultMatches,
                onMatchClick = onMatchClick,
                modifier = Modifier.fillMaxWidth().height(500.dp)
            )
        } else if (viewMode == ResultsViewMode.GRID) {
            // --- STAGGERED GRID VIEW MODE ---
            if (filteredMatches.isNotEmpty()) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().height(600.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp
                ) {
                    items(filteredMatches) { match ->
                        MatchCard(
                            match = match,
                            isPrimary = false,
                            debugMode = debugMode,
                            onLoadHighRes = { onLoadHighRes(match) },
                            onImageClick = { url ->
                                selectedLightboxUrl = url
                                selectedLightboxTitle = match.displayName
                            },
                            onClick = { onMatchClick(match) }
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Text("No matches found for filter: $selectedFilter", color = Color.Gray, fontWeight = FontWeight.Medium)
                }
            }
        } else {
            // --- LIST VIEW MODE ---
            Column(modifier = Modifier.fillMaxWidth()) {
                if (selectedFilter == "All") {
                    val hasAnyMatches = verifiedMatches.isNotEmpty() ||
                            likelyMatches.isNotEmpty() ||
                            uiState.tinEyeMatches.isNotEmpty() ||
                            uiState.adultMatches.isNotEmpty() ||
                            visualLeads.isNotEmpty()

                    if (!hasAnyMatches) {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text("No matches found for filter: All", color = Color.Gray, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        // 1. CONFIRMED VERIFIED MATCHES
                        if (verifiedMatches.isNotEmpty()) {
                            Text(
                                text = "CONFIRMED FACE MATCHES (${verifiedMatches.size})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = Amber
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            verifiedMatches.forEachIndexed { index, match ->
                                MatchCard(
                                    match = match,
                                    isPrimary = index == 0,
                                    debugMode = debugMode,
                                    onLoadHighRes = { onLoadHighRes(match) },
                                    onImageClick = { url ->
                                        selectedLightboxUrl = url
                                        selectedLightboxTitle = match.displayName
                                    },
                                    onClick = { onMatchClick(match) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }

                        // 2. POSSIBLE LIKELY MATCHES
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
                                    onImageClick = { url ->
                                        selectedLightboxUrl = url
                                        selectedLightboxTitle = match.displayName
                                    },
                                    onClick = { onMatchClick(match) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }

                        // 3. TINEYE EXACT OCCURRENCES
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
                                    onImageClick = { url ->
                                        selectedLightboxUrl = url
                                        selectedLightboxTitle = match.displayName
                                    },
                                    onClick = { onMatchClick(match) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }

                        // 4. ADULT PLATFORM HITS
                        if (uiState.adultMatches.isNotEmpty()) {
                            if (verifiedMatches.isNotEmpty() || likelyMatches.isNotEmpty() || uiState.tinEyeMatches.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            Text(
                                text = "ADULT PLATFORM HITS",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFE53935)
                            )
                            Text(
                                text = "These results were found on adult networks using identity dorks. They skip local face verification to ensure coverage.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            uiState.adultMatches.forEach { match ->
                                MatchCard(
                                    match = match,
                                    isPrimary = false,
                                    debugMode = debugMode,
                                    onLoadHighRes = { onLoadHighRes(match) },
                                    onImageClick = { url ->
                                        selectedLightboxUrl = url
                                        selectedLightboxTitle = match.displayName
                                    },
                                    onClick = { onMatchClick(match) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }

                        // 5. IN-APP VISUAL CANDIDATES
                        if (visualLeads.isNotEmpty()) {
                            if (verifiedMatches.isNotEmpty() || likelyMatches.isNotEmpty() || uiState.tinEyeMatches.isNotEmpty() || uiState.adultMatches.isNotEmpty()) {
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
                            Spacer(modifier = Modifier.height(8.dp))

                            visualLeads.forEach { match ->
                                MatchCard(
                                    match = match,
                                    isPrimary = false,
                                    debugMode = debugMode,
                                    onLoadHighRes = { onLoadHighRes(match) },
                                    onImageClick = { url ->
                                        selectedLightboxUrl = url
                                        selectedLightboxTitle = match.displayName
                                    },
                                    onClick = { onMatchClick(match) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }

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
                        }
                    }
                } else {
                    // FILTER SPECIFICALLY SELECTED ("Verified", "Socials", "Exact", "Adult")
                    if (filteredMatches.isNotEmpty()) {
                        filteredMatches.forEachIndexed { index, match ->
                            MatchCard(
                                match = match,
                                isPrimary = index == 0 && selectedFilter == "Verified",
                                debugMode = debugMode,
                                onLoadHighRes = { onLoadHighRes(match) },
                                onImageClick = { url ->
                                    selectedLightboxUrl = url
                                    selectedLightboxTitle = match.displayName
                                },
                                onClick = { onMatchClick(match) }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text("No matches found for filter: $selectedFilter", color = Color.Gray, fontWeight = FontWeight.Medium)
                        }
                    }
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

        ImageLightboxDialog(
            imageUrl = selectedLightboxUrl,
            title = selectedLightboxTitle,
            onDismiss = { selectedLightboxUrl = null }
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
                Text("Run TinEye Exact-Image Check", color = Color.White, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onConfirmFreeSearch,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                border = BorderStroke(1.dp, Color(0xFF4CAF50)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.Black)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Run Deep In-App Search", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
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
