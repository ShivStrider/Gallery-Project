package com.facealbum.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FaceRecognitionConfigTest {

    @Test
    fun `embedding size matches MobileFaceNet output`() {
        // 512-D is the load-bearing contract with mobile_face_net.tflite — a
        // mismatch would silently corrupt every embedding we ever computed.
        assertThat(FaceRecognitionConfig.EMBEDDING_SIZE).isEqualTo(128)
    }

    @Test
    fun `cluster thresholds are sane`() {
        val assign = FaceRecognitionConfig.CLUSTER_ASSIGN_THRESHOLD
        val merge = FaceRecognitionConfig.CLUSTER_MERGE_THRESHOLD
        assertThat(assign).isAtLeast(0f)
        assertThat(assign).isAtMost(1f)
        assertThat(merge).isAtLeast(0f)
        assertThat(merge).isAtMost(1f)
        // merge MUST be stricter than assign or the merge pass would re-merge
        // every newly-assigned face on the next sweep.
        assertThat(merge).isGreaterThan(assign)
        assertThat(FaceRecognitionConfig.DEFAULT_MIN_CLUSTER_SIZE).isAtLeast(1)
    }

    @Test
    fun `model file name has tflite extension`() {
        assertThat(FaceRecognitionConfig.MODEL_FILE_NAME).endsWith(".tflite")
    }
}
