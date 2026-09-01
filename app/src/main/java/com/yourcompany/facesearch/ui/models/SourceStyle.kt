package com.yourcompany.facesearch.ui.models

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
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
            // Core Socials
            s.contains("facebook") -> SourceStyle(
                Color(0xFF1877F2), Icons.Default.People, Brush.verticalGradient(listOf(Color(0xFF00C6FF), Color(0xFF0072FF)))
            )
            s.contains("instagram") -> SourceStyle(
                Color(0xFFE4405F), Icons.Default.CameraAlt, Brush.verticalGradient(listOf(Color(0xFF833AB4), Color(0xFFFD1D1D), Color(0xFFFCB045)))
            )
            s.contains("tiktok") -> SourceStyle(
                Color(0xFF000000), Icons.Default.MusicNote, Brush.verticalGradient(listOf(Color(0xFF00F2EA), Color(0xFFFF0050)))
            )
            s.contains("twitter") || s.contains("x.com") || s.contains(" x ") -> SourceStyle(
                Color(0xFF000000), Icons.Default.People, Brush.verticalGradient(listOf(Color(0xFF000000), Color(0xFF434343)))
            )
            s.contains("reddit") -> SourceStyle(
                Color(0xFFFF4500), Icons.Default.Forum, Brush.verticalGradient(listOf(Color(0xFFFF5722), Color(0xFFFF4500)))
            )
            s.contains("youtube") -> SourceStyle(
                Color(0xFFFF0000), Icons.Default.PlayArrow, Brush.verticalGradient(listOf(Color(0xFFFF5252), Color(0xFFFF0000)))
            )
            s.contains("linkedin") -> SourceStyle(
                Color(0xFF0A66C2), Icons.Default.Work, Brush.verticalGradient(listOf(Color(0xFF0077B5), Color(0xFF0A66C2)))
            )
            
            // OSINT Expansion Pack Styles
            s.contains("linktree") || s.contains("linktr.ee") -> SourceStyle(
                Color(0xFF43E660), Icons.Default.Link, Brush.verticalGradient(listOf(Color(0xFF43E660), Color(0xFF138A36)))
            )
            s.contains("twitch") -> SourceStyle(
                Color(0xFF9146FF), Icons.Default.Videocam, Brush.verticalGradient(listOf(Color(0xFFA970FF), Color(0xFF9146FF)))
            )
            s.contains("patreon") -> SourceStyle(
                Color(0xFFFF424D), Icons.Default.Favorite, Brush.verticalGradient(listOf(Color(0xFFFF7078), Color(0xFFFF424D)))
            )
            s.contains("bsky") || s.contains("bluesky") -> SourceStyle(
                Color(0xFF0085FF), Icons.Default.Cloud, Brush.verticalGradient(listOf(Color(0xFF33A1FF), Color(0xFF0085FF)))
            )
            s.contains("mastodon") -> SourceStyle(
                Color(0xFF6364FF), Icons.AutoMirrored.Filled.Chat, Brush.verticalGradient(listOf(Color(0xFF8586FF), Color(0xFF6364FF)))
            )
            s.contains("behance") -> SourceStyle(
                Color(0xFF0057FF), Icons.Default.Brush, Brush.verticalGradient(listOf(Color(0xFF3379FF), Color(0xFF0057FF)))
            )
            s.contains("github") -> SourceStyle(
                Color(0xFF24292E), Icons.Default.Code, Brush.verticalGradient(listOf(Color.Gray, Color.DarkGray))
            )
            s.contains("vsco") -> SourceStyle(
                Color(0xFF000000), Icons.Default.Camera, Brush.verticalGradient(listOf(Color.DarkGray, Color.Black))
            )

            // Adult / Creator Network Styles
            s.contains("onlyfans") -> SourceStyle(
                Color(0xFF00AFF0), Icons.Default.Lock, Brush.verticalGradient(listOf(Color(0xFF00AFF0), Color(0xFF008CC0)))
            )
            s.contains("fansly") -> SourceStyle(
                Color(0xFF00A2F8), Icons.Default.Star, Brush.verticalGradient(listOf(Color(0xFF00A2F8), Color(0xFF0080C0)))
            )
            s.contains("pornhub") -> SourceStyle(
                Color(0xFFFFA300), Icons.Default.LocalMovies, Brush.verticalGradient(listOf(Color(0xFFFFA300), Color(0xFFFF7B00)))
            )
            s.contains("xvideos") -> SourceStyle(
                Color(0xFFE52D27), Icons.Default.PlayCircleFilled, Brush.verticalGradient(listOf(Color(0xFFE52D27), Color(0xFFB71C1C)))
            )
            s.contains("xnxx") -> SourceStyle(
                Color(0xFF005590), Icons.Default.OndemandVideo, Brush.verticalGradient(listOf(Color(0xFF005590), Color(0xFF003C66)))
            )
            s.contains("xhamster") -> SourceStyle(
                Color(0xFFE22D3E), Icons.Default.Pets, Brush.verticalGradient(listOf(Color(0xFFE22D3E), Color(0xFFC62828)))
            )
            s.contains("chaturbate") -> SourceStyle(
                Color(0xFFF6921E), Icons.Default.Videocam, Brush.verticalGradient(listOf(Color(0xFFF6921E), Color(0xFFE65100)))
            )
            s.contains("redtube") -> SourceStyle(
                Color(0xFFC00000), Icons.Default.Movie, Brush.verticalGradient(listOf(Color(0xFFC00000), Color(0xFF8B0000)))
            )
            s.contains("spankbang") || s.contains("eporner") || s.contains("beeg") || 
            s.contains("tnaflix") || s.contains("thumbzilla") || s.contains("tube8") || 
            s.contains("txxx") || s.contains("motherless") || s.contains("hqporner") || 
            s.contains("porntrex") || s.contains("cumlouder") || s.contains("youporn") -> SourceStyle(
                Color(0xFF212121), Icons.Default.VideoLibrary, Brush.verticalGradient(listOf(Color(0xFF424242), Color(0xFF000000)))
            )
            
            // Default Fallback
            else -> {
                val baseColor = Color(0xFF6750A4)
                SourceStyle(
                    baseColor, Icons.Default.Person, Brush.verticalGradient(listOf(baseColor.copy(alpha = 0.7f), baseColor))
                )
            }
        }
    }
}
