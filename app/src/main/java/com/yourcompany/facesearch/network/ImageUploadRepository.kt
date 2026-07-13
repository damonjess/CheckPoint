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
                response.body()?.data?.url
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
