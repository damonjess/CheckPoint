package com.yourcompany.facesearch.network

import android.graphics.Bitmap
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class ImageUploadRepository {
    private val api = ApiClient.imageUploadApi

    suspend fun uploadImage(bitmap: Bitmap, onLog: (String) -> Unit = {}): String? {
        val stream = ByteArrayOutputStream()
        // Compress more for faster upload while maintaining enough detail for reverse search
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        val byteArray = stream.toByteArray()
        val requestFile = byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
        val body = MultipartBody.Part.createFormData("image", "upload.jpg", requestFile)

        onLog("PROBE SIZE: ${byteArray.size / 1024} KB")

        return try {
            val apiKey = Secrets.IMGBB_API_KEY
            if (apiKey.isBlank() || apiKey == "null") {
                onLog("⚠ No ImgBB Key - switching to Telegra.ph fallback")
                uploadToTelegraph(byteArray, onLog)
            } else {
                onLog("Uploading to ImgBB cluster...")
                val response = api.upload(apiKey, body)
                if (response.isSuccessful) {
                    val data = response.body()?.data
                    val rawUrl = data?.url 
                    val displayUrl = data?.display_url
                    onLog("✓ ImgBB Success")
                    rawUrl ?: displayUrl
                } else {
                    val errorBody = response.errorBody()?.string()
                    onLog("✗ ImgBB Error ${response.code()}: $errorBody")
                    onLog("Falling back to secondary provider...")
                    uploadToTelegraph(byteArray, onLog)
                }
            }
        } catch (e: Exception) {
            onLog("⚠ Hosting Exception: ${e.message}")
            uploadToTelegraph(byteArray, onLog)
        }
    }

    private suspend fun uploadToTelegraph(bytes: ByteArray, onLog: (String) -> Unit): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        onLog("Engaging Telegra.ph pipeline...")
        val client = okhttp3.OkHttpClient()
        val requestBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "upload.jpg", requestBody)
            .build()

        val request = okhttp3.Request.Builder()
            .url("https://telegra.ph/upload")
            .post(body)
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: ""
                // Telegra.ph returns a JSON array like [{"src":"/file/..."}]
                val pattern = "\"src\":\"([^\"]+)\"".toRegex()
                val match = pattern.find(json)
                val url = match?.groups?.get(1)?.value?.let { "https://telegra.ph$it" }
                if (url != null) {
                    onLog("✓ Telegra.ph Success")
                } else {
                    onLog("✗ Telegra.ph parse failed: $json")
                }
                url
            } else {
                onLog("✗ Telegra.ph Error: HTTP ${response.code}")
                null
            }
        } catch (e: Exception) {
            onLog("✗ Telegra.ph Exception: ${e.message}")
            null
        }
    }
}
