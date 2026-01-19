package com.facealbum.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SimilarityMatcherTest {

    @Test
    fun `cosineSimilarity returns 1 for identical vectors`() {
        val vector = floatArrayOf(1f, 2f, 3f, 4f, 5f)

        val similarity = SimilarityMatcher.cosineSimilarity(vector, vector)

        assertThat(similarity).isWithin(0.0001f).of(1f)
    }

    @Test
    fun `cosineSimilarity returns 0 for orthogonal vectors`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)

        val similarity = SimilarityMatcher.cosineSimilarity(a, b)

        assertThat(similarity).isWithin(0.0001f).of(0f)
    }

    @Test
    fun `cosineSimilarity returns -1 for opposite vectors`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(-1f, 0f, 0f)

        val similarity = SimilarityMatcher.cosineSimilarity(a, b)

        assertThat(similarity).isWithin(0.0001f).of(-1f)
    }

    @Test
    fun `cosineSimilarity handles normalized vectors correctly`() {
        // Two unit vectors at ~45 degrees
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0.707f, 0.707f, 0f)

        val similarity = SimilarityMatcher.cosineSimilarity(a, b)

        // cos(45) ~= 0.707
        assertThat(similarity).isWithin(0.01f).of(0.707f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cosineSimilarity throws for different dimensions`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(1f, 2f)

        SimilarityMatcher.cosineSimilarity(a, b)
    }

    @Test
    fun `isMatch returns true when above threshold`() {
        val candidate = floatArrayOf(1f, 0f, 0f)
        val seeds = listOf(floatArrayOf(0.9f, 0.1f, 0f))

        val (isMatch, similarity) = SimilarityMatcher.isMatch(candidate, seeds, 0.8f)

        assertThat(isMatch).isTrue()
        assertThat(similarity).isGreaterThan(0.8f)
    }

    @Test
    fun `isMatch returns false when below threshold`() {
        val candidate = floatArrayOf(1f, 0f, 0f)
        val seeds = listOf(floatArrayOf(0f, 1f, 0f))

        val (isMatch, similarity) = SimilarityMatcher.isMatch(candidate, seeds, 0.5f)

        assertThat(isMatch).isFalse()
        assertThat(similarity).isLessThan(0.5f)
    }

    @Test
    fun `isMatch returns false for empty seeds`() {
        val candidate = floatArrayOf(1f, 0f, 0f)
        val seeds = emptyList<FloatArray>()

        val (isMatch, similarity) = SimilarityMatcher.isMatch(candidate, seeds, 0.5f)

        assertThat(isMatch).isFalse()
        assertThat(similarity).isEqualTo(0f)
    }

    @Test
    fun `isMatch returns max similarity across multiple seeds`() {
        val candidate = floatArrayOf(1f, 0f, 0f)
        val seeds = listOf(
            floatArrayOf(0f, 1f, 0f),  // orthogonal, similarity ~0
            floatArrayOf(0.8f, 0.6f, 0f),  // closer, similarity ~0.8
            floatArrayOf(0.5f, 0.5f, 0.707f)  // medium, similarity ~0.5
        )

        val (isMatch, similarity) = SimilarityMatcher.isMatch(candidate, seeds, 0.7f)

        assertThat(isMatch).isTrue()
        assertThat(similarity).isWithin(0.05f).of(0.8f)
    }

    @Test
    fun `isMatch with exact threshold returns true`() {
        val candidate = floatArrayOf(1f, 0f, 0f)
        val seed = floatArrayOf(1f, 0f, 0f)  // identical, similarity = 1.0

        val (isMatch, _) = SimilarityMatcher.isMatch(candidate, listOf(seed), 1.0f)

        assertThat(isMatch).isTrue()
    }
}
