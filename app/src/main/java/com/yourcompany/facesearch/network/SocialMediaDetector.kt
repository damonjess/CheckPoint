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
            // Major Social
            lower.contains("instagram.com") || lower.contains("instagr.am") -> PlatformScore("Instagram", 2500, true)
            lower.contains("facebook.com") || lower.contains("fb.com") -> PlatformScore("Facebook", 2200, true)
            lower.contains("linkedin.com") -> PlatformScore("LinkedIn", 2100, true)
            lower.contains("tiktok.com") -> PlatformScore("TikTok", 2000, true)
            lower.contains("twitter.com") || lower.contains("x.com") -> PlatformScore("Twitter", 1900, true)
            lower.contains("vsco.co") -> PlatformScore("VSCO", 1500, true)
            lower.contains("newsite.com") -> PlatformScore("NewSite", 1400, true)
            lower.contains("vk.com") || lower.contains("vkontakte") -> PlatformScore("VKontakte", 1800, true)
            lower.contains("youtube.com") -> PlatformScore("YouTube", 1400, false)
            
            // Messaging
            lower.contains("t.me") || lower.contains("telegram.org") -> PlatformScore("Telegram", 1700, true)
            lower.contains("wa.me") || lower.contains("whatsapp.com") -> PlatformScore("WhatsApp", 1600, true)
            lower.contains("snapchat.com") -> PlatformScore("Snapchat", 1300, true)
            lower.contains("discord.com") -> PlatformScore("Discord", 1200, true)
            
            // Professional/Tech
            lower.contains("github.com") -> PlatformScore("GitHub", 1500, true)
            lower.contains("gitlab.com") -> PlatformScore("GitLab", 1400, true)
            lower.contains("stackoverflow.com") -> PlatformScore("Stack Overflow", 1300, false)
            lower.contains("medium.com") -> PlatformScore("Medium", 1200, true)
            lower.contains("dev.to") -> PlatformScore("Dev.to", 1100, true)
            lower.contains("hashnode.com") -> PlatformScore("Hashnode", 1000, true)
            
            // Q&A
            lower.contains("quora.com") -> PlatformScore("Quora", 1100, true)
            lower.contains("stackexchange.com") -> PlatformScore("Stack Exchange", 1000, false)
            
            // Other
            lower.contains("ok.ru") -> PlatformScore("Odnoklassniki", 900, true)
            lower.contains("flickr.com") -> PlatformScore("Flickr", 700, false)
            lower.contains("tumblr.com") -> PlatformScore("Tumblr", 600, false)
            lower.contains("patreon.com") -> PlatformScore("Patreon", 800, true)
            lower.contains("substack.com") -> PlatformScore("Substack", 700, true)
            lower.contains("twitch.tv") -> PlatformScore("Twitch", 1500, true)
            lower.contains("onlyfans.com") -> PlatformScore("OnlyFans", 1500, true)
            lower.contains("fansly.com") -> PlatformScore("Fansly", 1400, true)
            lower.contains("pornhub.com") -> PlatformScore("Pornhub", 1300, true)
            lower.contains("xvideos.com") -> PlatformScore("XVideos", 1300, true)
            lower.contains("xnxx.com") -> PlatformScore("XNXX", 1300, true)
            lower.contains("xhamster.com") -> PlatformScore("xHamster", 1300, true)
            
            // Dating
            lower.contains("tinder.com") -> PlatformScore("Tinder", 400, true)
            lower.contains("bumble.com") -> PlatformScore("Bumble", 400, true)
            
            // Chinese
            lower.contains("weibo.com") -> PlatformScore("Weibo", 800, true)
            lower.contains("douyin.com") -> PlatformScore("Douyin", 700, true)
            lower.contains("xiaohongshu.com") -> PlatformScore("Xiaohongshu", 600, true)
            lower.contains("zhihu.com") -> PlatformScore("Zhihu", 600, true)
            
            // Default
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



