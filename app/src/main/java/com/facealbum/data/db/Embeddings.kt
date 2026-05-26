package com.facealbum.data.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Helpers to round-trip a 512-dim FloatArray as the ByteArray we persist in Room.
 * Little-endian, 4 bytes per float.
 */
object Embeddings {
    fun toBytes(values: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buf.putFloat(v)
        return buf.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) out[i] = buf.float
        return out
    }
}
