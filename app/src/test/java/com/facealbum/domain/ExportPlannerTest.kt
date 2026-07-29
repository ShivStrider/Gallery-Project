package com.facealbum.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The plan is what the user confirms, so it must describe exactly the files
 * that will be touched — no more, and never a file belonging to someone else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExportPlannerTest {

    private lateinit var db: FaceAlbumDatabase
    private lateinit var photoRepository: PhotoRepository
    private lateinit var planner: ExportPlanner

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FaceAlbumDatabase::class.java
        ).allowMainThreadQueries().build()
        photoRepository = mockk()
        every { photoRepository.makeStableDisplayName(any(), any()) } answers {
            val name = firstArg<String>()
            val dot = name.lastIndexOf('.')
            if (dot > 0) name.substring(0, dot) + "_tok" + name.substring(dot) else name + "_tok"
        }
        planner = ExportPlanner(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
            photoRepository = photoRepository,
            now = { 1000L },
            enqueueWorker = { enqueued += it }
        )
    }

    private val enqueued = mutableListOf<Long>()

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `whole-cluster plan lists every photo once with sizes`() = runTest {
        val clusterId = insertCluster()
        val a = insertPhotoWithFace(clusterId, mediaStoreId = 1, name = "a.jpg")
        val b = insertPhotoWithFace(clusterId, mediaStoreId = 2, name = "b.jpg")
        stubMetadata(mapOf(1L to "a.jpg" to 100L, 2L to "b.jpg" to 250L))

        val plan = planner.plan(clusterId, "Ada")

        assertThat(plan.items.map { it.photoRowId }).containsExactly(a, b)
        assertThat(plan.fileCount).isEqualTo(2)
        assertThat(plan.totalBytes).isEqualTo(350L)
        assertThat(plan.albumName).isEqualTo("Ada")
        assertThat(plan.rejectedCount).isEqualTo(0)
    }

    /** The core safety property: never plan a file the user didn't select. */
    @Test
    fun `subset plan contains only the requested photos`() = runTest {
        val clusterId = insertCluster()
        val a = insertPhotoWithFace(clusterId, mediaStoreId = 1, name = "a.jpg")
        insertPhotoWithFace(clusterId, mediaStoreId = 2, name = "b.jpg")
        stubMetadata(mapOf(1L to "a.jpg" to 100L, 2L to "b.jpg" to 250L))

        val plan = planner.plan(clusterId, "Ada", photoRowIds = listOf(a))

        assertThat(plan.items.map { it.photoRowId }).containsExactly(a)
        assertThat(plan.totalBytes).isEqualTo(100L)
    }

    /** A stale selection from another person must not leak into this album. */
    @Test
    fun `photos outside the cluster are rejected, not exported`() = runTest {
        val mine = insertCluster()
        val other = insertCluster()
        val minePhoto = insertPhotoWithFace(mine, mediaStoreId = 1, name = "a.jpg")
        val otherPhoto = insertPhotoWithFace(other, mediaStoreId = 2, name = "b.jpg")
        stubMetadata(mapOf(1L to "a.jpg" to 100L, 2L to "b.jpg" to 250L))

        val plan = planner.plan(mine, "Ada", photoRowIds = listOf(minePhoto, otherPhoto))

        assertThat(plan.items.map { it.photoRowId }).containsExactly(minePhoto)
        assertThat(plan.rejectedCount).isEqualTo(1)
    }

    @Test
    fun `a photo whose source vanished is rejected rather than planned`() = runTest {
        val clusterId = insertCluster()
        insertPhotoWithFace(clusterId, mediaStoreId = 1, name = "a.jpg")
        insertPhotoWithFace(clusterId, mediaStoreId = 2, name = "b.jpg")
        // Only photo 1 still exists in MediaStore.
        stubMetadata(mapOf(1L to "a.jpg" to 100L))

        val plan = planner.plan(clusterId, "Ada")

        assertThat(plan.fileCount).isEqualTo(1)
        assertThat(plan.rejectedCount).isEqualTo(1)
    }

    /** Two sources must never be planned onto the same destination file. */
    @Test
    fun `colliding destination names are disambiguated at plan time`() = runTest {
        val clusterId = insertCluster()
        insertPhotoWithFace(clusterId, mediaStoreId = 1, name = "IMG.jpg")
        insertPhotoWithFace(clusterId, mediaStoreId = 2, name = "IMG.jpg")
        stubMetadata(mapOf(1L to "IMG.jpg" to 10L, 2L to "IMG.jpg" to 20L))

        val plan = planner.plan(clusterId, "Ada")

        val destNames = plan.items.map { it.destDisplayName }
        assertThat(destNames).hasSize(2)
        assertThat(destNames.toSet()).hasSize(2)
        assertThat(destNames.all { it.endsWith(".jpg") }).isTrue()
    }

    @Test
    fun `plan flags photos that also contain other people`() = runTest {
        val mine = insertCluster()
        val other = insertCluster()
        val shared = insertPhotoWithFace(mine, mediaStoreId = 1, name = "a.jpg")
        // A second face on the same photo belongs to someone else.
        db.faceDao().insert(
            FaceEntity(
                photoId = shared, clusterId = other,
                bboxLeft = 0, bboxTop = 0, bboxRight = 5, bboxBottom = 5,
                embedding = Embeddings.toBytes(FloatArray(512)), quality = 0.1f
            )
        )
        stubMetadata(mapOf(1L to "a.jpg" to 100L))

        val plan = planner.plan(mine, "Ada")

        assertThat(plan.items.single().containsOtherPeople).isTrue()
    }

    @Test
    fun `album name falls back to the person name and is sanitised`() = runTest {
        val clusterId = insertCluster(displayName = "Ada/Lovelace")
        insertPhotoWithFace(clusterId, mediaStoreId = 1, name = "a.jpg")
        stubMetadata(mapOf(1L to "a.jpg" to 1L))

        val plan = planner.plan(clusterId, requestedAlbumName = "  ")

        assertThat(plan.albumName).isEqualTo("Ada_Lovelace")
        assertThat(plan.destRelativePath).isEqualTo("Pictures/FaceAlbums/Ada_Lovelace/")
    }

    @Test
    fun `commit writes the operation and one pending item per file`() = runTest {
        val clusterId = insertCluster()
        insertPhotoWithFace(clusterId, mediaStoreId = 1, name = "a.jpg")
        insertPhotoWithFace(clusterId, mediaStoreId = 2, name = "b.jpg")
        stubMetadata(mapOf(1L to "a.jpg" to 100L, 2L to "b.jpg" to 250L))
        val plan = planner.plan(clusterId, "Ada")

        val result = planner.commit(plan)

        assertThat(result).isInstanceOf(ExportPlanner.CommitResult.Started::class.java)
        val opId = (result as ExportPlanner.CommitResult.Started).operationId
        val op = db.exportDao().operationById(opId)!!
        assertThat(op.state).isEqualTo(ExportOperationEntity.STATE_PENDING)
        assertThat(op.mode).isEqualTo(ExportOperationEntity.MODE_COPY)
        assertThat(op.totalCount).isEqualTo(2)

        val items = db.exportDao().itemsForOperation(opId)
        assertThat(items).hasSize(2)
        assertThat(items.map { it.state }.toSet())
            .containsExactly(ExportItemEntity.STATE_PENDING)
        // Source identity is denormalised so undo works after the index changes.
        assertThat(items.map { it.sourceMediaStoreId }).containsExactly(1L, 2L)
        assertThat(items.all { it.sourceSha256 == null }).isTrue()
        // Execution is handed to the worker exactly once.
        assertThat(enqueued).containsExactly(opId)
    }

    @Test
    fun `committing an empty plan does nothing`() = runTest {
        val clusterId = insertCluster()
        val plan = planner.plan(clusterId, "Ada", photoRowIds = emptyList())

        assertThat(planner.commit(plan)).isEqualTo(ExportPlanner.CommitResult.NothingToDo)
        assertThat(db.exportDao().operationsInState(ExportOperationEntity.STATE_PENDING)).isEmpty()
        assertThat(enqueued).isEmpty()
    }

    /** Below API 30 the app cannot delete media it doesn't own. */
    @Test
    @Config(sdk = [29])
    fun `move is refused on API levels without createDeleteRequest`() = runTest {
        val clusterId = insertCluster()
        insertPhotoWithFace(clusterId, mediaStoreId = 1, name = "a.jpg")
        stubMetadata(mapOf(1L to "a.jpg" to 100L))
        val plan = planner.plan(clusterId, "Ada", mode = ExportPlanner.Mode.MOVE)

        assertThat(planner.commit(plan)).isEqualTo(ExportPlanner.CommitResult.MoveUnsupported)
        assertThat(db.exportDao().operationsInState(ExportOperationEntity.STATE_PENDING)).isEmpty()
        // Nothing was scheduled, so no copy can start on an unsupported path.
        assertThat(enqueued).isEmpty()
    }

    @Test
    @Config(sdk = [33])
    fun `move is accepted on API 30 and above`() = runTest {
        val clusterId = insertCluster()
        insertPhotoWithFace(clusterId, mediaStoreId = 1, name = "a.jpg")
        stubMetadata(mapOf(1L to "a.jpg" to 100L))
        val plan = planner.plan(clusterId, "Ada", mode = ExportPlanner.Mode.MOVE)

        val result = planner.commit(plan)

        assertThat(result).isInstanceOf(ExportPlanner.CommitResult.Started::class.java)
        val opId = (result as ExportPlanner.CommitResult.Started).operationId
        assertThat(db.exportDao().operationById(opId)!!.mode)
            .isEqualTo(ExportOperationEntity.MODE_MOVE)
    }

    // --- helpers ---

    private fun stubMetadata(spec: Map<Pair<Long, String>, Long>) {
        val byId = spec.entries.associate { (key, size) ->
            val (id, name) = key
            id to PhotoRepository.SourceMetadata(
                mediaStoreId = id,
                displayName = name,
                relativePath = "DCIM/Camera/",
                sizeBytes = size
            )
        }
        coEvery { photoRepository.querySourceMetadata(any()) } answers {
            val requested = firstArg<List<Long>>()
            byId.filterKeys { it in requested }
        }
    }

    private suspend fun insertCluster(displayName: String? = null): Long =
        db.clusterDao().insert(
            ClusterEntity(
                displayName = displayName,
                coverFaceId = null,
                faceCount = 1,
                centroid = Embeddings.toBytes(FloatArray(512)),
                createdAt = 0L,
                updatedAt = 0L
            )
        )

    private suspend fun insertPhotoWithFace(
        clusterId: Long,
        mediaStoreId: Long,
        name: String
    ): Long {
        val photoId = db.photoDao().insert(
            PhotoEntity(
                mediaStoreId = mediaStoreId,
                uri = "content://media/external/images/media/$mediaStoreId",
                displayName = name,
                dateTaken = 0L,
                dateModified = 0L,
                processedAt = 0L,
                faceCount = 1
            )
        )
        db.faceDao().insert(
            FaceEntity(
                photoId = photoId,
                clusterId = clusterId,
                bboxLeft = 0, bboxTop = 0, bboxRight = 10, bboxBottom = 10,
                embedding = Embeddings.toBytes(FloatArray(512)),
                quality = 0.5f
            )
        )
        return photoId
    }
}
