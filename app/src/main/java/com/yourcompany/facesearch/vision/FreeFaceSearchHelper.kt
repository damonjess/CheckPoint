package com.yourcompany.facesearch.vision

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class FreeFaceSearchHelper(private val context: Context, private val cropper: NativeFaceCropper) {

    suspend fun searchMyPhoto(photoBitmap: Bitmap, myName: String? = null, engineIndex: Int? = null) {
        val goodBitmap = cropper.prepareFaceForSearch(photoBitmap)
        val uri = saveImage(goodBitmap)

        // Open engines
        openEngines(uri, myName, engineIndex)
    }

    /**
     * DIRECT search - NO upload needed. Saves image locally and opens browser tabs.
     * This is used by FREE mode when ImgBB is down.
     */
    fun searchMyPhotoDirect(photoBitmap: Bitmap, myName: String? = null) {
        // Save the image locally
        val uri = saveImage(photoBitmap)

        // Open browser tabs directly with the local image
        openEnginesDirect(uri, myName)
    }

    /**
     * Opens browser tabs with the local image URI.
     * Uses ACTION_SEND to share the image with browsers.
     */
    private fun openEnginesDirect(uri: Uri, name: String?) {
        // Google Lens
        openBrowserWithImage(uri, "https://lens.google.com/upload", name)

        // Bing
        openBrowserWithImage(uri, "https://www.bing.com/images/searchbyimage", name)

        // Yandex
        openBrowserWithImage(uri, "https://yandex.com/images/search", name)

        // TinEye
        openBrowserWithImage(uri, "https://tineye.com/search", name)
    }

    private fun openBrowserWithImage(uri: Uri, url: String, name: String?) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Searching for: ${name ?: "this person"}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Use a chooser so the user can pick a browser or Google Lens
            val chooser = Intent.createChooser(shareIntent, "Search with...")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            // Fallback: open the URL directly
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    suspend fun openGoogleLensOnly(bitmap: Bitmap, nameHint: String?) {
        val uri = saveImage(cropper.prepareFaceForSearch(bitmap))
        
        // Use ACTION_SEND for sharing the image to Google Lens or other search apps
        // This avoids the confusing "ACTION_VIEW with URL + MimeType" which triggers gallery apps
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        try {
            // Google Lens is part of the Google App, we can try to target it or use a chooser
            val chooser = Intent.createChooser(intent, "Search with Google Lens / Internet")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            // Fallback to browser if everything else fails
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com/upload"))
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(browserIntent)
        }
    }

    private fun saveImage(bitmap: Bitmap): Uri {
        val file = File(context.cacheDir, "search_photo.jpg")
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    private fun openEngines(uri: Uri, name: String?, engineIndex: Int? = null) {
        val urls = listOf(
            "https://lens.google.com/upload",           // Best for faces
            "https://pimeyes.com/en",                  // Top face search engine (Manual upload)
            "https://www.bing.com/images/searchbyimage", 
            "https://yandex.com/images/search",
            "https://tineye.com"                       // Reliable image tracker
        )

        if (engineIndex != null && engineIndex in urls.indices) {
            launchIntent(urls[engineIndex], uri)
        } else {
            urls.forEachIndexed { i, url ->
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    launchIntent(url, uri)
                }, (i * 1500L))
            }
        }
    }

    private fun launchIntent(url: String, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        // Use a separate intent for sharing the image to the system, 
        // as standard browsers don't support direct image upload via URL intents.
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            // Open the search engine page
            context.startActivity(intent)
            
            // Also offer to "Share" the photo so user can manually upload or use an app like Google Lens
            val chooser = Intent.createChooser(shareIntent, "Search Socials: Upload this photo")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            context.startActivity(Intent.createChooser(intent, "Open Search"))
        }
    }
}