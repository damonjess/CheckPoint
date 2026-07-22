package com.yourcompany.facesearch.network

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ImageUploadRepository {
    private val api = ApiClient.imageUploadApi
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Host image locally in the app's Ktor server for Termux access.
     */
    fun stageLocalProbe(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        LocalServer.currentProbeImage = stream.toByteArray()
        return "http://localhost:8080/probe.jpg"
    }

    suspend fun uploadImage(bitmap: Bitmap, onLog: (String) -> Unit = {}): String? {
        val stream = ByteArrayOutputStream()
        // Good balance for face search: enough detail, small size
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val byteArray = stream.toByteArray()
        
        onLog("PROBE READY: ${byteArray.size / 1024} KB")

        // Try Hosting Chain
        val apiKey = Secrets.IMGBB_API_KEY
        
        // 1. ImgBB Base64 (Most Reliable)
        if (apiKey.isNotBlank() && apiKey != "null") {
            onLog("Attempting ImgBB (Base64 Mode)...")
            val base64 = Base64.encodeToString(byteArray, Base64.DEFAULT)
            try {
                val response = api.uploadBase64(apiKey, base64)
                if (response.isSuccessful && response.body()?.success == true) {
                    onLog("✓ ImgBB Success")
                    return response.body()?.data?.url ?: response.body()?.data?.display_url
                } else {
                    val msg = response.body()?.error?.message ?: "Unknown API Error"
                    onLog("⚠ ImgBB Base64 fail: $msg")
                }
            } catch (e: Exception) {
                onLog("⚠ ImgBB Base64 Exception: ${e.message}")
            }
        }

        // 2. Telegra.ph (Improved)
        onLog("Switching to Telegra.ph cluster...")
        val telegraph = uploadToTelegraph(byteArray, onLog)
        if (telegraph != null) return telegraph

        // 3. ImgLoc (Backup)
        onLog("Switching to ImgLoc gateway...")
        val imgLoc = uploadToImgLoc(byteArray, onLog)
        if (imgLoc != null) return imgLoc

        onLog("✗ ALL HOSTING PROVIDERS FAILED.")
        return null
    }

    private suspend fun uploadToTelegraph(bytes: ByteArray, onLog: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val requestBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "probe.jpg", requestBody)
            .build()

        val request = Request.Builder()
            .url("https://telegra.ph/upload")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val json = response.body?.string() ?: ""
                if (response.isSuccessful && json.contains("src")) {
                    val match = "\"src\":\"([^\"]+)\"".toRegex().find(json)
                    val path = match?.groups?.get(1)?.value
                    if (path != null) {
                        onLog("✓ Telegra.ph Active")
                        return@withContext "https://telegra.ph$path"
                    }
                }
                onLog("⚠ Telegra.ph fail: ${response.code} ${json.take(30)}")
            }
        } catch (e: Exception) {
            onLog("⚠ Telegra.ph error: ${e.message}")
        }
        null
    }

    private suspend fun uploadToImgLoc(bytes: ByteArray, onLog: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        // Simple anonymous upload fallback: Catbox.moe
        return@withContext uploadToCatbox(bytes, onLog)
    }

    private suspend fun uploadToCatbox(bytes: ByteArray, onLog: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val requestBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("fileToUpload", "probe.jpg", requestBody)
            .build()

        val request = Request.Builder()
            .url("https://catbox.moe/user/api.php")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val res = response.body?.string()?.trim() ?: ""
                if (response.isSuccessful && res.startsWith("http")) {
                    onLog("✓ Catbox Active")
                    return@withContext res
                }
                onLog("⚠ Catbox fail: $res")
            }
        } catch (e: Exception) {
            onLog("⚠ Catbox error: ${e.message}")
        }
        null
    }
}
