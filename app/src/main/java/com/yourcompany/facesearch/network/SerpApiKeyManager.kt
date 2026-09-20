package com.yourcompany.facesearch.network

import android.content.Context
import com.yourcompany.facesearch.BuildConfig

object SerpApiKeyManager {
    private const val PREFS_NAME = "face_search_prefs"
    private const val KEY_SERP_API_KEY = "serp_api_key"

    fun cleanKey(rawKey: String): String {
        val cleaned = rawKey
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .removePrefix("'")
            .removeSuffix("'")
            .trim()

        if (cleaned.equals("YOUR_SERPAPI_KEY_HERE", ignoreCase = true) ||
            cleaned.equals("your_key_here", ignoreCase = true) ||
            cleaned.equals("null", ignoreCase = true) ||
            cleaned.equals("none", ignoreCase = true)) {
            return ""
        }
        return cleaned
    }

    fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_SERP_API_KEY, "").orEmpty()
        val cleanedSaved = cleanKey(savedKey)
        if (cleanedSaved.isNotBlank()) {
            return cleanedSaved
        }
        return cleanKey(BuildConfig.SERP_API_KEY)
    }

    fun saveApiKey(context: Context, apiKey: String) {
        val cleaned = cleanKey(apiKey)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERP_API_KEY, cleaned).apply()
    }

    fun hasApiKey(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }
}
