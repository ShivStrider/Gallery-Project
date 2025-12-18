package com.facealbum.domain

import kotlin.math.sqrt

/**
 * Utility for comparing face embeddings using cosine similarity.
 */
object SimilarityMatcher {

    /**
     * Calculate cosine similarity between two embeddings.
     *
     * @param a First embedding vector
     * @param b Second embedding vector
     * @return Cosine similarity score (0.0 to 1.0, higher = more similar)
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding dimensions must match" }

        var dot = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0) dot / denominator else 0f
    }

    /**
     * Check if a candidate embedding matches any of the seed embeddings.
     *
     * @param candidateEmbedding Embedding to test
     * @param seedEmbeddings List of reference embeddings from seed photos
     * @param threshold Minimum similarity score to be considered a match
     * @return Pair of (isMatch, maxSimilarity)
     */
    fun isMatch(
        candidateEmbedding: FloatArray,
        seedEmbeddings: List<FloatArray>,
        threshold: Float
    ): Pair<Boolean, Float> {
        if (seedEmbeddings.isEmpty()) {
            return Pair(false, 0f)
        }

        // Match if similar to ANY seed (handles different angles/lighting)
        val maxSimilarity = seedEmbeddings.maxOf { seed ->
            cosineSimilarity(candidateEmbedding, seed)
        }

        return Pair(maxSimilarity >= threshold, maxSimilarity)
    }
}
