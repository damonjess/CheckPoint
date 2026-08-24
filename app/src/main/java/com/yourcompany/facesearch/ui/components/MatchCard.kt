package com.yourcompany.facesearch.ui.components

import androidx.compose.animation.core.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.yourcompany.facesearch.ui.Amber
import com.yourcompany.facesearch.ui.models.SourceStyles
import com.yourcompany.facesearch.ui.models.WebMatchDisplay

@Composable
fun MatchCard(
    match: WebMatchDisplay,
    isPrimary: Boolean = false,
    debugMode: Boolean = false,
    onLoadHighRes: () -> Unit,
    onClick: () -> Unit
) {
    val style = SourceStyles.getStyle(match.source)
    val handle = rememberHandle(match)
    
    // Auto-fetch high-res for suspicious social thumbnails
    if (match.isHighResLoading) {
        LaunchedEffect(match.profileUrl) {
            onLoadHighRes()
        }
    }
    
    // Breathing/Pulse animation for top match
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by if (isPrimary) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )
    } else {
        androidx.compose.runtime.remember { mutableStateOf(1f) }
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPrimary) 4.dp else 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Left Vertical Gradient Bar
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(6.dp)
                    .background(style.gradient)
            )

            if (isPrimary) {
                PrimaryMatchContent(match, style, handle, debugMode, pulseScale, onLoadHighRes)
            } else {
                SecondaryMatchContent(match, style, handle, debugMode, onLoadHighRes)
            }
        }
    }
}

