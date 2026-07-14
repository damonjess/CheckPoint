package com.yourcompany.facesearch.vision

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class FreeFaceSearchHelper(private val context: Context) {

    fun searchMyPhoto(photoBitmap: Bitmap, myName: String? = null, engineIndex: Int? = null) {
        val goodBitmap = ImageEnhancer.prepareImageForSearch(photoBitmap)
        val uri = saveImage(goodBitmap)

        // Open engines
        openEngines(uri, myName, engineIndex)
    }

    fun openGoogleLensOnly(bitmap: Bitmap, nameHint: String?) {
        val uri = saveImage(ImageEnhancer.prepareImageForSearch(bitmap))
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("https://lens.google.com/upload"), "image/jpeg")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            context.startActivity(Intent.createChooser(intent, "Open Google Lens"))
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
            "https://www.bing.com/images/searchbyimage", 
            "https://yandex.com/images/search"
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
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "image/jpeg")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            context.startActivity(Intent.createChooser(intent, "Open Search"))
        }
    }
}