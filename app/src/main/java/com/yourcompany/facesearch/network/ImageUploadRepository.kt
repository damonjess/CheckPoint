package com.yourcompany.facesearch.network

import android.graphics.Bitmap
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class ImageUploadRepository {
    private val api = ApiClient.imageUploadApi

    suspend fun uploadImage(bitmap: Bitmap): String? {
        val stream = ByteArrayOutputStream()
        // Compress more for faster upload while maintaining enough detail for reverse search
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        val byteArray = stream.toByteArray()
        val requestFile = byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
        val body = MultipartBody.Part.createFormData("image", "upload.jpg", requestFile)

        Log.d("NetworkDebug", "ImgBB Key: ${Secrets.IMGBB_API_KEY}")

        return try {
            val apiKey = Secrets.IMGBB_API_KEY
            if (apiKey.isBlank() || apiKey == "null") {
                // FALLBACK: Anonymous upload if no API key is provided
                uploadToTelegraph(byteArray)
            } else {
                val response = api.upload(apiKey, body)
                if (response.isSuccessful) {
                    val data = response.body()?.data
                    val rawUrl = data?.url 
                    val displayUrl = data?.display_url
                    rawUrl ?: displayUrl
                } else {
                    // If ImgBB fails (e.g. invalid key), try anonymous fallback
                    uploadToTelegraph(byteArray)
                }
            }
        } catch (e: Exception) {
            uploadToTelegraph(byteArray)
        }
    }

    private suspend fun uploadToTelegraph(bytes: ByteArray): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
                match?.groups?.get(1)?.value?.let { "https://telegra.ph$it" }
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