@Composable
private fun PrimaryMatchContent(
    match: WebMatchDisplay,
    style: com.yourcompany.facesearch.ui.models.SourceStyle,
    handle: String,
    debugMode: Boolean,
    pulseScale: Float,
    onLoadHighRes: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Social Logo
            Surface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = style.color,
                shadowElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = style.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Match Percentage
            Column(horizontalAlignment = Alignment.End) {
                val confidenceInt = (match.confidence * 100).toInt()
                Text(
                    text = when {
                        confidenceInt >= 90 -> "Highly certain match"
                        confidenceInt >= 70 -> "Confident match"
                        confidenceInt >= 50 -> "Weak match"
                        match.isFaceVerified -> "$confidenceInt% match"
                        match.isLikelyFaceMatch -> "Possible match"
                        else -> "Visual lead"
                    },
                    color = when {
                        confidenceInt >= 90 -> Color(0xFF2E7D32) // Green
                        confidenceInt >= 70 -> Color(0xFF388E3C) // Lighter Green
                        confidenceInt >= 50 -> Color(0xFFF57F17) // Amber
                        else -> Color(0xFFF57F17)
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp
                )
                
                if (match.imageUrl.toString().contains("yandex") || match.imageUrl.toString().contains("bing") || match.imageUrl.toString().contains("baidu")) {
                    TextButton(
                        onClick = onLoadHighRes,
                        enabled = !match.isHighResLoading,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        if (match.isHighResLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Amber)
                        } else {
                            Icon(Icons.Default.Hd, contentDescription = null, modifier = Modifier.size(16.dp), tint = Amber)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("HD Probe", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Amber)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Large Circle Profile with Glow and Pulse
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(pulseScale)
                .shadow(
                    elevation = (20 * pulseScale).dp, 
                    shape = CircleShape, 
                    ambientColor = style.color, 
                    spotColor = style.color
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(4.dp),
                shape = CircleShape,
                border = BorderStroke(3.dp, Color(0xFF4CAF50)),
                color = Color.LightGray
            ) {
                if (match.imageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(match.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,  // WAS Crop
                        alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFFF5F5F5)),
                        error = androidx.compose.ui.graphics.vector.rememberVectorPainter(style.icon)
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = style.icon,
                            contentDescription = null, 
                            tint = style.color.copy(alpha = 0.3f), 
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Handle
        Surface(
            color = Color(0xFFF5F5F5),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .padding(bottom = 8.dp)
                .fillMaxWidth(0.85f)   // don't stretch full width
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = handle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.DarkGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis   // truncates with ... instead of overflow
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.Gray)
            }
        }
        
        if (debugMode) {
            Text(
                text = "BIO-SCORE: ${match.score}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Amber,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SecondaryMatchContent(
    match: WebMatchDisplay,
    style: com.yourcompany.facesearch.ui.models.SourceStyle,
    handle: String,
    debugMode: Boolean,
    onLoadHighRes: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Social Icon
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = Color.White,
            border = BorderStroke(1.dp, Color.LightGray),
            shadowElevation = 2.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(24.dp))

        // Image with Badge
        Column {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.LightGray)
            ) {
                if (match.imageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(match.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,  // WAS Crop
                        alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFFF5F5F5)),
                        error = androidx.compose.ui.graphics.vector.rememberVectorPainter(style.icon)
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = style.icon, 
                            contentDescription = null, 
                            tint = style.color.copy(alpha = 0.2f), 
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                
                Surface(
                    color = when {
                        (match.confidence * 100).toInt() >= 90 -> Color(0xFF4CAF50)
                        (match.confidence * 100).toInt() >= 70 -> Color(0xFF8BC34A)
                        else -> Amber
                    },
                    shape = RoundedCornerShape(bottomStart = 8.dp),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        text = when {
                            (match.confidence * 100).toInt() >= 90 -> "CERTAIN"
                            (match.confidence * 100).toInt() >= 70 -> "CONFIDENT"
                            (match.confidence * 100).toInt() >= 50 -> "WEAK"
                            match.isFaceVerified -> "${(match.confidence * 100).toInt()}%"
                            match.isLikelyFaceMatch -> "POSSIBLE"
                            else -> "LEAD"
                        },
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (match.isHighResLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = handle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.DarkGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    Icons.AutoMirrored.Filled.Launch,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = Color.Gray
                )
                
                if (match.imageUrl.toString().contains("yandex") || match.imageUrl.toString().contains("bing") || match.imageUrl.toString().contains("baidu")) {
                    Spacer(modifier = Modifier.weight(0.1f))
                    IconButton(onClick = onLoadHighRes, modifier = Modifier.size(24.dp), enabled = !match.isHighResLoading) {
                        Icon(Icons.Default.Hd, contentDescription = "HD", tint = Amber, modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (match.duplicateCount > 1) {
                Text(
                    text = "${match.duplicateCount} duplicate leads merged",
                    fontSize = 10.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.Medium
                )
            }

            if (!match.isFaceVerified) {
                Text(
                    text = if (match.isLikelyFaceMatch) {
                        "Possible face match — review manually; it did not meet the confirmation threshold"
                    } else if (match.confidence >= 0.45f) {
                        "Review lead — ${(match.confidence * 100).toInt()}% local similarity; not identity verified"
                    } else if (match.confidence > 0f) {
                        "Ranked visual candidate — ${(match.confidence * 100).toInt()}% local similarity; not identity verified"
                    } else {
                        "Visual-search lead — not locally face verified"
                    },
                    fontSize = 10.sp,
                    color = Amber,
                    fontWeight = FontWeight.Medium
                )
            }

            if (debugMode) {
                Text(
                    text = "ID: ${match.score}",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Amber.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun rememberHandle(match: WebMatchDisplay): String {
    return remember(match.username, match.displayName, match.profileUrl) {
        // 1. Real username from URL path (best)
        match.username?.let { "@$it" }
        // 2. First 2 words of cleaned title
            ?: match.displayName.split(" ").take(2).joinToString(" ").let { name ->
                val compact = name.lowercase().replace(" ", "")
                if (compact.length > 3 && !compact.contains("match")) "@$compact" else null
            }
        // 3. Do not fabricate an account label from a result score.
            ?: "Unnamed visual lead"
    }
}
