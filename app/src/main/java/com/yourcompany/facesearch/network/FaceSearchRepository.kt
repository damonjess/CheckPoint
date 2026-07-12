package com.yourcompany.facesearch.network

import android.graphics.Bitmap
import com.yourcompany.facesearch.network.model.WebMatch
import com.yourcompany.facesearch.network.model.FaceSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /**
     * Performs a real internet search using the FaceCheck.id API flow.
     * 1. Upload the image to get a Search ID.
     * 2. Poll the search endpoint until results are ready.
     */
    suspend fun searchByFace(
        faceBitmap: Bitmap,
        onProgress: (Float) -> Unit
    ): FaceSearchOutcome = withContext(Dispatchers.IO) {
        try {
            if (ApiClient.API_KEY.isBlank()) {
                return@withContext FaceSearchOutcome.ApiError(401, "API Key is missing in Secrets.kt")
            }

            onProgress(0.05f)

            // Step 1: Upload the face image
            val multipart = bitmapToMultipart(faceBitmap)
            val uploadResponse = api.uploadFace(ApiClient.API_KEY, multipart)

            if (!uploadResponse.isSuccessful) {
                return@withContext FaceSearchOutcome.ApiError(
                    uploadResponse.code(),
                    uploadResponse.errorBody()?.string() ?: "Upload failed"
                )
            }

            val searchId = uploadResponse.body()?.id_search ?: 
                return@withContext FaceSearchOutcome.UnknownError(Exception("No search ID returned"))

            onProgress(0.15f)

            // Step 2: Poll for results (Internet searches take time)
            // Increased max attempts to 60 (with 3s delay = ~180 seconds / 3 minutes total)
            // Internet-wide facial recognition scans can be very intensive.
            val maxAttempts = 60
            var attempts = 0
            while (attempts < maxAttempts) {
                delay(3000) 
                attempts++
                
                // Calculate progress from 15% to 98%
                val pollingProgress = 0.15f + (0.83f * (attempts.toFloat() / maxAttempts))
                onProgress(pollingProgress)

                val response = api.getSearchResults(
                    ApiClient.API_KEY, 
                    SearchRequest(id_search = searchId, testing_mode = true)
                )

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        // In some cases, results might be delivered piece-meal or only at the end.
                        if (!body.results.isNullOrEmpty()) {
                            onProgress(1.0f)
                            return@withContext FaceSearchOutcome.Success(body.results)
                        }
                        
                        // Check if the search is officially finished
                        if (body.status?.lowercase() == "completed") {
                            if (body.results.isNullOrEmpty()) {
                                return@withContext FaceSearchOutcome.NoMatches
                            } else {
                                return@withContext FaceSearchOutcome.Success(body.results)
                            }
                        }
                        
                        // If the API returns a specific error message in the body
                        if (body.message != null && body.status?.lowercase() == "error") {
                            return@withContext FaceSearchOutcome.ApiError(response.code(), body.message)
                        }
                    }
                } else if (response.code() != 404 && response.code() != 429) {
                    // If we get a hard error that isn't a "Not Found" (still processing) 
                    // or "Too Many Requests" (rate limit), we should probably stop.
                    return@withContext FaceSearchOutcome.ApiError(
                        response.code(),
                        response.errorBody()?.string() ?: "Server error during polling"
                    )
                }
            }

            FaceSearchOutcome.ApiError(408, "Deep Search timed out. The internet is large, and the engines are busy. Please try again in a moment.")

        } catch (e: IOException) {
            FaceSearchOutcome.NetworkError(e)
        } catch (e: Exception) {
            FaceSearchOutcome.UnknownError(e)
        }
    }

    private fun bitmapToMultipart(
        bitmap: Bitmap,
        quality: Int = 90,
        partName: String = "images",
        fileName: String = "face.jpg"
    ): MultipartBody.Part {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val byteArray = stream.toByteArray()

        val requestBody = byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
        return MultipartBody.Part.createFormData(partName, fileName, requestBody)
    }
}
