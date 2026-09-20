package com.yourcompany.facesearch.domain

import kotlin.math.exp

data class IntelligenceEvidence(
    val faceCosineSimilarity: Float,     // Range: [0.0, 1.0]
    val handleMatchScore: Float,         // Range: [0.0, 1.0]
    val keywordCoOccurrenceCount: Int,   // Number of matching contextual hits (e.g., city, school)
    val hasVerifiedMetadata: Boolean     // Bonus weight flag
)

data class EvaluatedLead(
    val targetIdentifier: String,
    val finalProbability: Float,
    val confidenceTier: String
)

class ConfidenceScoringEngine {

    companion object {
        // Tunable weights summing to 1.0
        private const val WEIGHT_FACE = 0.50f
        private const val WEIGHT_HANDLE = 0.30f
        private const val WEIGHT_CONTEXT = 0.20f
    }

    fun evaluateLead(identifier: String, evidence: IntelligenceEvidence): EvaluatedLead {
        // 1. Normalize facial similarity score
        val normalizedFace = evidence.faceCosineSimilarity.coerceIn(0f, 1f)

        // 2. Normalize handle match confidence
        val normalizedHandle = evidence.handleMatchScore.coerceIn(0f, 1f)

        // 3. Compute sigmoid-based contextual co-occurrence probability
        // More keyword matches asymptotically scale context score towards 1.0
        val contextScore = (1.0f / (1.0f + exp(-0.8f * (evidence.keywordCoOccurrenceCount - 2)))).toFloat()

        // 4. Weighted multi-factor aggregation
        var aggregateScore = (normalizedFace * WEIGHT_FACE) +
                             (normalizedHandle * WEIGHT_HANDLE) +
                             (contextScore * WEIGHT_CONTEXT)

        // Apply metadata corroboration boost if verified
        if (evidence.hasVerifiedMetadata) {
            aggregateScore = (aggregateScore * 1.15f).coerceAtMost(1.0f)
        }

        val tier = when {
            aggregateScore >= 0.85f -> "CONFIRMED_MATCH"
            aggregateScore >= 0.60f -> "PROBABLE_LEAD"
            aggregateScore >= 0.35f -> "POSSIBLE_CORRELATION"
            else -> "LOW_CONFIDENCE_NOISE"
        }

        return EvaluatedLead(
            targetIdentifier = identifier,
            finalProbability = aggregateScore,
            confidenceTier = tier
        )
    }
}
