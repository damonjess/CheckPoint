package com.yourcompany.facesearch.network

import android.graphics.Bitmap
import com.yourcompany.facesearch.network.model.WebMatch
import com.yourcompany.facesearch.network.model.FaceSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException

sealed class FaceSearchOutcome {
    data class Success(val matches: List<WebMatch>) : FaceSearchOutcome()
    object NoMatches : FaceSearchOutcome()
    data class ApiError(val code: Int, val message: String) : FaceSearchOutcome()
    data class NetworkError(val exception: Exception) : FaceSearchOutcome()
    data class UnknownError(val exception: Exception) : FaceSearchOutcome()
}

class FaceSearchRepository(
    private val api: FaceSearchApi = ApiClient.faceSearchApi
) {

    suspend fun searchByFace(faceBitmap: Bitmap): FaceSearchOutcome = withContext(Dispatchers.IO) {
        try {
            val multipart = bitmapToMultipart(faceBitmap)
            val response = api.searchByFace(multipart)

            if (!response.isSuccessful) {
                return@withContext FaceSearchOutcome.ApiError(
                    code = response.code(),
                    message = response.errorBody()?.string() ?: "Unknown server error"
                )
            }

            val body = response.body()

            when {
                body == null -> FaceSearchOutcome.UnknownError(IllegalStateException("Empty response body"))
                !body.matchFound || body.results.isNullOrEmpty() -> FaceSearchOutcome.NoMatches
                else -> FaceSearchOutcome.Success(body.results)
            }
        } catch (e: IOException) {
            FaceSearchOutcome.NetworkError(e)
        } catch (e: Exception) {
            FaceSearchOutcome.UnknownError(e)
        }
    }

    /**
     * Compresses the bitmap to JPEG in-memory and wraps it as a Multipart part.
     * Avoids writing to disk unless you specifically need a File reference.
     */
    private fun bitmapToMultipart(
        bitmap: Bitmap,
        quality: Int = 90,
        partName: String = "image",
        fileName: String = "face.jpg"
    ): MultipartBody.Part {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val byteArray = stream.toByteArray()

        val requestBody = byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
        return MultipartBody.Part.createFormData(partName, fileName, requestBody)
    }
}
