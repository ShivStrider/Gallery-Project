package com.facealbum.model

import com.facealbum.config.FaceRecognitionConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUiStateTest {

    @Test
    fun `default state has valid values`() {
        val state = AppUiState()

        assertThat(state.similarityThreshold).isEqualTo(FaceRecognitionConfig.DEFAULT_SIMILARITY_THRESHOLD)
        assertThat(state.maxPhotosToScan).isEqualTo(FaceRecognitionConfig.DEFAULT_MAX_PHOTOS)
        assertThat(state.seedUris).isEmpty()
        assertThat(state.candidates).isEmpty()
        assertThat(state.albumName).isEmpty()
        assertThat(state.scanState).isEqualTo(ScanState.Idle)
    }

    @Test
    fun `valid similarity threshold at lower bound`() {
        val state = AppUiState(similarityThreshold = 0f)

        assertThat(state.similarityThreshold).isEqualTo(0f)
    }

    @Test
    fun `valid similarity threshold at upper bound`() {
        val state = AppUiState(similarityThreshold = 1f)

        assertThat(state.similarityThreshold).isEqualTo(1f)
    }

    @Test
    fun `valid similarity threshold in middle range`() {
        val state = AppUiState(similarityThreshold = 0.5f)

        assertThat(state.similarityThreshold).isEqualTo(0.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid similarity threshold below zero throws`() {
        AppUiState(similarityThreshold = -0.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid similarity threshold above one throws`() {
        AppUiState(similarityThreshold = 1.1f)
    }

    @Test
    fun `valid maxPhotosToScan at minimum`() {
        val state = AppUiState(maxPhotosToScan = 1)

        assertThat(state.maxPhotosToScan).isEqualTo(1)
    }

    @Test
    fun `valid maxPhotosToScan with large value`() {
        val state = AppUiState(maxPhotosToScan = 10000)

        assertThat(state.maxPhotosToScan).isEqualTo(10000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid maxPhotosToScan at zero throws`() {
        AppUiState(maxPhotosToScan = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid maxPhotosToScan negative throws`() {
        AppUiState(maxPhotosToScan = -1)
    }

    @Test
    fun `copy preserves valid values`() {
        val original = AppUiState(
            similarityThreshold = 0.7f,
            maxPhotosToScan = 200,
            albumName = "Test Album"
        )

        val copied = original.copy(albumName = "New Album")

        assertThat(copied.similarityThreshold).isEqualTo(0.7f)
        assertThat(copied.maxPhotosToScan).isEqualTo(200)
        assertThat(copied.albumName).isEqualTo("New Album")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `copy with invalid threshold throws`() {
        val original = AppUiState()

        original.copy(similarityThreshold = 2.0f)
    }
}
