package com.yourcompany.facesearch.network

import android.net.Uri

object ThumbnailUtils {
    fun normalize(raw: String?, sourceUrl: String? = null): String? {
        if (raw.isNullOrBlank()) return null
        var url = raw.trim()
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\/", "/")

        if (url.startsWith("//")) url = "https:$url"
        
        if (url.startsWith("/") && !sourceUrl.isNullOrBlank()) {
            url = try {
                val uri = Uri.parse(sourceUrl)
                val scheme = uri.scheme ?: "https"
                val host = uri.host.orEmpty()
                if (host.isNotBlank()) "$scheme://$host$url" else url
            } catch (_: Exception) {
                url
            }
        }

        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:image")) {
            if (isNoise(url)) {
                android.util.Log.d("ThumbnailUtils", "Dropped noise URL: $url")
                return null
            }
            return url
        }
        return null
    }

    fun canonicalKey(raw: String?): String? {
        val normalized = normalize(raw) ?: return null
        if (normalized.startsWith("data:image")) return normalized.take(96)
        return try {
            val uri = android.net.Uri.parse(normalized)
            val retained = uri.queryParameterNames
                .filterNot { name -> name.lowercase() in setOf("w", "h", "width", "height", "q", "quality", "fit", "crop", "format", "fm", "auto", "cache", "cb") }
                .sorted()
                .joinToString("&") { name -> "$name=${uri.getQueryParameter(name).orEmpty()}" }
            buildString {
                append(uri.scheme?.lowercase().orEmpty())
                append("://")
                append(uri.host?.lowercase().orEmpty())
                append(uri.path.orEmpty().trimEnd('/'))
                if (retained.isNotBlank()) append('?').append(retained)
            }
        } catch (_: Exception) {
            normalized.substringBefore('?').trimEnd('/')
        }
    }

    private fun isNoise(url: String): Boolean {
        val lower = url.lowercase()
        val noise = listOf(
            "1x1.gif", "pixel.gif", "spacer.gif", "transparent.png",
            "facebook.com/images/fb_icon", "fb_logo", "facebook_logo",
            "instagram.com/static/images", "twitter_logo", "x_logo",
            "tiktok_logo", "linkedin_logo",
            "favicon.ico", "apple-touch-icon", "default_avatar",
            "no_profile", "blank_profile", "anonymous.png",
            // Aggressively drop stock vector sites
            "shutterstock", "istockphoto", "stock.adobe", "vectorstock", 
            "freepik", "depositphotos", "123rf", "dreamstime", "alamy",
            "gettyimages", "vecteezy", "flaticon", "icon-icons", "pngtree"
        )
        
        return noise.any { lower.contains(it) } ||
            (lower.startsWith("data:image") && url.length < 100)
    }
}
