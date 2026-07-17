package com.yourcompany.facesearch.network

object SocialMediaDetector {

    data class PlatformScore(
        val name: String,
        val baseScore: Int,
        val isProfileBased: Boolean
    )

    fun detectPlatform(link: String?): PlatformScore {
        if (link.isNullOrBlank()) return PlatformScore("Web", 0, false)

        val lower = link.lowercase()

        return when {
            lower.contains("instagram.com") || lower.contains("instagr.am") -> PlatformScore("Instagram", 2500, true)
            lower.contains("facebook.com") || lower.contains("fb.com") -> PlatformScore("Facebook", 2200, true)
            lower.contains("linkedin.com") -> PlatformScore("LinkedIn", 2100, true)
            lower.contains("tiktok.com") -> PlatformScore("TikTok", 2000, true)
            lower.contains("twitter.com") || lower.contains("x.com") -> PlatformScore("Twitter", 1900, true)
            lower.contains("youtube.com") -> PlatformScore("YouTube", 1400, false)
            lower.contains("snapchat.com") || lower.contains("snap") -> PlatformScore("Snapchat", 1300, true)
            lower.contains("reddit.com") -> PlatformScore("Reddit", 800, false)
            lower.contains("pinterest.com") -> PlatformScore("Pinterest", 700, false)
            else -> PlatformScore("Web", 500, false)
        }
    }

    fun isProfileUrl(link: String?): Boolean {
        if (link.isNullOrBlank()) return false
        val lower = link.lowercase()
        return lower.contains("/profile") || 
               lower.contains("/user/") || 
               lower.contains("/@") ||
               lower.contains("/in/") || 
               lower.contains("/people/") ||
               lower.contains("about") ||
               lower.contains("bio")
    }

    fun extractUsername(link: String?, platform: PlatformScore): String? {
        if (link.isNullOrBlank()) return null
        val lower = link.lowercase()

        return when {
            lower.contains("instagram.com") -> extractFromPath(link, "instagram.com")
            lower.contains("facebook.com") -> extractFromPath(link, "facebook.com")
            lower.contains("linkedin.com") -> extractFromPath(link, "linkedin.com")
            lower.contains("tiktok.com") -> extractFromPath(link, "tiktok.com")
            lower.contains("twitter.com") || lower.contains("x.com") -> extractFromPath(link, listOf("twitter.com", "x.com"))
            else -> null
        }
    }

    private fun extractFromPath(link: String, domains: List<String>): String? {
        try {
            val uri = android.net.Uri.parse(link)
            var path = uri.path ?: return null
            for (domain in domains) {
                if (link.contains(domain)) {
                    path = path.removePrefix("/").removeSuffix("/")
                    return if (path.isNotEmpty()) path else null
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun extractFromPath(link: String, domain: String): String? = extractFromPath(link, listOf(domain))

    fun scoreNameMatch(hint: String?, title: String, link: String): Int {
        if (hint.isNullOrBlank()) return 0
        val cleanHint = hint.lowercase().trim()
        val cleanTitle = title.lowercase()

        return when {
            cleanTitle.contains(cleanHint) -> 1400
            cleanTitle.split(" ").count { cleanHint.contains(it) } >= 2 -> 900
            else -> 0
        }
    }

    fun scoreUrlPattern(link: String?): Int {
        if (link.isNullOrBlank()) return 0
        val lower = link.lowercase()

        return when {
            lower.contains("/profile") || lower.contains("/in/") || lower.contains("/@") -> 1100
            lower.contains("/user/") -> 900
            lower.contains("/people/") -> 700
            else -> 300
        }
    }
}
