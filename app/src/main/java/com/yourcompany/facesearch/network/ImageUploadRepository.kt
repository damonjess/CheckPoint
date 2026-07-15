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
            val response = api.upload(Secrets.IMGBB_API_KEY, body)
            if (response.isSuccessful) {
                val data = response.body()?.data
                // ENFORCE DIRECT URL: SerpApi requires a direct image file stream (i.ibb.co)
                // data.url is the raw original file. data.display_url is often a web-viewer.
                val rawUrl = data?.url 
                val displayUrl = data?.display_url
                
                Log.d("NetworkDebug", "ImgBB Raw URL: $rawUrl")
                Log.d("NetworkDebug", "ImgBB Display URL: $displayUrl")
                
                val finalUrl = rawUrl ?: displayUrl
                Log.d("NetworkDebug", "Final URL selected for SerpApi: $finalUrl")
                finalUrl
            } else {
                Log.e("NetworkDebug", "ImgBB Upload Failed: ${response.code()} ${response.errorBody()?.string()}")
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
