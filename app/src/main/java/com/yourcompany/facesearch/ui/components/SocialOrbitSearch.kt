package com.yourcompany.facesearch.ui.components

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourcompany.facesearch.R
import kotlin.math.cos
import kotlin.math.sin

data class OrbitIcon(
    @DrawableRes val icon: Int,
    val color: Color,
    val label: String
)

@Composable
fun SocialOrbitSearchScreen(
    faceBitmap: Bitmap?,
    modifier: Modifier = Modifier
) {
    // Replace these with your actual brand drawables (R.drawable.ic_tiktok, etc.)
    val icons = remember {
        listOf(
            OrbitIcon(R.drawable.ic_tiktok,    Color(0xFF000000), "TikTok"),
            OrbitIcon(R.drawable.ic_reddit,    Color(0xFFFF4500), "Reddit"),
            OrbitIcon(R.drawable.ic_linkedin,  Color(0xFF0077B5), "LinkedIn"),
            OrbitIcon(R.drawable.ic_youtube,   Color(0xFFFF0000), "YouTube"),
            OrbitIcon(R.drawable.ic_x_logo,    Color(0xFF000000), "X"),
            OrbitIcon(R.drawable.ic_instagram, Color(0xFFE1306C), "Instagram"),
            OrbitIcon(R.drawable.ic_facebook,  Color(0xFF1877F2), "Facebook"),
            OrbitIcon(R.drawable.ic_snapchat,  Color(0xFFFFFC00), "Snapchat")
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "orbit")

    // Full orbit rotation (12 seconds per loop)
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbitAngle"
    )

    // Scanning line bounces up/down
    val scanLineY by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanLine"
    )

    val density = LocalDensity.current
    val orbitRadiusPx = with(density) { 150.dp.toPx() }
    val circleSize = 200.dp
    val circlePx = with(density) { circleSize.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFBFBFB)),
        contentAlignment = Alignment.Center
    ) {
        // ---- ORBITING ICONS ----
        icons.forEachIndexed { index, item ->
            val itemAngle = orbitAngle + (index * 45f)
            val rad = Math.toRadians(itemAngle.toDouble())
            val xOffset = (orbitRadiusPx * cos(rad)).toFloat()
            val yOffset = (orbitRadiusPx * sin(rad)).toFloat()
            val xDp = with(density) { xOffset.toDp() }
            val yDp = with(density) { yOffset.toDp() }

            Box(
                modifier = Modifier
                    .offset(x = xDp, y = yDp)
                    .size(48.dp)
                    .shadow(8.dp, CircleShape)
                    .background(item.color, CircleShape)
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = item.icon),
                    contentDescription = item.label,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // ---- CENTER FACE + SCANNER ----
        Box(
            modifier = Modifier
                .size(circleSize)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Face image
            if (faceBitmap != null) {
                Image(
                    bitmap = faceBitmap.asImageBitmap(),
                    contentDescription = "Face",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.LightGray),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = Color.White
                    )
                }
            }

            // Cyan scanning line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .offset(
                        y = with(density) { (scanLineY * circlePx / 2).toDp() }
                    )
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFF00E5FF),
                                Color(0xFF00E5FF),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}



