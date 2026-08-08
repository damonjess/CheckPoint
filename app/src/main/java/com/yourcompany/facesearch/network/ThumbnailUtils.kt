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
        return lower.contains("1x1.gif") ||
            lower.contains("pixel.gif") ||
            lower.contains("spacer.gif") ||
            lower.contains("transparent.png") ||
            (lower.startsWith("data:image") && url.length < 120)
    }
}
