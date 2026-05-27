package com.facealbum.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke checks for the worker's public constants. The richer integration —
 * that one-time + periodic enqueue actually register work in WorkManager — is
 * exercised in instrumented (`androidTest`) runs where a real WorkManager
 * provider is available.
 */
class FaceIndexWorkerTest {

    @Test
    fun uniqueWorkName_isStable() {
        assertEquals("face_index", FaceIndexWorker.UNIQUE_WORK_NAME)
    }

    @Test
    fun periodicWorkName_isStableAndDistinct() {
        assertEquals("face_index_periodic", FaceIndexWorker.UNIQUE_PERIODIC_WORK_NAME)
        assertNotEquals(
            "Periodic + one-time must use distinct unique-work names so KEEP doesn't collapse them",
            FaceIndexWorker.UNIQUE_WORK_NAME,
            FaceIndexWorker.UNIQUE_PERIODIC_WORK_NAME
        )
    }

    @Test
    fun progressKeys_areNonBlank() {
        assertTrue(FaceIndexWorker.KEY_PROGRESS_DONE.isNotBlank())
        assertTrue(FaceIndexWorker.KEY_PROGRESS_TOTAL.isNotBlank())
        assertTrue(FaceIndexWorker.KEY_PROGRESS_FACES.isNotBlank())
        assertTrue(FaceIndexWorker.KEY_PROGRESS_CLUSTERS.isNotBlank())
    }
}
