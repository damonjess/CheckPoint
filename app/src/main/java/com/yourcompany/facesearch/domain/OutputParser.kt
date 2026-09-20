package com.yourcompany.facesearch.domain

import android.net.Uri

object OutputParser {
    private val urlRegex = Regex("https?://[\\w.-]+(?:\\.[\\w\\.-]+)+[/#?]?.*?(?=\\s|\$)")

    fun extractValidUrls(rawStdout: String): List<String> {
        return urlRegex.findAll(rawStdout)
            .map { it.value.trim() }
            .distinct()
            .filter { isValidUrl(it) }
            .toList()
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val parsed = Uri.parse(url)
            parsed.scheme in listOf("http", "https") && !parsed.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    fun extractPlatform(url: String): String {
        return try {
            val host = Uri.parse(url).host ?: return "unknown"
            val parts = host.removePrefix("www.").split(".")
            if (parts.size >= 2) parts[parts.size - 2] else host
        } catch (e: Exception) {
            "unknown"
        }
    }
}
