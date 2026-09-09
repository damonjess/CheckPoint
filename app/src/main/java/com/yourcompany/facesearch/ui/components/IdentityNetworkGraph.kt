package com.yourcompany.facesearch.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.yourcompany.facesearch.R
import com.yourcompany.facesearch.ui.Amber
import com.yourcompany.facesearch.ui.models.WebMatchDisplay
import kotlin.math.cos
import kotlin.math.sin

enum class GraphFilter(val label: String) {
    ALL("All Nodes"),
    VERIFIED("Verified"),
    SOCIALS("Socials"),
    TINEYE("TinEye"),
    ADULT("Adult Platform")
}

data class NetworkNode(
    val id: String,
    val match: WebMatchDisplay,
    val category: NodeCategory,
    val xOffsetDp: Dp,
    val yOffsetDp: Dp,
    val nodeColor: Color,
    val connectionType: String
)

enum class NodeCategory {
    VERIFIED_FACE,
    LIKELY_FACE,
    VISUAL_LEAD,
    TINEYE_OCCURRENCE,
    ADULT_PLATFORM
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
    var activeFilter by remember { mutableStateOf(GraphFilter.ALL) }
    var selectedNode by remember { mutableStateOf<NetworkNode?>(null) }

    // Gesture States: Pan & Zoom
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Animation for pulsing central target glow
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    // Build the set of all nodes from all match buckets
    val allNodes = remember(verifiedMatches, likelyMatches, visualLeads, tinEyeMatches, adultMatches) {
        buildNetworkNodes(
            verifiedMatches = verifiedMatches,
            likelyMatches = likelyMatches,
            visualLeads = visualLeads,
            tinEyeMatches = tinEyeMatches,
            adultMatches = adultMatches
        )
    }

    // Filter nodes based on selected chip
    val filteredNodes = remember(allNodes, activeFilter) {
        when (activeFilter) {
            GraphFilter.ALL -> allNodes
            GraphFilter.VERIFIED -> allNodes.filter { 
                it.category == NodeCategory.VERIFIED_FACE || it.category == NodeCategory.LIKELY_FACE 
            }
            GraphFilter.SOCIALS -> allNodes.filter { it.match.isSocial }
            GraphFilter.TINEYE -> allNodes.filter { it.category == NodeCategory.TINEYE_OCCURRENCE }
            GraphFilter.ADULT -> allNodes.filter { it.category == NodeCategory.ADULT_PLATFORM }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(520.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0F172A)) // Sleek dark slate graph canvas background
    ) {
        // --- INTERACTIVE CANVAS AREA ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.6f, 2.5f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current

            // Canvas drawing connecting lines (edges) between center face and match nodes
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerPxX = size.width / 2f + offsetX
                val centerPxY = size.height / 2f + offsetY

                filteredNodes.forEach { node ->
                    val nodePxX = centerPxX + with(density) { node.xOffsetDp.toPx() } * scale
                    val nodePxY = centerPxY + with(density) { node.yOffsetDp.toPx() } * scale

                    val strokeWidth = when (node.category) {
                        NodeCategory.VERIFIED_FACE -> 3.5f * scale
                        NodeCategory.LIKELY_FACE -> 2.5f * scale
                        NodeCategory.ADULT_PLATFORM -> 2.5f * scale
                        else -> 1.5f * scale
                    }

                    val pathEffect = if (node.category == NodeCategory.VISUAL_LEAD || node.category == NodeCategory.TINEYE_OCCURRENCE) {
                        PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    } else null

                    drawLine(
                        color = node.nodeColor.copy(alpha = 0.65f),
                        start = Offset(centerPxX, centerPxY),
                        end = Offset(nodePxX, nodePxY),
                        strokeWidth = strokeWidth,
                        pathEffect = pathEffect
                    )
                }
            }

            // Render Nodes
            filteredNodes.forEach { node ->
                val xDp = node.xOffsetDp * scale + with(density) { offsetX.toDp() }
                val yDp = node.yOffsetDp * scale + with(density) { offsetY.toDp() }

                Box(
                    modifier = Modifier
                        .offset(x = xDp, y = yDp)
                        .clickable { selectedNode = node },
                    contentAlignment = Alignment.Center
                ) {
                    NodeBubble(
                        node = node,
                        isSelected = selectedNode?.id == node.id
                    )
                }
            }

