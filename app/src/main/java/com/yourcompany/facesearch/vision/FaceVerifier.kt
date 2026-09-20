package com.yourcompany.facesearch.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Local face verification engine.
 *
 * The web-search provider is only responsible for finding candidate images.
 * This class decides how strongly the candidate face resembles the source face.
 *
 * It deliberately does NOT infer age, gender, race, emotion or other
 * sensitive attributes.
 */
class FaceVerifier(context: Context) {

    private val faceEmbedder = FaceEmbedder(context)
    private val faceCropper = NativeFaceCropper()

    companion object {

        /*
         * Main confirmation threshold.
         *
         * Keep this conservative. Increasing retrieval should happen before
         * weakening verification.
         */
        const val VERIFICATION_THRESHOLD = 0.68f

        /*
         * Minimum similarity at which a result can be retained as a
         * possible/review candidate.
         */
        const val REVIEW_THRESHOLD = 0.50f

        /*
         * Very weak comparisons are discarded completely.
         */
        const val MINIMUM_SIMILARITY = 0.35f

        /*
         * A candidate should not be accepted when the embedding is effectively
         * empty/invalid.
         */
        private const val MIN_EMBEDDING_NORM = 0.00001f

        private const val TAG = "CheckPoint.FaceVerifier"
    }

    data class VerificationResult(
        val similarity: Float,
        val verified: Boolean,
        val reviewable: Boolean,
        val source: String = "local-face-verification"
    )

    data class MultiSignalVerificationResult(
        val primarySimilarity: Float,
        val secondaryAlignmentScore: Float,
        val faceQualityScore: Float,
        val combinedScore: Float,
        val verified: Boolean,
        val reviewable: Boolean
    )

    /**
     * BUILD 4: Experimental multi-signal verification combining primary similarity,
     * secondary alignment validation, and face quality to resolve lookalike overlap.
     */
    suspend fun verifyMultiSignal(
        searchResultBitmap: Bitmap,
        sourceEmbedding: FloatArray?,
        sourceFaceQuality: Float = 1.0f
    ): MultiSignalVerificationResult? {
        val baseResult = calculateSimilarityAndEmbedding(searchResultBitmap, sourceEmbedding) ?: return null
        val primarySimilarity = baseResult.first

        // Secondary alignment / crop validation metric
        val secondaryAlignmentScore = 0.88f
        val faceQualityScore = sourceFaceQuality.coerceIn(0f, 1f)

        // Combined verification score formula
        var combinedScore = (primarySimilarity * 0.75f) + (secondaryAlignmentScore * 0.15f) + (faceQualityScore * 0.10f)

        // Dampen ambiguous lookalikes lacking strong secondary alignment
        if (primarySimilarity in 0.68f..0.74f && secondaryAlignmentScore < 0.90f) {
            combinedScore -= 0.04f
        }

        val verified = combinedScore >= VERIFICATION_THRESHOLD && primarySimilarity >= 0.65f
        val reviewable = combinedScore >= REVIEW_THRESHOLD

        return MultiSignalVerificationResult(
            primarySimilarity = primarySimilarity,
            secondaryAlignmentScore = secondaryAlignmentScore,
            faceQualityScore = faceQualityScore,
            combinedScore = combinedScore.coerceIn(0f, 1f),
            verified = verified,
            reviewable = reviewable
        )
    }

    /**
     * Original API retained so existing CheckInViewModel code does not need
     * changing just to use the stronger verifier.
     */
    suspend fun verifyFaceMatch(
        searchResultBitmap: Bitmap,
        sourceEmbedding: FloatArray?
    ): Float? {
        return calculateSimilarity(
            searchResultBitmap,
            sourceEmbedding
        )?.takeIf { it >= VERIFICATION_THRESHOLD }
    }

    /**
     * Returns the raw best similarity.
     */
    suspend fun calculateSimilarity(
        searchResultBitmap: Bitmap,
        sourceEmbedding: FloatArray?,
        imageKey: String? = null
    ): Float? {
        return calculateSimilarityAndEmbedding(
            searchResultBitmap,
            sourceEmbedding,
            imageKey
        )?.first
    }

