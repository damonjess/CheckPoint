package com.yourcompany.facesearch.vision

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class FreeFaceSearchHelper(private val context: Context) {

    /**
     * PURE FREE MODE: Opens search engines directly with the image attached.
     * No uploads. No APIs. Just native Android intents.
     */
    fun launchDirectSearch(bitmap: Bitmap, nameHint: String?) {
        val uri = saveToCache(bitmap)
        val name = nameHint ?: "Unknown"

        val intents = listOf(
            // Google Lens via Google App
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Search: $name")
                `package` = "com.google.android.googlequicksearchbox"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            } to "Google Lens",

            // Bing via Edge
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Bing Visual Search: $name")
                `package` = "com.microsoft.emmx"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            } to "Bing/Edge",

            // Generic chooser for Yandex, TinEye, others
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "OSINT Search: $name")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            } to "Yandex/Others"
        )

        intents.forEachIndexed { index, (intent, label) ->
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    when (label) {
                        "Yandex/Others" -> {
                            val chooser = Intent.createChooser(intent, "Search on Yandex / TinEye / Others")
                            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(chooser)
                        }
                        else -> context.startActivity(intent)
                    }
                } catch (e: Exception) {
                    // App not installed, open browser URL fallback
                    val fallback = when (label) {
                        "Google Lens" -> "https://lens.google.com/upload"
                        "Bing/Edge" -> "https://www.bing.com/images/searchbyimage"
                        else -> "https://yandex.com/images/search"
                    }
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }, index * 1500L)
        }
    }

    fun saveToCache(bitmap: Bitmap): Uri {
        val file = File(context.cacheDir, "free_search_probe.jpg")
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }
}



