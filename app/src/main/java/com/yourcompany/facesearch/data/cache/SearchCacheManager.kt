package com.yourcompany.facesearch.data.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yourcompany.facesearch.network.SerpVisualMatch
import java.io.File

object SearchCacheManager {
    private const val TAG = "SearchCacheManager"
    private const val CACHE_DIR_NAME = "search_results_cache"
    private const val EXPIRATION_MS = 24 * 60 * 60 * 1000L // 24 hours

    private val gson = Gson()

    private fun getCacheFile(context: Context, hash: String): File {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "$hash.json")
    }

    fun getCachedResults(context: Context, hash: String): List<SerpVisualMatch>? {
        try {
            val file = getCacheFile(context, hash)
            if (!file.exists()) return null

            if (System.currentTimeMillis() - file.lastModified() > EXPIRATION_MS) {
                file.delete()
                return null
            }

            val json = file.readText()
            val type = object : TypeToken<List<SerpVisualMatch>>() {}.type
            return gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cache for $hash", e)
            return null
        }
    }

    fun saveResults(context: Context, hash: String, results: List<SerpVisualMatch>) {
        try {
            val file = getCacheFile(context, hash)
            val json = gson.toJson(results)
            file.writeText(json)
            Log.d(TAG, "Saved ${results.size} results to cache for $hash")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cache for $hash", e)
        }
    }
}
