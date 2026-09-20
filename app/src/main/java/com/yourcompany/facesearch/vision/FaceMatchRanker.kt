package com.yourcompany.facesearch.vision

/**
 * Ranks candidates using local face evidence rather than blindly trusting
 * search-engine percentages.
 *
 * Provider scores are deliberately kept separate from face similarity.
 */
object FaceMatchRanker {

    data class CandidateEvidence(
        val faceSimilarity: Float?,
        val providerCount: Int = 1,
        val hasFace: Boolean = true,
        val faceQuality: Float = 1f,
        val duplicateCount: Int = 1,
        val exactImageMatch: Boolean = false,
        val secondaryAlignmentScore: Float = 0.85f
    )

    data class RankedResult(
        val score: Float,
        val faceSimilarity: Float?,
        val verified: Boolean,
        val reviewable: Boolean
    )

    const val VERIFIED_THRESHOLD = 0.68f
    const val REVIEW_THRESHOLD = 0.50f

    /**
     * Produces a 0..1 ranking score.
     *
     * This is a ranking value, NOT a probability that two images depict
     * the same person.
     */
    fun rank(
        evidence: CandidateEvidence
    ): RankedResult {

        val similarity =
            evidence.faceSimilarity?.coerceIn(0f, 1f) ?: 0f

        if (!evidence.hasFace) {
            return RankedResult(
                score = 0f,
                faceSimilarity = evidence.faceSimilarity,
                verified = false,
                reviewable = false
            )
        }

        /*
         * BUILD 4: Multi-signal integration combining primary similarity and secondary alignment.
         */
        val effectiveSimilarity = if (evidence.secondaryAlignmentScore < 0.90f && similarity in 0.68f..0.74f) {
            similarity - 0.04f
        } else {
            similarity
        }

        var score = effectiveSimilarity * 0.75f + evidence.secondaryAlignmentScore * 0.05f

        /*
         * A usable face is stronger than a provider result that contains
         * no detectable face.
         */
        score += evidence.faceQuality
            .coerceIn(0f, 1f) * 0.10f

        /*
         * Agreement between independent providers adds supporting evidence,
         * but can never overpower weak face similarity.
         */
        val providerBonus =
            ((evidence.providerCount - 1)
                .coerceAtLeast(0)
                .coerceAtMost(4)) * 0.025f

        score += providerBonus

        /*
         * Multiple copies of the same result are supporting evidence, but
         * are capped so duplicate scraping cannot artificially dominate.
         */
        val duplicateBonus =
            ((evidence.duplicateCount - 1)
                .coerceAtLeast(0)
                .coerceAtMost(4)) * 0.01f

        score += duplicateBonus

        /*
         * Exact-image occurrence is useful evidence that the image itself
         * appears elsewhere, but it does not prove person identity.
         */
        if (evidence.exactImageMatch) {
            score += 0.04f
        }

        score = score.coerceIn(0f, 1f)

        return RankedResult(
            score = score,
            faceSimilarity = evidence.faceSimilarity,
            verified = similarity >= VERIFIED_THRESHOLD,
            reviewable = similarity >= REVIEW_THRESHOLD
        )
    }

    /**
     * Sort strongest evidence first.
     */
    fun sort(
        results: List<Pair<Int, CandidateEvidence>>
    ): List<Pair<Int, RankedResult>> {

        return results
            .map { (index, evidence) ->
                index to rank(evidence)
            }
            .sortedByDescending {
                it.second.score
            }
    }

    /**
     * Converts a local similarity to a display percentage.
     *
     * This is only a similarity display, not an identity probability.
     */
    fun similarityPercent(
        similarity: Float?
    ): Int {

        if (similarity == null) {
            return 0
        }

        return (
            similarity
                .coerceIn(0f, 1f) * 100f
        ).toInt()
    }

    /**
     * Human-readable verification state.
     */
    fun state(
        similarity: Float?
    ): State {

        val value = similarity ?: 0f

        return when {
            value >= VERIFIED_THRESHOLD ->
                State.VERIFIED

            value >= REVIEW_THRESHOLD ->
                State.POSSIBLE

            value >= 0.35f ->
                State.WEAK

            else ->
                State.REJECTED
        }
    }

    enum class State {
        VERIFIED,
        POSSIBLE,
        WEAK,
        REJECTED
    }
}
