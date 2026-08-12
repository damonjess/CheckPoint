package com.yourcompany.facesearch.network

object ThumbnailUtils {
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var url = raw.trim()
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\/", "/")

        if (url.startsWith("//")) url = "https:$url"
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:image")) {
            if (isNoise(url)) return null
            return url
        }
        return null
    }

    private fun isNoise(url: String): Boolean {
        val lower = url.lowercase()
        val noise = listOf(
            "1x1.gif", "pixel.gif", "spacer.gif", "transparent.png",
            "facebook.com/images/fb_icon", "fb_logo", "facebook_logo",
            "instagram.com/static/images", "twitter_logo", "x_logo",
            "tiktok_logo", "linkedin_logo", "logo.png", "logo.jpg",
            "favicon.ico", "apple-touch-icon", "default_avatar",
            "no_profile", "blank_profile", "anonymous.png"
        )
        
        return noise.any { lower.contains(it) } ||
            (lower.startsWith("data:image") && url.length < 100)
    }
}
