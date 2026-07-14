package com.yourcompany.facesearch.vision

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class FreeFaceSearchHelper(private val context: Context) {

    fun searchMyPhoto(photoBitmap: Bitmap, myName: String? = null) {
        // Improved: Larger, higher quality crop
        val processedBitmap = prepareImageForSearch(photoBitmap)
        
        val imageUri = saveToCache(processedBitmap)
        
        openSearchEngines(imageUri, myName)
    }

    private fun prepareImageForSearch(original: Bitmap): Bitmap {
        // Make sure the image is large enough for good results
        val targetWidth = if (original.width < 600) 800 else original.width
        
        return if (original.width != targetWidth) {
            val scale = targetWidth.toFloat() / original.width
            Bitmap.createScaledBitmap(original, targetWidth, (original.height * scale).toInt(), true)
        } else {
            original
        }
    }

    private fun saveToCache(bitmap: Bitmap): Uri {
        val cacheDir = File(context.cacheDir, "face_search")
        cacheDir.mkdirs()
        val file = File(cacheDir, "my_photo_${System.currentTimeMillis()}.jpg")

        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)   // Good balance
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    private fun openSearchEngines(imageUri: Uri, nameHint: String?) {
        val engines = listOf(
            // Google Lens - Best for faces
            "https://lens.google.com/upload",
            // Bing Visual Search
            "https://www.bing.com/images/searchbyimage",
            // Yandex - Strong on people
            "https://yandex.com/images/search"
        )

        engines.forEachIndexed { index, url ->
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(url), "image/jpeg")
                    putExtra(Intent.EXTRA_STREAM, imageUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    context.startActivity(Intent.createChooser(intent, "Open in Browser"))
                }
            }, (index * 1600L))
        }
    }
}
