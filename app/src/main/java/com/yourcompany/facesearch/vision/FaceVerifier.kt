package com.yourcompany.facesearch.vision

import android.graphics.Bitmap
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Verifies search result accuracy by comparing face embeddings.
 * Filters out false positive matches.
 */
class FaceVerifier(context: Context) {
    private val faceEmbedder = FaceEmbedder(context)
    private val faceCropper = NativeFaceCropper()
    
    companion object {
        // Very strict threshold for verifying matches
        const val VERIFICATION_THRESHOLD = 0.62f
        
        // Looser threshold for soft filtering (show as less confident)
        const val SOFT_FILTER_THRESHOLD = 0.50f
    }

    /**
     * Verifies if a face in a search result matches the source face.
     * Returns confidence score (0-1), or null if verification failed.
     */
    suspend fun verifyFaceMatch(
        searchResultBitmap: Bitmap,
        sourceEmbedding: FloatArray?
    ): Float? {
        if (sourceEmbedding == null) return null
        
        return withContext(Dispatchers.Default) {
            try {
                // 1. Detect and extract face from search result
                val resultFace = faceCropper.getTightFaceCrop(searchResultBitmap) ?: return@withContext null
                
                // 2. Generate embedding for result
                val resultEmbedding = faceEmbedder.getEmbedding(resultFace)
                
                // Clean up
                if (resultFace != searchResultBitmap) {
                    resultFace.recycle()
                }

                if (resultEmbedding == null) return@withContext null
                
                // 3. Compare embeddings
                val similarity = FaceMatcher.cosineSimilarity(sourceEmbedding, resultEmbedding)
                
                similarity.takeIf { it > SOFT_FILTER_THRESHOLD }
            } catch (e: Exception) {
                android.util.Log.e("FaceVerifier", "Error verifying face: ${e.message}")
                null
            }
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
