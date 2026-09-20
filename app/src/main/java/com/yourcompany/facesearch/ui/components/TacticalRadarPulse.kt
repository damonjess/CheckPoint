package com.yourcompany.facesearch.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import com.yourcompany.facesearch.ui.CyberGreen

@Composable
fun TacticalRadarPulse(
    modifier: Modifier = Modifier,
    color: Color = CyberGreen
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ScaleAnimation"
    )

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "AlphaAnimation"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .background(color.copy(alpha = alpha), shape = CircleShape)
    )
}