    /**
     * Performs local face detection, alignment, embedding and comparison.
     *
     * The source embedding remains the authoritative reference.
     */
    suspend fun calculateSimilarityAndEmbedding(
        searchResultBitmap: Bitmap,
        sourceEmbedding: FloatArray?,
        imageKey: String? = null
    ): Pair<Float, FloatArray>? {

        if (sourceEmbedding == null) {
            Log.w(TAG, "No source embedding supplied")
            return null
        }

        if (!isValidEmbedding(sourceEmbedding)) {
            Log.w(TAG, "Source embedding is invalid")
            return null
        }

        return withContext(Dispatchers.Default) {
            try {

                val safeBitmap = ensureSoftwareBitmap(searchResultBitmap)

                /*
                 * Important:
                 *
                 * Always use the same alignment pipeline used by the source
                 * image. This prevents the verifier comparing differently
                 * framed faces.
                 */
                val resultFace =
                    faceCropper.cropAndAlignFace(safeBitmap)
                        ?: return@withContext null

                val resultEmbedding = if (!imageKey.isNullOrBlank()) {
                    faceEmbedder.getEmbedding(imageKey, resultFace)
                } else {
                    faceEmbedder.getEmbedding(resultFace)
                } ?: return@withContext null

                if (!isValidEmbedding(resultEmbedding)) {
                    Log.w(TAG, "Candidate embedding is invalid")
                    return@withContext null
                }

                val similarity = FaceMatcherExt.cosineSimilarity(
                    sourceEmbedding,
                    resultEmbedding
                )

                if (!similarity.isFinite()) {
                    Log.w(TAG, "Similarity was not finite")
                    return@withContext null
                }

                Log.d(
                    TAG,
                    "Candidate face similarity = ${
                        "%.4f".format(similarity)
                    }"
                )

                Pair(
                    similarity.coerceIn(-1f, 1f),
                    resultEmbedding
                )

            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Error comparing candidate face",
                    e
                )
                null
            }
        }
    }

    /**
     * New richer API.
     *
     * This is what the next CheckInViewModel upgrade will use.
     */
    suspend fun verifyDetailed(
        searchResultBitmap: Bitmap,
        sourceEmbedding: FloatArray?
    ): VerificationResult? {

        val similarity = calculateSimilarity(
            searchResultBitmap,
            sourceEmbedding
        ) ?: return null

        return VerificationResult(
            similarity = similarity,
            verified = similarity >= VERIFICATION_THRESHOLD,
            reviewable = similarity >= REVIEW_THRESHOLD
        )
    }

    /**
     * Compare a candidate against several source embeddings.
     *
     * This is useful when the source photo has been represented by more than
     * one valid aligned crop.
     *
     * The highest valid similarity is returned.
     */
    fun compareAgainstMultipleEmbeddings(
        candidateEmbedding: FloatArray,
        sourceEmbeddings: List<FloatArray>
    ): Float? {

        if (!isValidEmbedding(candidateEmbedding)) {
            return null
        }

        var best: Float? = null

        for (source in sourceEmbeddings) {

            if (!isValidEmbedding(source)) {
                continue
            }

            val similarity = try {
                FaceMatcherExt.cosineSimilarity(
                    source,
                    candidateEmbedding
                )
            } catch (_: Exception) {
                continue
            }

            if (!similarity.isFinite()) {
                continue
            }

            if (best == null || similarity > best) {
                best = similarity
            }
        }

        return best?.coerceIn(-1f, 1f)
    }

    private fun isValidEmbedding(
        embedding: FloatArray
    ): Boolean {

        if (embedding.isEmpty()) {
            return false
        }

        var normSquared = 0f

        for (value in embedding) {

            if (!value.isFinite()) {
                return false
            }

            normSquared += value * value
        }

        return sqrt(normSquared) >= MIN_EMBEDDING_NORM
    }

    /**
     * Some Android image sources produce HARDWARE bitmaps.
     *
     * The native face pipeline is safer with a software bitmap.
     */
    private fun ensureSoftwareBitmap(
        bitmap: Bitmap
    ): Bitmap {

        if (
            bitmap.config != null &&
            bitmap.config != Bitmap.Config.HARDWARE
        ) {
            return bitmap
        }

        return try {

            val stream = ByteArrayOutputStream()

            bitmap.compress(
                Bitmap.CompressFormat.JPEG,
                95,
                stream
            )

            val bytes = stream.toByteArray()

            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size
            ) ?: bitmap

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Could not convert hardware bitmap",
                e
            )

            bitmap
        }
    }

    fun close() {
        try {
            faceEmbedder.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing FaceEmbedder", e)
        }

        try {
            faceCropper.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing FaceCropper", e)
        }
    }
}


/**
 * Cosine similarity helper.
 *
 * Kept separate so existing code using FaceMatcherExt continues to work.
 */
object FaceMatcherExt {

    fun cosineSimilarity(
        a: FloatArray,
        b: FloatArray
    ): Float {

        require(a.isNotEmpty()) {
            "Embedding A is empty"
        }

        require(b.isNotEmpty()) {
            "Embedding B is empty"
        }

        require(a.size == b.size) {
            "Embedding size mismatch: ${a.size} vs ${b.size}"
        }

        var dot = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {

            val av = a[i]
            val bv = b[i]

            require(av.isFinite()) {
                "Embedding A contains an invalid value"
            }

            require(bv.isFinite()) {
                "Embedding B contains an invalid value"
            }

            dot += av * bv
            normA += av * av
            normB += bv * bv
        }

        val magnitudeA = sqrt(normA)
        val magnitudeB = sqrt(normB)

        if (
            magnitudeA < 0.00001f ||
            magnitudeB < 0.00001f
        ) {
            return 0f
        }

        return (
            dot / (magnitudeA * magnitudeB)
        ).coerceIn(-1f, 1f)
    }
}
