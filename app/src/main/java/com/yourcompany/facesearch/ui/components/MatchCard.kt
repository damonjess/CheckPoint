package com.yourcompany.facesearch.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
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
import com.yourcompany.facesearch.ui.CyberGreen
import com.yourcompany.facesearch.ui.TacticalBorder
import com.yourcompany.facesearch.ui.TacticalSurface
import com.yourcompany.facesearch.ui.models.SourceStyle
import com.yourcompany.facesearch.ui.models.SourceStyles
import com.yourcompany.facesearch.ui.models.WebMatchDisplay

@Composable
fun MatchCard(
    match: WebMatchDisplay,
    isPrimary: Boolean = false,
    debugMode: Boolean = false,
    onLoadHighRes: () -> Unit,
    onImageClick: ((String) -> Unit)? = null,
    onClick: () -> Unit
) {
    val style = SourceStyles.getStyle("${match.source} ${match.profileUrl}")
    val handle = rememberHandle(match)
    
    if (match.isHighResLoading) {
        LaunchedEffect(match.profileUrl) {
            onLoadHighRes()
        }
    }
    
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
        remember { mutableStateOf(1f) }
    }

    val containerShape = if (isPrimary) RoundedCornerShape(24.dp) else RoundedCornerShape(16.dp)
    val cardBorder = when {
        match.isFaceVerified -> BorderStroke(2.dp, CyberGreen)
        isPrimary -> BorderStroke(1.dp, CyberGreen.copy(alpha = 0.5f))
        else -> BorderStroke(1.dp, TacticalBorder)
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = containerShape,
        border = cardBorder,
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPrimary) 6.dp else 2.dp)
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
                PrimaryMatchContent(match, style, handle, debugMode, pulseScale, onLoadHighRes, onImageClick)
            } else {
                SecondaryMatchContent(match, style, handle, debugMode, onLoadHighRes, onImageClick)
            }
        }
    }
}

