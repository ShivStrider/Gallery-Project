package com.facealbum.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FaceIndexWorkerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun enqueue_oneTime_registersWork() {
        FaceIndexWorker.enqueue(context, forceFullRescan = false)
        val infos = workManager
            .getWorkInfosForUniqueWork(FaceIndexWorker.UNIQUE_WORK_NAME)
            .get()
        assertNotNull("WorkInfo list should not be null", infos)
        assertTrue("Expected at least one work request", infos.isNotEmpty())
        val state = infos.first().state
        assertTrue(
            "Expected ENQUEUED or RUNNING, got $state",
            state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
        )
    }

    @Test
    fun enqueuePeriodic_registersPeriodicWork() {
        FaceIndexWorker.enqueuePeriodic(context)
        val infos = workManager
            .getWorkInfosForUniqueWork(FaceIndexWorker.UNIQUE_PERIODIC_WORK_NAME)
            .get()
        assertNotNull("Periodic WorkInfo list should not be null", infos)
        assertTrue("Expected periodic work to be enqueued", infos.isNotEmpty())
        val state = infos.first().state
        assertTrue(
            "Expected ENQUEUED or RUNNING for periodic work, got $state",
            state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
        )
    }

    @Test
    fun enqueue_withKeepPolicy_doesNotDuplicateWork() {
        FaceIndexWorker.enqueue(context)
        FaceIndexWorker.enqueue(context)
        val infos = workManager
            .getWorkInfosForUniqueWork(FaceIndexWorker.UNIQUE_WORK_NAME)
            .get()
        assertTrue("KEEP policy should result in exactly one work request", infos.size == 1)
    }
}
