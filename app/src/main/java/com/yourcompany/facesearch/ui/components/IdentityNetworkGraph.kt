package com.yourcompany.facesearch.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.yourcompany.facesearch.ui.models.WebMatchDisplay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class GraphNode(
    val id: String,
    val match: WebMatchDisplay,
    val x: Float,
    val y: Float,
    val color: Color,
    val tierName: String,
)

enum class GraphFilter(val label: String) {
    ALL("All Nodes"),
    VERIFIED("Verified"),
    LIKELY("Likely"),
    TINEYE("TinEye"),
    ADULT("Adult")
}

@Composable
fun IdentityNetworkGraph(
    targetFaceBitmap: Bitmap?,
    verifiedMatches: List<WebMatchDisplay>,
    likelyMatches: List<WebMatchDisplay>,
    visualLeads: List<WebMatchDisplay>,
    tinEyeMatches: List<WebMatchDisplay>,
    adultMatches: List<WebMatchDisplay>,
    onMatchClick: (WebMatchDisplay) -> Unit,
    modifier: Modifier = Modifier
) {
    // Interactive Pan & Zoom State
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var activeFilter by remember { mutableStateOf(GraphFilter.ALL) }
    var selectedNode by remember { mutableStateOf<GraphNode?>(null) }

    // Pre-calculate radial positions for hardware-accelerated rendering
    val allNodes = remember(verifiedMatches, likelyMatches, visualLeads, tinEyeMatches, adultMatches) {
        val result = mutableListOf<GraphNode>()

        fun addTier(matches: List<WebMatchDisplay>, radius: Float, color: Color, tierName: String) {
            if (matches.isEmpty()) return
            val angleStep = (2 * PI) / matches.size
            matches.forEachIndexed { index, match ->
                val angle = index * angleStep
                val x = (radius * cos(angle)).toFloat()
                val y = (radius * sin(angle)).toFloat()
                val id = "${tierName}_${match.profileUrl}_$index"
                result.add(GraphNode(id, match, x, y, color, tierName))
            }
        }

        // Tiers expand outward based on confidence levels
        addTier(verifiedMatches, radius = 400f, color = Color(0xFF00FF66), tierName = "Verified Face") // CyberGreen
        addTier(likelyMatches, radius = 700f, color = Color(0xFFFFB000), tierName = "Likely Match")   // Amber
        addTier(tinEyeMatches, radius = 1000f, color = Color(0xFF4285F4), tierName = "TinEye Occurrence")  // Blue
        addTier(visualLeads, radius = 1300f, color = Color(0xFF94A3B8), tierName = "Visual Lead")    // Slate/Gray
        addTier(adultMatches, radius = 1600f, color = Color(0xFFE53935), tierName = "Adult Network Hit")   // Red

        result
    }

    val filteredNodes = remember(allNodes, activeFilter) {
        when (activeFilter) {
            GraphFilter.ALL -> allNodes
            GraphFilter.VERIFIED -> allNodes.filter { it.tierName == "Verified Face" }
            GraphFilter.LIKELY -> allNodes.filter { it.tierName == "Likely Match" }
            GraphFilter.TINEYE -> allNodes.filter { it.tierName == "TinEye Occurrence" }
            GraphFilter.ADULT -> allNodes.filter { it.tierName == "Adult Network Hit" }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F172A)) // Tactical Slate Background
    ) {
        // --- TOP FILTER BAR ---
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            GraphFilter.entries.forEach { filter ->
                val isSelected = filter == activeFilter
                FilterChip(
                    selected = isSelected,
                    onClick = { activeFilter = filter },
                    label = {
                        Text(
                            filter.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF1E293B),
                        labelColor = Color.LightGray,
                        selectedContainerColor = Color(0xFF00E5FF),
                        selectedLabelColor = Color.Black
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = Color(0xFF334155),
                        selectedBorderColor = Color(0xFF00E5FF)
                    )
                )
            }
        }

        // --- GRAPH CANVAS / DRAWING AREA ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.2f, 3f)
                            offset += pan
                        }
                    }
            ) {
                val density = LocalDensity.current
                val centerX = with(density) { maxWidth.toPx() / 2f }
                val centerY = with(density) { maxHeight.toPx() / 2f }

                // The transformable master container
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Phase 1: Draw edges first so they sit underneath nodes
                        val parentNode = filteredNodes.find { it.match.isFaceVerified }

                        filteredNodes.forEach { childNode ->
                            if (childNode.match.source.contains("Pivot Discovery", ignoreCase = true)) {
                                val targetParent = parentNode ?: filteredNodes.firstOrNull()
                                if (targetParent != null) {
                                    drawLine(
                                        color = Color(0xFF334155),
                                        start = Offset(centerX + targetParent.x, centerY + targetParent.y),
                                        end = Offset(centerX + childNode.x, centerY + childNode.y),
                                        strokeWidth = 2.dp.toPx()
                                    )
                                }
                            }
                        }

                        // Phase 2: Draw node circles after edges have been painted
                        filteredNodes.forEach { node ->
                            val nodeColor = when {
                                node.match.isFaceVerified -> Color(0xFF00FF66)
                                node.match.source.contains("Pivot Discovery", ignoreCase = true) -> Color(0xFF00E5FF)
                                node.match.isLikelyFaceMatch -> Color(0xFFFFB000)
                                else -> Color(0xFF334155)
                            }

                            drawCircle(
                                color = nodeColor,
                                radius = 24.dp.toPx(),
                                center = Offset(centerX + node.x, centerY + node.y)
                            )

                            drawCircle(
                                color = Color.Black,
                                radius = 24.dp.toPx(),
                                center = Offset(centerX + node.x, centerY + node.y),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                            )
                        }
                    }

                    // 3. Draw Target Node (Center)
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (centerX - 40.dp.toPx()).roundToInt(),
                                    (centerY - 40.dp.toPx()).roundToInt()
                                )
                            }
                            .size(80.dp)
                            .border(3.dp, Color.White, CircleShape)
                            .background(Color.DarkGray, CircleShape)
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (targetFaceBitmap != null) {
                            Image(
                                bitmap = targetFaceBitmap.asImageBitmap(),
                                contentDescription = "Target Subject",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(Icons.Default.Person, contentDescription = null, tint = Color.White)
                        }
                    }

                    // 3. Draw Web/OSINT Nodes (Satellites)
                    filteredNodes.forEach { node ->
                        val isSelected = selectedNode?.id == node.id
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        (centerX + node.x - 30.dp.toPx()).roundToInt(),
                                        (centerY + node.y - 30.dp.toPx()).roundToInt()
                                    )
                                }
                                .size(60.dp)
                                .clickable {
                                    if (selectedNode?.id == node.id) {
                                        onMatchClick(node.match)
                                    } else {
                                        selectedNode = node
                                    }
                                }
                                .border(if (isSelected) 4.dp else 2.dp, if (isSelected) Color.White else node.color, CircleShape)
                                .background(Color(0xFF1E293B), CircleShape)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (node.match.imageUrl != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(node.match.imageUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = node.match.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    text = node.match.source.take(1).uppercase(),
                                    color = node.color,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Node Label Overlay
                        Surface(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        (centerX + node.x - 45.dp.toPx()).roundToInt(),
                                        (centerY + node.y + 35.dp.toPx()).roundToInt()
                                    )
                                }
                                .width(90.dp),
                            color = Color.Black.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = node.match.displayName.ifBlank { node.match.source },
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // --- BOTTOM RIGHT CONTROLS (RESET PAN/ZOOM) ---
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Reset View")
                }
            }

            // --- SELECTED NODE DETAIL CARD OVERLAY ---
            this@Column.AnimatedVisibility(
                visible = selectedNode != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                selectedNode?.let { node ->
                    NodeDetailCard(
                        node = node,
                        onOpenProfile = { onMatchClick(node.match) },
                        onDismiss = { selectedNode = null }
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeDetailCard(
    node: GraphNode,
    onOpenProfile: () -> Unit,
    onDismiss: () -> Unit
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(node.color, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${node.tierName} (${(node.match.confidence * 100).toInt()}%)",
                        color = node.color,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .border(2.dp, node.color, CircleShape)
                ) {
                    if (node.match.imageUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(node.match.imageUrl).crossfade(true).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Gray),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = node.match.displayName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    node.match.username?.let { handle ->
                        Text(
                            text = "@$handle",
                            color = Color(0xFF00E5FF),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        text = node.match.source,
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenProfile,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open Profile", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(node.match.profileUrl))
                    },
                    modifier = Modifier.height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color.Gray)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Link", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