@Composable
private fun PrimaryMatchContent(
    match: WebMatchDisplay,
    style: SourceStyle,
    handle: String,
    debugMode: Boolean,
    pulseScale: Float,
    onLoadHighRes: () -> Unit,
    onImageClick: ((String) -> Unit)? = null
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

            // Match Percentage & Badges
            Column(horizontalAlignment = Alignment.End) {
                val confidenceInt = (match.confidence * 100).toInt()
                
                if (match.isFaceVerified) {
                    Surface(
                        color = CyberGreen.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, CyberGreen),
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Verified, contentDescription = null, tint = CyberGreen, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("FACE VERIFIED", color = CyberGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

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
                        confidenceInt >= 90 || match.isFaceVerified -> CyberGreen
                        confidenceInt >= 70 -> CyberGreen.copy(alpha = 0.8f)
                        else -> Amber
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
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CyberGreen)
                        } else {
                            Icon(Icons.Default.Hd, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberGreen)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("HD Probe", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CyberGreen, fontFamily = FontFamily.Monospace)
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
                    elevation = (24 * pulseScale).dp, 
                    shape = CircleShape, 
                    ambientColor = (if (match.isFaceVerified) CyberGreen else style.color).copy(alpha = 0.8f), 
                    spotColor = (if (match.isFaceVerified) CyberGreen else style.color).copy(alpha = 0.8f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(2.dp),
                shape = CircleShape,
                border = BorderStroke(4.dp, Brush.linearGradient(listOf(CyberGreen, Amber))),
                color = TacticalSurface
            ) {
                val imageModel = remember(match.imageUrl) { parseImageModel(match.imageUrl) }
                if (imageModel != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageModel)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (onImageClick != null && match.imageUrl != null) {
                                    Modifier.clickable { onImageClick(match.imageUrl.toString()) }
                                } else Modifier
                            ),
                        placeholder = ColorPainter(TacticalBorder),
                        error = rememberVectorPainter(style.icon)
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(TacticalBorder),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = style.icon,
                            contentDescription = null, 
                            tint = style.color.copy(alpha = 0.5f), 
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Name and Monospace Handle
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = match.displayName,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                color = TacticalBorder.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = handle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyberGreen,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(12.dp), tint = CyberGreen)
                }
            }
        }
        
        if (debugMode) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "BIO-SCORE: ${match.score}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = CyberGreen,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SecondaryMatchContent(
    match: WebMatchDisplay,
    style: SourceStyle,
    handle: String,
    debugMode: Boolean,
    onLoadHighRes: () -> Unit,
    onImageClick: ((String) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Social Icon
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = TacticalSurface,
            border = BorderStroke(1.dp, TacticalBorder),
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

        Spacer(modifier = Modifier.width(20.dp))

        // Image with Badge
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(TacticalBorder)
            ) {
                val imageModel = remember(match.imageUrl) { parseImageModel(match.imageUrl) }
                if (imageModel != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageModel)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (onImageClick != null && match.imageUrl != null) {
                                    Modifier.clickable { onImageClick(match.imageUrl.toString()) }
                                } else Modifier
                            ),
                        placeholder = ColorPainter(TacticalBorder),
                        error = rememberVectorPainter(style.icon)
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(TacticalBorder),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = style.icon, 
                            contentDescription = null, 
                            tint = style.color.copy(alpha = 0.3f), 
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                
                Surface(
                    color = when {
                        match.isFaceVerified -> CyberGreen
                        (match.confidence * 100).toInt() >= 90 -> CyberGreen
                        (match.confidence * 100).toInt() >= 70 -> CyberGreen.copy(alpha = 0.8f)
                        else -> Amber
                    },
                    shape = RoundedCornerShape(bottomStart = 8.dp),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        text = when {
                            match.isFaceVerified -> "VERIFIED"
                            (match.confidence * 100).toInt() >= 90 -> "CERTAIN"
                            (match.confidence * 100).toInt() >= 70 -> "CONFIDENT"
                            (match.confidence * 100).toInt() >= 50 -> "WEAK"
                            match.isLikelyFaceMatch -> "POSSIBLE"
                            else -> "LEAD"
                        },
                        color = TacticalSurface,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (match.isHighResLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = CyberGreen, strokeWidth = 2.dp)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = match.displayName,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = handle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = CyberGreen,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    Icons.AutoMirrored.Filled.Launch,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = CyberGreen
                )
                
                if (match.imageUrl.toString().contains("yandex") || match.imageUrl.toString().contains("bing") || match.imageUrl.toString().contains("baidu")) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onLoadHighRes, modifier = Modifier.size(24.dp), enabled = !match.isHighResLoading) {
                        Icon(Icons.Default.Hd, contentDescription = "HD", tint = CyberGreen, modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (match.duplicateCount > 1) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${match.duplicateCount} duplicate leads merged",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.LightGray
                )
            }

            if (!match.isFaceVerified) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (match.source.contains("TinEye", ignoreCase = true) || match.source.contains("Adult", ignoreCase = true)) {
                        "Exact image occurrence — bypassing local face verification"
                    } else if (match.isLikelyFaceMatch) {
                        "Possible face match — review manually"
                    } else if (match.confidence >= 0.45f) {
                        "Review lead — ${(match.confidence * 100).toInt()}% similarity"
                    } else {
                        "Visual-search lead — unverified"
                    },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Amber
                )
            }

            if (debugMode) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "ID: ${match.score}",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = CyberGreen.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun rememberHandle(match: WebMatchDisplay): String {
    return remember(match.username, match.displayName, match.profileUrl, match.isFaceVerified, match.isLikelyFaceMatch, match.source) {
        when {
            !match.username.isNullOrBlank() -> "@${match.username}"
            match.isFaceVerified -> "${match.source.ifBlank { "Verified" }}"
            match.isLikelyFaceMatch -> "${match.source.ifBlank { "Possible" }}"
            else -> "${match.source.ifBlank { "Lead" }}"
        }
    }
}

private fun parseImageModel(rawModel: Any?): Any? {
    if (rawModel == null) return null
    if (rawModel is String) {
        val trimmed = rawModel.trim()
        if (trimmed.startsWith("data:image", ignoreCase = true)) {
            return try {
                val base64Data = trimmed.substringAfter("base64,")
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            }
        }
    }
    return rawModel
}
