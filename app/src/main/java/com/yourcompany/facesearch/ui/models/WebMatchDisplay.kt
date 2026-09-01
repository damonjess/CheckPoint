package com.yourcompany.facesearch.ui.models

import java.util.Locale

data class WebMatchDisplay(
    val name: String,
    val source: String,
    val profileUrl: String,
    val score: Int,
    val imageUrl: Any? = null,
    val extraImages: List<String> = emptyList(),
    val isSocial: Boolean = false,
    val confidence: Float = 0f,
    val isFaceVerified: Boolean = false,
    val isLikelyFaceMatch: Boolean = false,
    val duplicateCount: Int = 1,
    val isHighResLoading: Boolean = false,
    val faceEmbedding: FloatArray? = null
) {
    val displayName: String by lazy {
        var n = name
            .replace(Regex("^\\d+\\s*[×xX]\\s*\\d+[A-Za-zА-Яа-я]?\\s*"), "")
            .replace(Regex("\\.(jpg|jpeg|png|gif|webp|svg)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\s*-\\s*"), "")
            .replace(Regex("#\\w+"), "")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[|\\\\/]"), " ")
            .trim()
            .take(120)

        if (n.contains(Regex("^[a-zA-Z0-9-]+\\.[a-z]{2,}$"))) {
            n = n.substringBefore(".").replaceFirstChar { 
                if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() 
            }
        }
        n.ifBlank { "Visual Match" }
    }

    /** Extracts a clean username from known social URL patterns */
    val username: String? by lazy {
        extractUsernameFromUrl(profileUrl)
    }

    companion object {
        fun extractUsernameFromUrl(url: String): String? {
            if (url.isBlank()) return null
            val clean = url.substringBefore("?").substringBefore("#").trimEnd('/')
            val lower = clean.lowercase()

            return when {
                lower.contains("instagram.com/p/") -> null // post, not profile
                lower.contains("instagram.com/") -> clean.substringAfterLast("/").takeIf { it.isNotBlank() && it.length < 30 && it != "instagram.com" }
                lower.contains("facebook.com/") -> {
                    val after = clean.substringAfter("facebook.com/").trimEnd('/')
                    // skip pages/groups/events
                    if (after.startsWith("pages/") || after.startsWith("groups/") || after.startsWith("events/")) null
                    else after.substringBefore("/").takeIf { it.isNotBlank() && it.length < 50 }
                }
                lower.contains("linkedin.com/in/") -> clean.substringAfter("/in/").substringBefore("/").takeIf { it.isNotBlank() }
                lower.contains("twitter.com/") || lower.contains("x.com/") -> clean.substringAfterLast("/").takeIf { it.isNotBlank() && it.length < 20 }
                lower.contains("tiktok.com/@") -> clean.substringAfter("@").substringBefore("/").takeIf { it.isNotBlank() }
                lower.contains("reddit.com/user/") || lower.contains("reddit.com/u/") -> clean.substringAfter("/user/").substringBefore("/").takeIf { it.isNotBlank() }
                lower.contains("youtube.com/@") || lower.contains("youtube.com/c/") || lower.contains("youtube.com/channel/") -> {
                    clean.substringAfterLast("/").takeIf { it.isNotBlank() && it.length < 40 }
                }
                // Adult / Creator Networks
                lower.contains("onlyfans.com/") -> clean.substringAfter("onlyfans.com/").substringBefore("/").takeIf { it.isNotBlank() }
                lower.contains("fansly.com/") -> clean.substringAfter("fansly.com/").substringBefore("/").takeIf { it.isNotBlank() }
                lower.contains("pornhub.com/model/") -> clean.substringAfter("/model/").substringBefore("/").takeIf { it.isNotBlank() }
                lower.contains("pornhub.com/users/") -> clean.substringAfter("/users/").substringBefore("/").takeIf { it.isNotBlank() }
                lower.contains("xvideos.com/profiles/") -> clean.substringAfter("/profiles/").substringBefore("/").takeIf { it.isNotBlank() }
                lower.contains("xvideos.com/model/") -> clean.substringAfter("/model/").substringBefore("/").takeIf { it.isNotBlank() }
                lower.contains("xnxx.com/profiles/") -> clean.substringAfter("/profiles/").substringBefore("/").takeIf { it.isNotBlank() }
                lower.contains("xnxx.com/model/") -> clean.substringAfter("/model/").substringBefore("/").takeIf { it.isNotBlank() }
                lower.contains("xhamster.com/users/") -> clean.substringAfter("/users/").substringBefore("/").takeIf { it.isNotBlank() }
                lower.contains("xhamster.com/models/") -> clean.substringAfter("/models/").substringBefore("/").takeIf { it.isNotBlank() }
                lower.contains("chaturbate.com/") -> {
                    val after = clean.substringAfter("chaturbate.com/").trimEnd('/')
                    if (after.contains("/") || after.isEmpty()) null else after
                }
                else -> null
            }
        }
    }
}
