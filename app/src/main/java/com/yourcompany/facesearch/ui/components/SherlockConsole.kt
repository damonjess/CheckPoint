package com.yourcompany.facesearch.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.ScrollState
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
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black),
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
                logs.forEach { log ->
                    Text(
                        text = "> $log",
                        color = if (log.contains("SUCCESS", true) || log.contains("MATCH", true)) Color.Green 
                                else if (log.contains("ERROR", true) || log.contains("FAIL", true)) Color.Red 
                                else Amber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
                if (showCursor) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs", tint = Amber.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun BlinkingCursor() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Box(
        modifier = Modifier
            .size(width = 8.dp, height = 12.dp)
            .padding(top = 2.dp)
            .background(Amber.copy(alpha = alpha))
    )
}
