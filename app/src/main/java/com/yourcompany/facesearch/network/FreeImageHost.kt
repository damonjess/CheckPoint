package com.yourcompany.facesearch.network

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 100% free image hosting. No API keys required.
 * Chain: Telegra.ph → Catbox.moe → Imgur Anonymous → Postimages
 */
class FreeImageHost {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    suspend fun upload(bitmap: Bitmap, onLog: (String) -> Unit): String? {
        val bytes = ByteArrayOutputStream().apply {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, this)
        }.toByteArray()

        onLog("PROBE READY: ${bytes.size / 1024} KB")

        // Shuffle hosts to distribute load and bypass IP blocks
        val hosts = listOf(
            ::telegraph,
            ::catbox,
            ::imgur,
            ::postimages
        ).shuffled()

        for (hostFunc in hosts) {
            val result = hostFunc(bytes, onLog)
            if (result != null) return result
        }

        onLog("✗ ALL FREE HOSTS FAILED. Using local probe only.")
        return null
    }

    private suspend fun telegraph(bytes: ByteArray, onLog: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "probe.jpg", bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val req = Request.Builder()
            .url("https://telegra.ph/upload")
            .header("User-Agent", ua)
            .post(body)
            .build()

        try {
            client.newCall(req).execute().use { res ->
                val json = res.body?.string() ?: ""
                if (res.isSuccessful && json.contains("src")) {
                    val m = "\"src\":\"([^\"]+)\"".toRegex().find(json)
                    val path = m?.groups?.get(1)?.value
                    if (path != null) {
                        onLog("✓ Telegra.ph Active")
                        return@withContext "https://telegra.ph$path"
                    }
                }
            }
        } catch (e: Exception) { onLog("⚠️ Telegra.ph: ${e.message}") }
        null
    }

    private suspend fun catbox(bytes: ByteArray, onLog: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("fileToUpload", "probe.jpg", bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val req = Request.Builder()
            .url("https://catbox.moe/user/api.php")
            .header("User-Agent", ua)
            .post(body)
            .build()

        try {
            client.newCall(req).execute().use { res ->
                val txt = res.body?.string()?.trim() ?: ""
                if (res.isSuccessful && txt.startsWith("http")) {
                    onLog("✓ Catbox Active")
                    return@withContext txt
                }
            }
        } catch (e: Exception) { onLog("⚠️ Catbox: ${e.message}") }
        null
    }

    private suspend fun imgur(bytes: ByteArray, onLog: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT))
            .build()

        val req = Request.Builder()
            .url("https://api.imgur.com/3/image")
            .header("Authorization", "Client-ID 546c25a59c58ad7")
            .header("User-Agent", ua)
            .post(body)
            .build()

        try {
            client.newCall(req).execute().use { res ->
                val json = res.body?.string() ?: ""
                val m = "\"link\":\"([^\"]+)\"".toRegex().find(json)
                val url = m?.groups?.get(1)?.value
                if (url != null) {
                    onLog("✓ Imgur Anonymous Active")
                    return@withContext url
                }
            }
        } catch (e: Exception) { onLog("⚠️ Imgur: ${e.message}") }
        null
    }

    private suspend fun postimages(bytes: ByteArray, onLog: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "probe.jpg", bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val req = Request.Builder()
            .url("https://postimages.org/json/rr")
            .header("User-Agent", ua)
            .post(body)
            .build()

        try {
            client.newCall(req).execute().use { res ->
                val json = res.body?.string() ?: ""
                val m = "\"url\":\"([^\"]+)\"".toRegex().find(json)
                val url = m?.groups?.get(1)?.value
                if (url != null) {
                    onLog("✓ Postimages Active")
                    return@withContext url
                }
            }
        } catch (e: Exception) { onLog("⚠️ Postimages: ${e.message}") }
        null
    }
}



