package com.yourcompany.facesearch.network

import android.net.Uri

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
            lower.contains("linktr.ee") -> PlatformScore("Linktree", 2600, true)
            lower.contains("threads.net") || lower.contains("threads.app") -> PlatformScore("Threads", 1600, true)
            lower.contains("bsky.app") || lower.contains("bluesky") -> PlatformScore("Bluesky", 1500, true)
            lower.contains("mastodon.social") || lower.contains("mastodon") || lower.contains("fosstodon.org") || lower.contains("mstdn.social") || lower.contains("hachyderm.io") -> PlatformScore("Mastodon", 1400, true)
            lower.contains("behance.net") -> PlatformScore("Behance", 1300, true)
            lower.contains("dribbble.com") -> PlatformScore("Dribbble", 1200, true)
            lower.contains("youtube.com") -> PlatformScore("YouTube", 1400, false)
            lower.contains("t.me") || lower.contains("telegram.org") -> PlatformScore("Telegram", 1700, true)
            lower.contains("wa.me") || lower.contains("whatsapp.com") -> PlatformScore("WhatsApp", 1600, true)
            lower.contains("github.com") -> PlatformScore("GitHub", 1500, true)
            lower.contains("stackexchange.com") -> PlatformScore("Stack Exchange", 1000, false)
            lower.contains("ok.ru") -> PlatformScore("Odnoklassniki", 900, true)
            lower.contains("flickr.com") -> PlatformScore("Flickr", 700, false)
            lower.contains("tumblr.com") -> PlatformScore("Tumblr", 600, false)
            lower.contains("vk.com") || lower.contains("vkontakte") -> PlatformScore("VKontakte", 1800, true)
            lower.contains("snapchat.com") || lower.contains("snap.com") -> PlatformScore("Snapchat", 1300, true)
            lower.contains("discord.com") || lower.contains("discord.gg") || lower.contains("discordapp.com") -> PlatformScore("Discord", 1200, true)
            lower.contains("quora.com") -> PlatformScore("Quora", 1100, true)
            lower.contains("gitlab.com") -> PlatformScore("GitLab", 1400, true)
            lower.contains("stackoverflow.com") -> PlatformScore("Stack Overflow", 1300, false)
            lower.contains("medium.com") -> PlatformScore("Medium", 1200, true)
            lower.contains("dev.to") -> PlatformScore("Dev.to", 1100, true)
            lower.contains("hashnode.com") -> PlatformScore("Hashnode", 1000, true)
            lower.contains("patreon.com") -> PlatformScore("Patreon", 1400, true)
            lower.contains("substack.com") -> PlatformScore("Substack", 700, true)
            lower.contains("twitch.tv") -> PlatformScore("Twitch", 1500, true)
            lower.contains("onlyfans.com") -> PlatformScore("OnlyFans", 1500, true)
            lower.contains("fansly.com") -> PlatformScore("Fansly", 1400, true)
            lower.contains("soundcloud.com") -> PlatformScore("SoundCloud", 1200, true)
            lower.contains("spotify.com") -> PlatformScore("Spotify", 1100, true)
            lower.contains("keybase.io") -> PlatformScore("Keybase", 1100, true)
            lower.contains("producthunt.com") -> PlatformScore("Product Hunt", 1000, true)
            lower.contains("pornhub.com") -> PlatformScore("Pornhub", 1300, true)
            lower.contains("xvideos.com") -> PlatformScore("XVideos", 1300, true)
            lower.contains("xnxx.com") -> PlatformScore("XNXX", 1300, true)
            lower.contains("xhamster.com") -> PlatformScore("xHamster", 1300, true)
            lower.contains("redtube.com") -> PlatformScore("RedTube", 1300, true)
            lower.contains("youporn.com") -> PlatformScore("YouPorn", 1300, true)
            lower.contains("spankbang.com") -> PlatformScore("SpankBang", 1300, true)
            lower.contains("eporner.com") -> PlatformScore("Eporner", 1300, true)
            lower.contains("beeg.com") -> PlatformScore("Beeg", 1300, true)
            lower.contains("tnaflix.com") -> PlatformScore("TNAFlix", 1300, true)
            lower.contains("thumbzilla.com") -> PlatformScore("Thumbzilla", 1300, true)
            lower.contains("motherless.com") -> PlatformScore("Motherless", 1300, true)
            lower.contains("tube8.com") -> PlatformScore("Tube8", 1300, true)
            lower.contains("cumlouder.com") -> PlatformScore("CumLouder", 1300, true)
            lower.contains("hqporner.com") -> PlatformScore("HQPornEr", 1300, true)
            lower.contains("porntrex.com") -> PlatformScore("PornTrex", 1300, true)
            lower.contains("txxx.com") -> PlatformScore("TXXX", 1300, true)
            lower.contains("chaturbate.com") -> PlatformScore("Chaturbate", 1300, true)
            
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
               lower.contains("/add/") ||
               lower.contains("/channel/") ||
               lower.contains("/c/") ||
               lower.contains("about") ||
               lower.contains("bio")
    }

    fun extractUsername(link: String?, platform: PlatformScore): String? {
        if (link.isNullOrBlank()) return null

        // Exclude media/content path segments that aren't user profiles
        val nonProfileSegments = setOf(
            "p", "reel", "reels", "tv", "stories", "explore", "share",      // Instagram
            "posts", "watch", "photo", "groups", "story",                   // Facebook
            "status", "i", "hashtag",                                       // X / Twitter
            "r", "comments", "gallery"                                      // Reddit
        )

        val rawPath = extractFromPath(link, listOf(
            "instagram.com", "facebook.com", "linkedin.com", "tiktok.com",
            "twitter.com", "x.com", "threads.net", "bsky.app", "mastodon.social",
            "reddit.com", "youtube.com", "pinterest.com"
        )) ?: return null

        val firstSegment = rawPath.split('/').firstOrNull().orEmpty()
        if (firstSegment.lowercase() in nonProfileSegments) return null
        
        // Alphanumeric hashes without common username characters are usually shortcodes
        if (firstSegment.length >= 10 && firstSegment.any { it.isUpperCase() } && firstSegment.any { it.isLowerCase() } && firstSegment.any { it.isDigit() }) {
            return null
        }

        return firstSegment
    }

    private fun extractFromPath(link: String, domains: List<String>): String? {
        try {
            val formattedLink = if (!link.startsWith("http://") && !link.startsWith("https://")) {
                "https://$link"
            } else {
                link
            }
            val uri = Uri.parse(formattedLink)
            var path = uri.path ?: return null
            for (domain in domains) {
                if (formattedLink.contains(domain)) {
                    path = path.removePrefix("/").removeSuffix("/")
                    return if (path.isNotEmpty()) path else null
                }
            }
        } catch (_: Exception) {}
        return null
    }

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



