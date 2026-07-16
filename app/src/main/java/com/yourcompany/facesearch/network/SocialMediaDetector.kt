package com.yourcompany.facesearch.network

/**
 * Detects and scores social media platforms from URLs.
 * Prioritizes platforms for face search accuracy.
 */
object SocialMediaDetector {

    enum class Platform(val baseScore: Int, val isProfileBased: Boolean) {
        // Core Social Networks (High Priority - Best for profile pics)
        LINKEDIN(2500, true),
        FACEBOOK(2400, true),
        INSTAGRAM(2300, true),
        TWITTER(2200, true),
        TIKTOK(2100, true),
        
        // Visual & Photo Platforms
        PINTEREST(1800, true),
        REDDIT(1700, false),
        FLICKR(1600, true),
        TUMBLR(1500, false),
        
        // Professional/Entertainment
        GITHUB(1400, true),
        YOUTUBE(1300, false),
        TWITCH(1200, false),
        BEHANCE(1100, true),
        
        // Emerging Platforms
        SNAPCHAT(1000, true),
        DISCORD(900, false),
        TELEGRAM(800, false),
        WHATSAPP(700, false),
        
        // Photo/Dating
        BUMBLE(1900, true),
        HINGE(1850, true),
        TINDER(1800, true),
        MATCH(1700, true),
        OKPUPPY(1600, true),
        
        // Generic (Lower confidence)
        UNKNOWN(100, false)
    }

    fun detectPlatform(url: String?): Platform {
        if (url == null) return Platform.UNKNOWN
        
        val domain = url.lowercase()
        
        return when {
            domain.contains("linkedin.com") -> Platform.LINKEDIN
            domain.contains("facebook.com") -> Platform.FACEBOOK
            domain.contains("instagram.com") -> Platform.INSTAGRAM
            domain.contains("twitter.com") || domain.contains("x.com") -> Platform.TWITTER
            domain.contains("tiktok.com") || domain.contains("douyin.com") -> Platform.TIKTOK
            domain.contains("pinterest.") -> Platform.PINTEREST
            domain.contains("reddit.com") -> Platform.REDDIT
            domain.contains("flickr.com") -> Platform.FLICKR
            domain.contains("tumblr.com") -> Platform.TUMBLR
            domain.contains("github.com") -> Platform.GITHUB
            domain.contains("youtube.com") || domain.contains("youtu.be") -> Platform.YOUTUBE
            domain.contains("twitch.tv") -> Platform.TWITCH
            domain.contains("behance.net") -> Platform.BEHANCE
            domain.contains("snapchat.com") || domain.contains("snap.com") -> Platform.SNAPCHAT
            domain.contains("discord.com") || domain.contains("discordapp.com") -> Platform.DISCORD
            domain.contains("telegram.org") || domain.contains("t.me") -> Platform.TELEGRAM
            domain.contains("whatsapp.com") -> Platform.WHATSAPP
            domain.contains("bumble.com") -> Platform.BUMBLE
            domain.contains("hinge.co") -> Platform.HINGE
            domain.contains("tinder.com") -> Platform.TINDER
            domain.contains("match.com") -> Platform.MATCH
            domain.contains("okcupid.com") || domain.contains("okpuppy.com") -> Platform.OKPUPPY
            else -> Platform.UNKNOWN
        }
    }

    fun isProfileUrl(url: String?): Boolean {
        if (url == null) return false
        val path = url.lowercase()
        
        // Profile indicators for specific platforms
        return when {
            // LinkedIn profile patterns
            path.contains("linkedin.com/in/") -> true
            
            // Facebook profile patterns
            path.contains("facebook.com/") && !path.contains("facebook.com/share") -> true
            
            // Instagram profile patterns
            path.contains("instagram.com/") && !path.contains("instagram.com/p/") && !path.contains("instagram.com/reel") -> true
            
            // Twitter profile patterns
            (path.contains("twitter.com/") || path.contains("x.com/")) && 
            !path.contains("twitter.com/search") && !path.contains("x.com/search") -> true
            
            // TikTok profile patterns
            path.contains("tiktok.com/@") -> true
            
            // Reddit profile patterns
            path.contains("reddit.com/u/") || path.contains("reddit.com/user/") -> true
            
            // GitHub user profile
            path.contains("github.com/") && !path.contains("github.com/search") -> true
            
            // YouTube channel patterns
            path.contains("youtube.com/c/") || path.contains("youtube.com/@") || path.contains("youtube.com/user/") -> true
            
            // Twitch channel
            path.contains("twitch.tv/") -> true
            
            // Generic profile/people indicators
            path.contains("/profile") || path.contains("/people") || path.contains("/user") -> true
            
            else -> false
        }
    }

    fun extractUsername(url: String?, platform: Platform): String? {
        if (url == null) return null
        val path = url.lowercase()
        
        return when (platform) {
            Platform.LINKEDIN -> {
                path.substringAfter("/in/").substringBefore("/").takeIf { it.isNotEmpty() }
            }
            Platform.INSTAGRAM -> {
                path.substringAfter("instagram.com/").substringBefore("/").takeIf { it.isNotEmpty() && it != "p" && it != "reel" }
            }
            Platform.TWITTER -> {
                val cleaned = path.replace("x.com", "twitter.com")
                cleaned.substringAfter("twitter.com/").substringBefore("/").takeIf { it.isNotEmpty() && it != "search" }
            }
            Platform.TIKTOK -> {
                path.substringAfter("@").substringBefore("?").substringBefore("/").takeIf { it.isNotEmpty() }
            }
            Platform.GITHUB -> {
                path.substringAfter("github.com/").substringBefore("/").takeIf { it.isNotEmpty() }
            }
            Platform.REDDIT -> {
                if (path.contains("/u/")) {
                    path.substringAfter("/u/").substringBefore("/").takeIf { it.isNotEmpty() }
                } else if (path.contains("/user/")) {
                    path.substringAfter("/user/").substringBefore("/").takeIf { it.isNotEmpty() }
                } else null
            }
            else -> null
        }
    }

    fun scoreUrlPattern(url: String?): Int {
        if (url == null) return 0
        
        var bonus = 0
        val path = url.lowercase()
        
        // Bonus for direct profile patterns
        if (isProfileUrl(url)) bonus += 800
        
        // Bonus for standard social domain structures (not generic image CDN)
        if (!isCDNUrl(url)) bonus += 400 else bonus -= 200
        
        // Bonus for having a clean, simple URL (not spammy)
        if (path.split("/").size < 6) bonus += 300
        
        return bonus
    }

    private fun isCDNUrl(url: String): Boolean {
        val domain = url.lowercase()
        return domain.contains("cdn") || 
               domain.contains("cloudflare") || 
               domain.contains("akamai") ||
               domain.contains("images-") ||
               domain.contains("pbs.twimg") ||
               domain.contains("scontent")
    }
}
