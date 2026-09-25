package com.yourcompany.facesearch.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ShareManager {

    fun shareText(
        context: Context,
        text: String,
        chooserTitle: String,
        mimeType: String = "text/plain",
        fileName: String = "export_${System.currentTimeMillis()}.txt"
    ) {
        val tempFile = File(context.cacheDir, fileName)
        tempFile.writeText(text)
        shareFile(
            context = context,
            file = tempFile,
            mimeType = mimeType,
            chooserTitle = chooserTitle,
            extraText = text
        )
    }

    fun shareFile(
        context: Context,
        file: File,
        mimeType: String,
        chooserTitle: String,
        extraText: String? = null
    ) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            extraText?.let { putExtra(Intent.EXTRA_TEXT, it) }
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    fun buildFileShareIntent(
        context: Context,
        file: File,
        mimeType: String,
        extraText: String? = null
    ): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            extraText?.let { putExtra(Intent.EXTRA_TEXT, it) }
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
}
