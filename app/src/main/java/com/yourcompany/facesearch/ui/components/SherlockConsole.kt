package com.yourcompany.facesearch.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourcompany.facesearch.ui.Amber

@Composable
fun SherlockConsole(
    logs: List<String>,
    modifier: Modifier = Modifier,
    showCursor: Boolean = false,
    scrollState: ScrollState = rememberScrollState()
) {
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D11)),
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(12.dp)
            ) {
                logs.forEach { rawLog ->
                    val textColor = getLogColor(rawLog)
                    val formattedText = formatLogPrefix(rawLog)
                    Text(
                        text = formattedText,
                        color = textColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                if (showCursor) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = "> ",
                            color = Amber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        BlinkingCursor()
                    }
                }
            }
            
            IconButton(
                onClick = { clipboardManager.setText(AnnotatedString(logs.joinToString("\n"))) },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy logs",
                    tint = Amber.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun formatLogPrefix(log: String): String {
    val trimmed = log.trim()
    return when {
        trimmed.startsWith("✓") ||
        trimmed.startsWith("⚠") ||
        trimmed.startsWith("✗") ||
        trimmed.startsWith("ℹ") ||
        trimmed.startsWith(">") -> trimmed
        trimmed.startsWith("STEP") -> "> $trimmed"
        else -> "> $trimmed"
    }
}

private fun getLogColor(log: String): Color {
    val trimmed = log.trim()
    return when {
        // Errors / Failures
        trimmed.startsWith("✗") || 
        trimmed.contains("ERROR", true) || 
        trimmed.contains("FAILED", true) -> Color(0xFFEF5350) // Soft Red

        // Warnings / Tips
        trimmed.startsWith("⚠") || 
        trimmed.contains("Tip:", true) -> Color(0xFFFFB74D) // Orange/Amber Warning

        // Success / Matches found
        trimmed.startsWith("✓") || 
        trimmed.contains("SUCCESS", true) || 
        (trimmed.contains("found", true) && !trimmed.contains("0 candidate", true) && !trimmed.contains("0 candidate(s)", true)) ||
        trimmed.contains("verified match", true) -> Color(0xFF4CAF50) // Emerald Green

        // Info / 0 Candidates / Neutral Notices
        trimmed.startsWith("ℹ") || 
        trimmed.contains("0 candidates found", true) || 
        trimmed.contains("0 candidate(s)", true) -> Color(0xFF81D4FA) // Light Blue/Cyan

        // Step headers
        trimmed.startsWith("STEP", true) -> Color(0xFFFFD54F) // Bright Yellow

        // Default terminal amber
        else -> Amber
    }
}

@Composable
private fun BlinkingCursor() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Box(
        modifier = Modifier
            .size(width = 7.dp, height = 12.dp)
            .background(Amber.copy(alpha = alpha))
    )
}