            // --- CENTRAL TARGET FACE NODE ---
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { offsetX.toDp() },
                        y = with(density) { offsetY.toDp() }
                    )
                    .size((90 * scale).dp)
                    .shadow(12.dp * scale, CircleShape)
                    .background(Color(0xFF00E5FF).copy(alpha = 0.25f * pulseGlow), CircleShape)
                    .border(3.dp, Color(0xFF00E5FF), CircleShape)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (targetFaceBitmap != null) {
                    Image(
                        bitmap = targetFaceBitmap.asImageBitmap(),
                        contentDescription = "Target Face",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Target",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
        }

        // --- TOP FILTER BAR ---
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp)
                .fillMaxWidth(),
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

        // --- BOTTOM RIGHT CONTROLS (RESET PAN/ZOOM) ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    scale = 1f
                    offsetX = 0f
                    offsetY = 0f
                },
                containerColor = Color(0xFF1E293B),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = "Reset View")
            }
        }

        // --- SELECTED NODE DETAIL SHEET OVERLAY ---
        AnimatedVisibility(
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

@Composable
private fun NodeBubble(
    node: NetworkNode,
    isSelected: Boolean
) {
    val nodeSize = when (node.category) {
        NodeCategory.VERIFIED_FACE -> 54.dp
        NodeCategory.LIKELY_FACE -> 48.dp
        NodeCategory.ADULT_PLATFORM -> 48.dp
        else -> 42.dp
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(90.dp)
    ) {
        Box(
            modifier = Modifier
                .size(nodeSize)
                .shadow(if (isSelected) 10.dp else 4.dp, CircleShape)
                .background(Color(0xFF1E293B), CircleShape)
                .border(
                    width = if (isSelected) 3.dp else 2.dp,
                    color = if (isSelected) Color.White else node.nodeColor,
                    shape = CircleShape
                )
                .padding(3.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val imgUrl = node.match.imageUrl
            if (imgUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imgUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = node.match.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = node.match.displayName.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Label pill below bubble
        Surface(
            color = Color(0xFF0F172A).copy(alpha = 0.85f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, node.nodeColor.copy(alpha = 0.5f))
        ) {
            Text(
                text = node.match.displayName,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun NodeDetailCard(
    node: NetworkNode,
    onOpenProfile: () -> Unit,
    onDismiss: () -> Unit
) {
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
                            .background(node.nodeColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = node.connectionType,
                        color = node.nodeColor,
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
                        .border(2.dp, node.nodeColor, CircleShape)
                ) {
                    val imgUrl = node.match.imageUrl
                    if (imgUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(imgUrl).crossfade(true).build(),
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
                        Icons.Default.OpenInNew,
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
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Link", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private fun buildNetworkNodes(
    verifiedMatches: List<WebMatchDisplay>,
    likelyMatches: List<WebMatchDisplay>,
    visualLeads: List<WebMatchDisplay>,
    tinEyeMatches: List<WebMatchDisplay>,
    adultMatches: List<WebMatchDisplay>
): List<NetworkNode> {
    val nodes = mutableListOf<NetworkNode>()

    val allBuckets = listOf(
        verifiedMatches to NodeCategory.VERIFIED_FACE,
        likelyMatches to NodeCategory.LIKELY_FACE,
        adultMatches to NodeCategory.ADULT_PLATFORM,
        tinEyeMatches to NodeCategory.TINEYE_OCCURRENCE,
        visualLeads to NodeCategory.VISUAL_LEAD
    )

    // Flatten all matches with their assigned category into a single list
    val allPairs = allBuckets.flatMap { (matches, category) ->
        matches.map { match -> match to category }
    }

    if (allPairs.isEmpty()) return emptyList()

    // Orbital distribution rings
    val ringRadiiDp = listOf(110.dp, 160.dp, 210.dp)
    val ringCapacity = 8

    allPairs.forEachIndexed { totalIndex, (match, category) ->
        val ringIndex = (totalIndex / ringCapacity).coerceAtMost(ringRadiiDp.lastIndex)
        val radiusDp = ringRadiiDp[ringIndex]

        // Calculate total items assigned to this specific ring
        val startIndexForRing = ringIndex * ringCapacity
        val itemsInThisRing = if (ringIndex == ringRadiiDp.lastIndex) {
            allPairs.size - startIndexForRing
        } else {
            ringCapacity.coerceAtMost(allPairs.size - startIndexForRing)
        }.coerceAtLeast(1)

        val indexInRing = totalIndex % ringCapacity
        // Stagger outer rings slightly (+22.5 deg) to prevent overlap with inner ring nodes
        val ringAngleOffset = ringIndex * 22.5f
        val angleDeg = (indexInRing * (360f / itemsInThisRing) + ringAngleOffset) % 360f
        val rad = Math.toRadians(angleDeg.toDouble())

        val xOffset = (radiusDp.value * cos(rad)).dp
        val yOffset = (radiusDp.value * sin(rad)).dp

        val color = when (category) {
            NodeCategory.VERIFIED_FACE -> Color(0xFF00E5FF) // Electric Cyan
            NodeCategory.LIKELY_FACE -> Color(0xFFFFB74D) // Warm Amber
            NodeCategory.ADULT_PLATFORM -> Color(0xFFEF4444) // Vibrant Red
            NodeCategory.TINEYE_OCCURRENCE -> Color(0xFF3B82F6) // Bright Blue
            NodeCategory.VISUAL_LEAD -> Color(0xFF94A3B8) // Slate Gray
        }

        val connectionType = when (category) {
            NodeCategory.VERIFIED_FACE -> "Confirmed Facial Match (${(match.confidence * 100).toInt()}%)"
            NodeCategory.LIKELY_FACE -> "Possible Facial Match (${(match.confidence * 100).toInt()}%)"
            NodeCategory.ADULT_PLATFORM -> "Adult Network Hit"
            NodeCategory.TINEYE_OCCURRENCE -> "Exact Image Occurrence"
            NodeCategory.VISUAL_LEAD -> "Visual Lead Candidate"
        }

        nodes.add(
            NetworkNode(
                id = "${category.name}_${match.profileUrl}_${totalIndex}",
                match = match,
                category = category,
                xOffsetDp = xOffset,
                yOffsetDp = yOffset,
                nodeColor = color,
                connectionType = connectionType
            )
        )
    }

    return nodes
}
