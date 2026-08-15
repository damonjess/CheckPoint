package com.yourcompany.facesearch.vision

import com.yourcompany.facesearch.data.EnrolledFace
import kotlin.math.sqrt

object FaceMatcher {

    const val DEFAULT_MATCH_THRESHOLD = 0.58f

    data class MatchResult(val face: EnrolledFace, val similarity: Float)

    fun findBestMatch(
        embedding: FloatArray, 
        enrolledFaces: List<EnrolledFace>,
        threshold: Float = DEFAULT_MATCH_THRESHOLD
    ): MatchResult? {
        var best: MatchResult? = null

        for (enrolled in enrolledFaces) {
            val similarity = cosineSimilarity(embedding, enrolled.embedding)
            if (best == null || similarity > best.similarity) {
                best = MatchResult(enrolled, similarity)
            }
        }

        return best?.takeIf { it.similarity >= threshold }
    }

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
        val denom = (sqrt(normA) * sqrt(normB)).coerceAtLeast(1e-8f)
        return dot / denom
    }
}