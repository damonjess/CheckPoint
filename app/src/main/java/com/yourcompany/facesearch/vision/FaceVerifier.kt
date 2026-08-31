package com.yourcompany.facesearch.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class FaceVerifier(context: Context) {
    private val faceEmbedder = FaceEmbedder(context)
    private val faceCropper = NativeFaceCropper()
    
    companion object {
        // Lowered from 0.68f to 0.58f to accept more highly-probable look-alikes
        const val VERIFICATION_THRESHOLD = 0.58f
    }

    suspend fun verifyFaceMatch(
        searchResultBitmap: Bitmap,
        sourceEmbedding: FloatArray?
    ): Float? = calculateSimilarity(searchResultBitmap, sourceEmbedding)
        ?.takeIf { it >= VERIFICATION_THRESHOLD }

    suspend fun calculateSimilarity(
        searchResultBitmap: Bitmap,
        sourceEmbedding: FloatArray?
    ): Float? = calculateSimilarityAndEmbedding(searchResultBitmap, sourceEmbedding)?.first

    suspend fun calculateSimilarityAndEmbedding(
        searchResultBitmap: Bitmap,
        sourceEmbedding: FloatArray?
    ): Pair<Float, FloatArray>? {
        if (sourceEmbedding == null) return null

        return withContext(Dispatchers.Default) {
            try {
                val safeBitmap = ensureSoftwareBitmap(searchResultBitmap)
                val resultFace = faceCropper.getTightFaceCrop(safeBitmap) ?: return@withContext null
                val resultEmbedding = faceEmbedder.getEmbedding(resultFace) ?: return@withContext null
                val similarity = FaceMatcherExt.cosineSimilarity(sourceEmbedding, resultEmbedding)
                Pair(similarity, resultEmbedding)
            } catch (e: Exception) {
                android.util.Log.e("FaceVerifier", "Error comparing face: ${e.message}")
                null
            }
        }
    }

    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.config != null && bitmap.config != Bitmap.Config.HARDWARE) {
            return bitmap
        }
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            val bytes = stream.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: bitmap
        } catch (_: Exception) {
            bitmap
        }
    }

    fun close() {
        faceEmbedder.close()
        faceCropper.release()
    }
}

object FaceMatcherExt {
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding size mismatch: ${a.size} vs ${b.size}" }
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)).coerceAtLeast(1e-8f)
        return dot / denom
    }
}
