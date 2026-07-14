package com.yourcompany.facesearch.network

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class FreeReverseImageSearch(private val context: Context) {

    fun searchWithMultipleEngines(croppedFace: Bitmap, nameHint: String?) {
        val imageUri = saveBitmapToCache(croppedFace)

        // Open Google Lens (best for faces)
        openGoogleLens(imageUri, nameHint)

        // Delay then open Bing
        Handler(Looper.getMainLooper()).postDelayed({
            openBingVisualSearch(imageUri)
        }, 1500)

        // Yandex
        Handler(Looper.getMainLooper()).postDelayed({
            openYandexImages(imageUri)
        }, 3500)
    }

    private fun saveBitmapToCache(bitmap: Bitmap): Uri {
        val cachePath = File(context.cacheDir, "images")
        cachePath.mkdirs()
        val file = File(cachePath, "face_search.jpg")
        
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    private fun openGoogleLens(imageUri: Uri, nameHint: String?) {
        // Most browsers/Lens app accept an image stream for upload via VIEW or SEND
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("https://lens.google.com/upload?ep=ccm"), "image/jpeg")
            putExtra(Intent.EXTRA_STREAM, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun openBingVisualSearch(imageUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bing.com/images/searchbyimage")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Search on Bing").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun openYandexImages(imageUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.com/images/search")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
