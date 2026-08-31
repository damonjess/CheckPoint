package com.yourcompany.facesearch.network

import android.graphics.Bitmap
import com.google.gson.Gson
import com.yourcompany.facesearch.network.model.ImgbbResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class FreeImageHost {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

    suspend fun upload(bitmap: Bitmap, onLog: (String) -> Unit): String? {
        val bytes = ByteArrayOutputStream().apply {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, this)
        }.toByteArray()

        onLog("PROBE READY: ${bytes.size / 1024} KB")

        // We must use highly reliable hosts. 
        // Freeimage.host is currently the most stable.
        val hosts = listOf(
            ::imgbb,
            ::freeimageHost,
            ::fileCoffee
        )

        for (hostFunc in hosts) {
            val result = hostFunc(bytes, onLog)
            if (result != null) return result
        }

        onLog("✗ ALL PUBLIC HOSTS FAILED. Engines will not be able to fetch the image.")
        return null
    }

    private suspend fun imgbb(bytes: ByteArray, onLog: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val apiKey = com.yourcompany.facesearch.BuildConfig.IMGBB_API_KEY.ifBlank { "752a049b4efdbb31dc4a517ee2da39f8" }
        
        // Try Binary upload first
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "probe.jpg", bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val req = Request.Builder()
            .url("https://api.imgbb.com/1/upload?key=$apiKey&expiration=600")
            .post(body)
            .build()

        try {
            client.newCall(req).execute().use { res ->
                val json = res.body?.string() ?: ""
                val response = gson.fromJson(json, ImgbbResponse::class.java)
                
                if (res.isSuccessful && response?.success == true) {
                    val url = response.data?.url
                    if (url != null) {
                        onLog("✓ ImgBB Active (Binary)")
                        return@withContext url
                    }
                } else if (res.code == 400) {
                    onLog("⚠ ImgBB Binary Rejected (400). Attempting Base64 fallback...")
                    return@withContext imgbbBase64(bytes, apiKey, onLog)
                } else {
                    onLog("⚠️ ImgBB Rejected: ${res.code}")
                }
            }
        } catch (e: Exception) { onLog("⚠️ ImgBB Error: ${e.message}") }
        null
    }

    private suspend fun imgbbBase64(bytes: ByteArray, apiKey: String, onLog: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        
        val body = okhttp3.FormBody.Builder()
            .add("image", base64)
            .build()

        val req = Request.Builder()
            .url("https://api.imgbb.com/1/upload?key=$apiKey&expiration=600")
            .post(body)
            .build()

        try {
            client.newCall(req).execute().use { res ->
                val json = res.body?.string() ?: ""
                val response = gson.fromJson(json, ImgbbResponse::class.java)
                
                if (res.isSuccessful && response?.success == true) {
                    val url = response.data?.url
                    if (url != null) {
                        onLog("✓ ImgBB Active (Base64)")
                        return@withContext url
                    }
                } else {
                    onLog("⚠️ ImgBB Base64 Rejected: ${res.code}")
                }
            }
        } catch (e: Exception) { onLog("⚠️ ImgBB Base64 Error: ${e.message}") }
        null
    }

    private suspend fun freeimageHost(bytes: ByteArray, onLog: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        // Public API key for freeimage.host
        val apiKey = "6d207e02198a847aa98d0a2a901485a5" 
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("key", apiKey)
            .addFormDataPart("action", "upload")
            .addFormDataPart("source", "probe.jpg", bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
            .addFormDataPart("format", "json")
            .build()

        val req = Request.Builder()
            .url("https://freeimage.host/api/1/upload")
            .header("User-Agent", ua)
            .post(body)
            .build()

        try {
            client.newCall(req).execute().use { res ->
                val json = res.body?.string() ?: ""
                if (res.isSuccessful && json.contains("\"url\"")) {
                    val m = "\"url\":\"([^\"]+)\"".toRegex().find(json)
                    val url = m?.groups?.get(1)?.value?.replace("\\/", "/")
                    if (url != null) {
                        onLog("✓ FreeImage.host Active")
                        return@withContext url
                    }
                }
            }
        } catch (e: Exception) { onLog("⚠️ FreeImage.host Error: ${e.message}") }
        null
    }

    private suspend fun fileCoffee(bytes: ByteArray, onLog: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "probe.jpg", bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val req = Request.Builder()
            .url("https://file.coffee/api/file/upload")
            .header("User-Agent", ua)
            .post(body)
            .build()

        try {
            client.newCall(req).execute().use { res ->
                val json = res.body?.string() ?: ""
                if (res.isSuccessful && json.contains("\"url\"")) {
                    val m = "\"url\":\"([^\"]+)\"".toRegex().find(json)
                    val url = m?.groups?.get(1)?.value?.replace("\\/", "/")
                    if (url != null) {
                        onLog("✓ File.coffee Active")
                        return@withContext url
                    }
                }
            }
        } catch (e: Exception) { onLog("⚠️ File.coffee Error: ${e.message}") }
        null
    }
}
