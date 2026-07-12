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
        onUpdate: (progress: Float, log: String) -> Unit
    ): FaceSearchOutcome = withContext(Dispatchers.IO) {
        try {
            if (ApiClient.API_KEY.isBlank()) {
                return@withContext FaceSearchOutcome.ApiError(401, "API Key is missing in Secrets.kt")
            }

            onUpdate(0.05f, "Initializing Sherlock Secure Connection...")
            delay(500)
            onUpdate(0.10f, "Encrypting biometric data packet...")

            // Step 1: Upload the face image
            val multipart = bitmapToMultipart(faceBitmap)
            onUpdate(0.15f, "Uploading probe image to global neural network...")
            
            val uploadResponse = try {
                api.uploadFace(ApiClient.API_KEY, multipart)
            } catch (e: Exception) {
                return@withContext FaceSearchOutcome.NetworkError(e)
            }

            if (!uploadResponse.isSuccessful) {
                return@withContext FaceSearchOutcome.ApiError(
                    uploadResponse.code(),
                    uploadResponse.errorBody()?.string() ?: "Upload failed"
                )
            }

            val searchId = uploadResponse.body()?.id_search ?: 
                return@withContext FaceSearchOutcome.UnknownError(Exception("No search ID returned"))

            onUpdate(0.20f, "Probe received. ID: ${searchId.take(8)}...")
            onUpdate(0.25f, "Bypassing social media scrap-filters...")

            // Step 2: Poll for results
            // Increased to 100 attempts * 3s = 300 seconds (5 minutes)
            val maxAttempts = 100
            var attempts = 0
            var lastStatus = ""
            
            while (attempts < maxAttempts) {
                delay(3000) 
                attempts++
                
                val pollingProgress = 0.25f + (0.70f * (attempts.toFloat() / maxAttempts))
                
                val response = try {
                    api.getSearchResults(
                        ApiClient.API_KEY, 
                        SearchRequest(id_search = searchId, testing_mode = true)
                    )
                } catch (e: IOException) {
                    onUpdate(pollingProgress, "Network jitter detected. Re-establishing link...")
                    continue // Try again
                }

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        val currentStatus = body.status ?: "processing"
                        if (currentStatus != lastStatus) {
                            onUpdate(pollingProgress, "Engine Status: ${currentStatus.uppercase()}")
                            lastStatus = currentStatus
                        }

                        if (body.message != null && body.message.isNotBlank()) {
                            onUpdate(pollingProgress, "Remote: ${body.message}")
                        }

                        if (!body.results.isNullOrEmpty()) {
                            onUpdate(1.0f, "Matches recovered. Finalizing report...")
                            return@withContext FaceSearchOutcome.Success(body.results)
                        }
                        
                        if (currentStatus.lowercase() == "completed") {
                            onUpdate(1.0f, "Scan finished. Generating results...")
                            return@withContext if (body.results.isNullOrEmpty()) {
                                FaceSearchOutcome.NoMatches
                            } else {
                                FaceSearchOutcome.Success(body.results)
                            }
                        }
                    }
                } else if (response.code() == 429) {
                    onUpdate(pollingProgress, "Rate limit reached. Cooling down engine...")
                    delay(5000)
                }
            }

            FaceSearchOutcome.ApiError(408, "Deep Search reached max duration (5m). Please check history later.")

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
