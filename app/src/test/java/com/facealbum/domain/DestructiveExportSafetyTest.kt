package com.facealbum.domain

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.facealbum.config.ExportFeature
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.ClusterEntity
import com.facealbum.data.db.Embeddings
import com.facealbum.data.db.ExportItemEntity
import com.facealbum.data.db.ExportOperationEntity
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.data.db.FaceEntity
import com.facealbum.data.db.PhotoEntity
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Destructive-operation suite — the exit criterion for
 * [ExportFeature.MOVE_ENABLED].**
 *
 * Every other test checks that a component does its job. These check that the
 * pipeline cannot destroy a photo, end to end, through the seam that actually
 * authorises deletion. Move mode stays off until this file is green in CI.
 *
 * The suite works against a simulated MediaStore rather than real files: the
 * `deletedSources` set is the device, and any deletion that happens without
 * passing through the consent gate would show up as an unexpected entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DestructiveExportSafetyTest {

    private lateinit var db: FaceAlbumDatabase
    private lateinit var photoRepository: PhotoRepository
    private var clusterId: Long = 0
    private var otherClusterId: Long = 0

    /** Stands in for the device: ids removed from MediaStore. */
    private val deletedSources = mutableSetOf<Long>()

    /** Destination copies the app currently has on disk. */
    private val destinations = mutableMapOf<String, Long>()

    /** Source display names as MediaStore would report them. */
    private val sourceNames = mutableMapOf<Long, String>()

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FaceAlbumDatabase::class.java
        ).allowMainThreadQueries().build()
        clusterId = insertCluster("Ada")
        otherClusterId = insertCluster("Grace")
        photoRepository = mockk(relaxed = true)

        every { photoRepository.makeStableDisplayName(any(), any()) } answers { firstArg<String>() }
        coEvery { photoRepository.querySourceMetadata(any()) } answers {
            firstArg<List<Long>>().associateWith { id ->
                PhotoRepository.SourceMetadata(
                    mediaStoreId = id,
                    displayName = sourceNames[id] ?: "IMG_$id.jpg",
                    relativePath = "DCIM/Camera/",
                    sizeBytes = 100L
                )
            }
        }
        coEvery { photoRepository.copyToAlbumChecked(any(), any(), any(), any()) } answers {
            val destName = thirdArg<String>()
            destinations[destName] = (destinations[destName] ?: 0L) + 1
            PhotoRepository.CheckedCopyResult.Success(
                uri = Uri.parse("content://dest/$destName"),
                bytesCopied = 100L,
                sha256 = "hash",
                dedupHit = false
            )
        }
        coEvery { photoRepository.verifyExportedCopy(any(), any(), any()) } returns
            PhotoRepository.VerifyResult.Verified
        coEvery { photoRepository.sourceStillExists(any()) } answers {
            firstArg<Long>() !in deletedSources
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * #1 — An unselected file must never reach the delete batch, no matter
     * what the caller passes in.
     */
    @Test
    fun `unselected and foreign photos never enter the delete batch`() = runTest {
        val keepMine = insertPhoto(clusterId, 1)
        val exportMine = insertPhoto(clusterId, 2)
        val someoneElse = insertPhoto(otherClusterId, 3)

        // Deliberately hostile input: another person's photo, plus one of
        // mine the user did not select.
        val opId = runExport(
            mode = ExportPlanner.Mode.MOVE,
            selection = listOf(exportMine, someoneElse)
        )

        val deletable = db.exportDao().deletableItems(opId).map { it.sourceMediaStoreId }
        assertThat(deletable).containsExactly(2L)
        assertThat(deletable).doesNotContain(1L) // unselected
        assertThat(deletable).doesNotContain(3L) // belongs to another person
        assertThat(keepMine).isGreaterThan(0L)
    }

    /** #2 — A verification failure must leave the original in place. */
    @Test
    fun `a source survives when its copy fails verification`() = runTest {
        insertPhoto(clusterId, 1)
        coEvery { photoRepository.verifyExportedCopy(any(), any(), any()) } returns
            PhotoRepository.VerifyResult.Failed(PhotoRepository.VerifyError.CHECKSUM_MISMATCH)

        val opId = runExport(ExportPlanner.Mode.MOVE)
        consent(opId, granted = true)

        assertThat(deletedSources).isEmpty()
        assertThat(db.exportDao().itemsForOperation(opId).single().state)
            .isEqualTo(ExportItemEntity.STATE_VERIFY_FAILED)
    }

    /** #2b — Same for a copy that never completed. */
    @Test
    fun `a source survives when its copy fails outright`() = runTest {
        insertPhoto(clusterId, 1)
        coEvery { photoRepository.copyToAlbumChecked(any(), any(), any(), any()) } returns
            PhotoRepository.CheckedCopyResult.Failure(PhotoRepository.CopyToAlbumError.COPY_FAILED)

        val opId = runExport(ExportPlanner.Mode.MOVE)
        consent(opId, granted = true)

        assertThat(deletedSources).isEmpty()
        assertThat(db.exportDao().deletableItems(opId)).isEmpty()
    }

    /**
     * #3 — Interrupting and resuming must neither lose a file nor copy one
     * twice.
     */
    @Test
    fun `an interrupted export resumes without loss or duplication`() = runTest {
        repeat(4) { i -> insertPhoto(clusterId, (i + 1).toLong()) }
        val plan = planner().plan(clusterId, "Ada", mode = ExportPlanner.Mode.MOVE)
        val opId = (planner().commit(plan) as ExportPlanner.CommitResult.Started).operationId

        // Kill the process midway: only two items got through.
        val items = db.exportDao().itemsForOperation(opId)
        items.take(2).forEach {
            db.exportDao().updateItemState(
                it.id, ExportItemEntity.STATE_VERIFIED,
                "content://dest/${it.destDisplayName}", "hash", null, 1L
            )
        }
        destinations["IMG_1.jpg"] = 1
        destinations["IMG_2.jpg"] = 1

        executor().run(opId)

        // Every file present exactly once, and nothing copied twice.
        val states = db.exportDao().itemsForOperation(opId)
        assertThat(states).hasSize(4)
        assertThat(states.map { it.state }.toSet())
            .containsExactly(ExportItemEntity.STATE_VERIFIED)
        assertThat(destinations.values.toSet()).containsExactly(1)
    }

    /** #4 — Two sources must never collapse onto one destination file. */
    @Test
    fun `colliding filenames never overwrite each other`() = runTest {
        // Same display name from two different source folders.
        insertPhoto(clusterId, 1, name = "IMG.jpg")
        insertPhoto(clusterId, 2, name = "IMG.jpg")

        val opId = runExport(ExportPlanner.Mode.MOVE)

        val destNames = db.exportDao().itemsForOperation(opId).map { it.destDisplayName }
        assertThat(destNames).hasSize(2)
        assertThat(destNames.toSet()).hasSize(2)
        assertThat(destinations.keys).hasSize(2)
    }

    /** #5 — Declining the prompt must leave every original untouched. */
    @Test
    fun `declining consent leaves every original on the device`() = runTest {
        repeat(3) { i -> insertPhoto(clusterId, (i + 1).toLong()) }

        val opId = runExport(ExportPlanner.Mode.MOVE)
        consent(opId, granted = false)

        assertThat(deletedSources).isEmpty()
        assertThat(db.exportDao().itemsForOperation(opId).map { it.state }.toSet())
            .containsExactly(ExportItemEntity.STATE_DELETE_DENIED)
    }

    /** #6 — A failed export must leave an actionable recovery state. */
    @Test
    fun `a partly failed export reports precisely what happened`() = runTest {
        insertPhoto(clusterId, 1)
        insertPhoto(clusterId, 2)
        var call = 0
        coEvery { photoRepository.verifyExportedCopy(any(), any(), any()) } answers {
            call += 1
            if (call == 1) {
                PhotoRepository.VerifyResult.Verified
            } else {
                PhotoRepository.VerifyResult.Failed(PhotoRepository.VerifyError.SIZE_MISMATCH)
            }
        }

        val opId = runExport(ExportPlanner.Mode.MOVE)
        consent(opId, granted = true)
        val report = ExportReport.load(db, opId)!!

        assertThat(report.failedCount).isEqualTo(1)
        assertThat(report.sourcesDeletedCount).isEqualTo(1)
        assertThat(report.hasProblems).isTrue()
        assertThat(report.operationState)
            .isEqualTo(ExportOperationEntity.STATE_COMPLETED_WITH_ERRORS)
        assertThat(report.canUndo).isTrue()
        // Only the verified file's source went.
        assertThat(deletedSources).containsExactly(1L)
    }

    /** #7 — Undo after a move must put every deleted original back. */
    @Test
    fun `undo restores every original a move deleted`() = runTest {
        insertPhoto(clusterId, 1)
        insertPhoto(clusterId, 2)
        val opId = runExport(ExportPlanner.Mode.MOVE)
        consent(opId, granted = true)
        assertThat(deletedSources).containsExactly(1L, 2L)

        coEvery { photoRepository.restoreFromCopy(any(), any(), any(), any()) } answers {
            // The restore puts the file back on the "device".
            val name = thirdArg<String>()
            deletedSources.remove(name.removePrefix("IMG_").removeSuffix(".jpg").toLong())
            PhotoRepository.CheckedCopyResult.Success(
                uri = Uri.parse("content://media/restored"),
                bytesCopied = 100L, sha256 = "hash", dedupHit = false
            )
        }
        coEvery { photoRepository.deleteOwnedDest(any()) } returns true

        val result = ExportUndoUseCase(
            ApplicationProvider.getApplicationContext(), db, photoRepository
        ) { 9L }.undo(opId)

        assertThat(result.restoredCount).isEqualTo(2)
        assertThat(deletedSources).isEmpty()
    }

    /**
     * #8 — The rollout gate itself. Move must remain unreachable until this
     * suite is deliberately signed off, so this asserts the flag's current
     * value on purpose: flipping it should require editing this test too.
     */
    @Test
    fun `move mode stays disabled until this suite is signed off`() {
        assertThat(ExportFeature.MOVE_ENABLED).isFalse()
        assertThat(ExportFeature.moveAvailable()).isFalse()
    }

    // --- harness ---

    private fun planner() = ExportPlanner(
        context = ApplicationProvider.getApplicationContext(),
        db = db,
        photoRepository = photoRepository,
        now = { 1L },
        enqueueWorker = { /* executed inline by the tests */ }
    )

    private fun executor() = ExportExecutor(
        context = ApplicationProvider.getApplicationContext(),
        db = db,
        photoRepository = photoRepository,
        now = { 2L }
    )

    private suspend fun runExport(
        mode: ExportPlanner.Mode,
        selection: List<Long>? = null
    ): Long {
        val plan = planner().plan(clusterId, "Ada", photoRowIds = selection, mode = mode)
        val committed = planner().commit(plan)
        val opId = (committed as ExportPlanner.CommitResult.Started).operationId
        executor().run(opId)
        return opId
    }

    /** Runs the consent gate, simulating the system deleting what it's given. */
    private suspend fun consent(operationId: Long, granted: Boolean) {
        val useCase = ExportConsentUseCase(
            ApplicationProvider.getApplicationContext(), db, photoRepository
        ) { 3L }
        if (granted) {
            // Only what the gate hands over may be deleted.
            useCase.deletableSourceUris(operationId).forEach { uri ->
                deletedSources += uri.lastPathSegment!!.toLong()
            }
        }
        useCase.finalizeAfterConsent(operationId, granted)
    }

    private suspend fun insertCluster(name: String): Long = db.clusterDao().insert(
        ClusterEntity(
            displayName = name, coverFaceId = null, faceCount = 1,
            centroid = Embeddings.toBytes(FloatArray(512)),
            createdAt = 0L, updatedAt = 0L
        )
    )

    private suspend fun insertPhoto(
        cluster: Long,
        mediaStoreId: Long,
        name: String = "IMG_$mediaStoreId.jpg"
    ): Long {
        sourceNames[mediaStoreId] = name
        val photoId = db.photoDao().insert(
            PhotoEntity(
                mediaStoreId = mediaStoreId,
                uri = "content://media/external/images/media/$mediaStoreId",
                displayName = name,
                dateTaken = 0L, dateModified = 0L, processedAt = 0L, faceCount = 1
            )
        )
        db.faceDao().insert(
            FaceEntity(
                photoId = photoId, clusterId = cluster,
                bboxLeft = 0, bboxTop = 0, bboxRight = 10, bboxBottom = 10,
                embedding = Embeddings.toBytes(FloatArray(512)), quality = 0.5f
            )
        )
        return photoId
    }
}
