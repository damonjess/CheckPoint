package com.yourcompany.facesearch.ui.models

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class SourceStyle(
    val color: Color,
    val icon: ImageVector,
    val gradient: Brush
)

object SourceStyles {
    fun getStyle(source: String): SourceStyle {
        val s = source.lowercase()
        return when {
            s.contains("facebook") -> SourceStyle(
                Color(0xFF1877F2), 
                Icons.Default.People,
                Brush.verticalGradient(listOf(Color(0xFF00C6FF), Color(0xFF0072FF)))
            )
            s.contains("instagram") -> SourceStyle(
                Color(0xFFE4405F), 
                Icons.Default.CameraAlt,
                Brush.verticalGradient(listOf(Color(0xFF833AB4), Color(0xFFFD1D1D), Color(0xFFFCB045)))
            )
            s.contains("tiktok") -> SourceStyle(
                Color(0xFF000000), 
                Icons.Default.MusicNote,
                Brush.verticalGradient(listOf(Color(0xFF00F2EA), Color(0xFFFF0050)))
            )
            s.contains("onlyfans") || s.contains("fansly") -> SourceStyle(
                Color(0xFF00AFF0), 
                Icons.Default.Public,
                Brush.verticalGradient(listOf(Color(0xFF00C6FF), Color(0xFF0072FF)))
            )
            s.contains("pornhub") || s.contains("xvideos") || s.contains("xhamster") ||
            s.contains("redtube") || s.contains("youporn") || s.contains("spankbang") ||
            s.contains("eporner") || s.contains("chaturbate") -> SourceStyle(
                Color(0xFFFF5722),
                Icons.Default.PlayArrow,
                Brush.verticalGradient(listOf(Color(0xFFFF7043), Color(0xFFD84315)))
            )
            s.contains("twitter") || s.contains(" x ") -> SourceStyle(
                Color(0xFF000000), 
                Icons.Default.People,
                Brush.verticalGradient(listOf(Color(0xFF000000), Color(0xFF434343)))
            )
            s.contains("telegram") -> SourceStyle(
                Color(0xFF26A5E4), 
                Icons.Default.Send,
                Brush.verticalGradient(listOf(Color(0xFF37AEE2), Color(0xFF1E96C8)))
            )
            else -> {
                val baseColor = Color(0xFF6750A4)
                SourceStyle(
                    baseColor, 
                    Icons.Default.Person,
                    Brush.verticalGradient(listOf(baseColor.copy(alpha = 0.7f), baseColor))
                )
            }
        }
    }
}
