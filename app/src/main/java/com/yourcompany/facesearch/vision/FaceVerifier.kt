package com.yourcompany.facesearch.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Verifies search result accuracy by comparing face embeddings.
 * Filters out false positive matches.
 */
class FaceVerifier(context: Context) {
    private val faceEmbedder = FaceEmbedder(context)
    private val faceCropper = NativeFaceCropper()
    
    companion object {
        // This is intentionally conservative: engine ranking is not identity evidence.
        const val VERIFICATION_THRESHOLD = 0.68f
    }

    /**
     * Verifies if a face in a search result matches the source face.
     * Returns confidence score (0-1), or null if verification failed.
     * 
     * FIX: Converts hardware bitmaps to software bitmaps for pixel access.
     */
    suspend fun verifyFaceMatch(
        searchResultBitmap: Bitmap,
        sourceEmbedding: FloatArray?
    ): Float? = calculateSimilarity(searchResultBitmap, sourceEmbedding)
        ?.takeIf { it >= VERIFICATION_THRESHOLD }

    /**
     * Returns the raw embedding similarity for an already face-bearing result.
     * Callers must keep the conservative [VERIFICATION_THRESHOLD] for confirmed
     * matches; lower values are suitable only for visibly labelled review leads.
     */
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

    /**
     * Converts a hardware bitmap to a software bitmap for pixel access.
     */
    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
        // If it's already a software bitmap, return it
        if (bitmap.config != null && bitmap.config != Bitmap.Config.HARDWARE) {
            return bitmap
        }
        
        // Convert to software bitmap by copying
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            val bytes = stream.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: bitmap
        } catch (e: Exception) {
            android.util.Log.e("FaceVerifier", "Failed to convert hardware bitmap", e)
            bitmap
        }
    }

    fun close() {
        faceEmbedder.close()
        faceCropper.release()
    }
}

// Extension to FaceMatcher for public access to cosine similarity
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
