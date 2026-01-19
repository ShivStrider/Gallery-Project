package com.facealbum.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FaceRecognitionConfigTest {

    @Test
    fun `model input size is valid dimension`() {
        assertThat(FaceRecognitionConfig.MODEL_INPUT_SIZE).isGreaterThan(0)
        assertThat(FaceRecognitionConfig.MODEL_INPUT_SIZE).isEqualTo(112)
    }

    @Test
    fun `embedding size matches MobileFaceNet output`() {
        assertThat(FaceRecognitionConfig.EMBEDDING_SIZE).isGreaterThan(0)
        assertThat(FaceRecognitionConfig.EMBEDDING_SIZE).isEqualTo(512)
    }

    @Test
    fun `default similarity threshold is in valid range`() {
        assertThat(FaceRecognitionConfig.DEFAULT_SIMILARITY_THRESHOLD).isGreaterThan(0f)
        assertThat(FaceRecognitionConfig.DEFAULT_SIMILARITY_THRESHOLD).isAtMost(1f)
    }

    @Test
    fun `default max photos is positive`() {
        assertThat(FaceRecognitionConfig.DEFAULT_MAX_PHOTOS).isGreaterThan(0)
    }

    @Test
    fun `max bitmap dimension is reasonable`() {
        assertThat(FaceRecognitionConfig.MAX_BITMAP_DIMENSION).isGreaterThan(0)
        assertThat(FaceRecognitionConfig.MAX_BITMAP_DIMENSION).isAtLeast(512)
    }

    @Test
    fun `face margin ratio is in valid range`() {
        assertThat(FaceRecognitionConfig.FACE_MARGIN_RATIO).isGreaterThan(0f)
        assertThat(FaceRecognitionConfig.FACE_MARGIN_RATIO).isLessThan(1f)
    }

    @Test
    fun `min face size is in valid range`() {
        assertThat(FaceRecognitionConfig.MIN_FACE_SIZE).isGreaterThan(0f)
        assertThat(FaceRecognitionConfig.MIN_FACE_SIZE).isLessThan(1f)
    }

    @Test
    fun `tflite num threads is positive`() {
        assertThat(FaceRecognitionConfig.TFLITE_NUM_THREADS).isGreaterThan(0)
    }

    @Test
    fun `model file name is not empty`() {
        assertThat(FaceRecognitionConfig.MODEL_FILE_NAME).isNotEmpty()
        assertThat(FaceRecognitionConfig.MODEL_FILE_NAME).endsWith(".tflite")
    }
}
