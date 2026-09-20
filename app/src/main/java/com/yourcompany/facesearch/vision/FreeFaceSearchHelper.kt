package com.yourcompany.facesearch.vision

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class FreeFaceSearchHelper(private val context: Context) {

    /**
     * Saves probe image to cache for internal pipeline processing.
     */
    fun saveToCache(bitmap: Bitmap): Uri {
        val file = File(context.cacheDir, "free_search_probe.jpg")
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }
}
