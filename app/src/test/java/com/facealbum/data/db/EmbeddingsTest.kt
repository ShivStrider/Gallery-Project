package com.facealbum.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random

/**
 * The BLOB codec under every stored embedding and centroid. A silent bug here
 * corrupts all grouping, so the round-trip contract is pinned down exactly.
 */
class EmbeddingsTest {

    @Test
    fun `round-trip preserves every float bit-exactly`() {
        val rand = Random(42L)
        val original = FloatArray(512) { rand.nextFloat() * 2f - 1f }

        val restored = Embeddings.fromBytes(Embeddings.toBytes(original))

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `encoding is little-endian 4 bytes per float`() {
        val bytes = Embeddings.toBytes(floatArrayOf(1.0f))
        // IEEE-754 1.0f = 0x3F800000, little-endian on disk: 00 00 80 3F
        assertThat(bytes).isEqualTo(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F))
    }

    @Test
    fun `special values survive the trip`() {
        val original = floatArrayOf(
            0f, -0f, Float.MIN_VALUE, Float.MAX_VALUE,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY
        )
        val restored = Embeddings.fromBytes(Embeddings.toBytes(original))
        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `empty array round-trips`() {
        assertThat(Embeddings.fromBytes(Embeddings.toBytes(FloatArray(0)))).isEmpty()
    }

    @Test
    fun `byte length is four times the dimension`() {
        assertThat(Embeddings.toBytes(FloatArray(512))).hasLength(2048)
    }
}
