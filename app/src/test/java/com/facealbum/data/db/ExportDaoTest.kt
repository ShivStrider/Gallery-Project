package com.facealbum.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The export transaction log is the safety net for move-exports: it decides
 * which source files may ever be deleted, and it is what an interrupted
 * export resumes from. These tests pin down both behaviours at the DAO seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExportDaoTest {

    private lateinit var db: FaceAlbumDatabase
    private lateinit var dao: ExportDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FaceAlbumDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.exportDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Invariant 1 of the safe-export design: a source file may only be
     * deleted after its copy verified. Every other state — including a copy
     * that succeeded but never verified — must stay out of the delete batch.
     */
    @Test
    fun `only verified and checksum-matched duplicates are deletable`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_MOVE)
        val states = listOf(
            ExportItemEntity.STATE_PENDING,
            ExportItemEntity.STATE_COPIED,
            ExportItemEntity.STATE_VERIFIED,
            ExportItemEntity.STATE_SKIPPED_DUPLICATE,
            ExportItemEntity.STATE_COPY_FAILED,
            ExportItemEntity.STATE_VERIFY_FAILED,
            ExportItemEntity.STATE_DELETE_DENIED,
            ExportItemEntity.STATE_UNDONE,
            ExportItemEntity.STATE_RESTORED,
            ExportItemEntity.STATE_SOURCE_DELETED
        )
        dao.insertItems(states.mapIndexed { i, state -> item(opId, i + 1L, state) })

        val deletable = dao.deletableItems(opId).map { it.state }.toSet()

        assertThat(deletable).isEqualTo(ExportItemEntity.DELETABLE_SOURCE_STATES)
    }

    /** A COPIED-but-unverified file is the dangerous case; call it out. */
    @Test
    fun `copied but unverified file is never deletable`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_MOVE)
        dao.insertItems(listOf(item(opId, 1L, ExportItemEntity.STATE_COPIED)))

        assertThat(dao.deletableItems(opId)).isEmpty()
    }

    @Test
    fun `deletable items never leak across operations`() = runTest {
        val mine = insertOperation(ExportOperationEntity.MODE_MOVE)
        val other = insertOperation(ExportOperationEntity.MODE_MOVE)
        dao.insertItems(listOf(item(mine, 1L, ExportItemEntity.STATE_VERIFIED)))
        dao.insertItems(listOf(item(other, 2L, ExportItemEntity.STATE_VERIFIED)))

        val deletable = dao.deletableItems(mine)

        assertThat(deletable).hasSize(1)
        assertThat(deletable.single().sourceMediaStoreId).isEqualTo(1L)
    }

    /** Resume: the worker picks up exactly the unfinished files, in order. */
    @Test
    fun `unfinished items are the pending and copied ones`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_COPY)
        dao.insertItems(
            listOf(
                item(opId, 1L, ExportItemEntity.STATE_PENDING),
                item(opId, 2L, ExportItemEntity.STATE_COPIED),
                item(opId, 3L, ExportItemEntity.STATE_VERIFIED),
                item(opId, 4L, ExportItemEntity.STATE_COPY_FAILED)
            )
        )

        val unfinished = dao.unfinishedItems(opId)

        assertThat(unfinished.map { it.sourceMediaStoreId }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `state transition persists checksum and destination`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_MOVE)
        dao.insertItems(listOf(item(opId, 1L, ExportItemEntity.STATE_PENDING)))
        val stored = dao.itemsForOperation(opId).single()

        dao.updateItemState(
            id = stored.id,
            state = ExportItemEntity.STATE_VERIFIED,
            destUri = "content://media/external/images/media/900",
            sourceSha256 = "abc123",
            errorCode = null,
            updatedAt = 42L
        )

        val updated = dao.itemsForOperation(opId).single()
        assertThat(updated.state).isEqualTo(ExportItemEntity.STATE_VERIFIED)
        assertThat(updated.sourceSha256).isEqualTo("abc123")
        assertThat(updated.destUri).isEqualTo("content://media/external/images/media/900")
        assertThat(updated.updatedAt).isEqualTo(42L)
    }

    @Test
    fun `awaiting-consent query finds operations stranded before the delete dialog`() = runTest {
        val stranded = insertOperation(
            ExportOperationEntity.MODE_MOVE,
            state = ExportOperationEntity.STATE_AWAITING_DELETE_CONSENT
        )
        insertOperation(ExportOperationEntity.MODE_MOVE, state = ExportOperationEntity.STATE_COMPLETED)

        val found = dao.operationsInState(ExportOperationEntity.STATE_AWAITING_DELETE_CONSENT)

        assertThat(found.map { it.id }).containsExactly(stranded)
    }

    @Test
    fun `deleting an operation cascades its items`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_COPY)
        dao.insertItems(listOf(item(opId, 1L, ExportItemEntity.STATE_VERIFIED)))
        assertThat(dao.itemsForOperation(opId)).hasSize(1)

        dao.clearOperations()

        assertThat(dao.itemsForOperation(opId)).isEmpty()
    }

    /** Recluster must not erase the record of files already moved. */
    @Test
    fun `operation survives deletion of its cluster`() = runTest {
        val clusterId = db.clusterDao().insert(
            ClusterEntity(
                displayName = "Ada",
                coverFaceId = null,
                faceCount = 1,
                centroid = Embeddings.toBytes(FloatArray(512)),
                createdAt = 0L,
                updatedAt = 0L
            )
        )
        val opId = dao.insertOperation(
            ExportOperationEntity(
                clusterId = clusterId,
                albumName = "Ada",
                destRelativePath = "Pictures/FaceAlbums/Ada/",
                mode = ExportOperationEntity.MODE_MOVE,
                state = ExportOperationEntity.STATE_COMPLETED,
                totalCount = 1,
                createdAt = 0L,
                updatedAt = 0L
            )
        )

        db.clusterDao().delete(clusterId)

        val survivor = dao.operationById(opId)
        assertThat(survivor).isNotNull()
        assertThat(survivor!!.clusterId).isNull()
        assertThat(survivor.albumName).isEqualTo("Ada")
    }

    private suspend fun insertOperation(
        mode: String,
        state: String = ExportOperationEntity.STATE_PENDING
    ): Long = dao.insertOperation(
        ExportOperationEntity(
            clusterId = null,
            albumName = "Album",
            destRelativePath = "Pictures/FaceAlbums/Album/",
            mode = mode,
            state = state,
            totalCount = 1,
            createdAt = 0L,
            updatedAt = 0L
        )
    )

    private fun item(operationId: Long, mediaStoreId: Long, state: String) = ExportItemEntity(
        operationId = operationId,
        photoId = null,
        sourceMediaStoreId = mediaStoreId,
        sourceUri = "content://media/external/images/media/$mediaStoreId",
        sourceDisplayName = "IMG_$mediaStoreId.jpg",
        sourceRelativePath = "DCIM/Camera/",
        sourceSizeBytes = 1024L,
        sourceSha256 = null,
        destDisplayName = "IMG_$mediaStoreId.jpg",
        destUri = null,
        state = state,
        errorCode = null,
        updatedAt = 0L
    )
}
