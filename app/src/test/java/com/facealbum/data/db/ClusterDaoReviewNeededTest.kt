package com.facealbum.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ClusterDao.summariesBelow] must be the exact complement of
 * [ClusterDao.summariesAtLeast]: every non-empty cluster shows up in exactly
 * one of the two, split at the same boundary `summariesAtLeast` already uses
 * (`>= minSize` is visible, everything else below it is "review needed").
 * These faces were previously unreachable in the UI once a cluster fell
 * under the "Minimum group size" setting — see P4.3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClusterDaoReviewNeededTest {

    private lateinit var db: FaceAlbumDatabase
    private lateinit var dao: ClusterDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FaceAlbumDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.clusterDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `clusters straddling the boundary land on the correct side`() = runTest {
        val minSize = 3
        val below = insertCluster(faceCount = 1)
        val atBoundary = insertCluster(faceCount = minSize)
        val above = insertCluster(faceCount = minSize + 5)

        val visible = dao.summariesAtLeast(minSize).first().map { it.id }
        val needsReview = dao.summariesBelow(minSize).first().map { it.id }

        assertThat(visible).containsExactly(atBoundary, above)
        assertThat(needsReview).containsExactly(below)
    }

    @Test
    fun `a cluster of exactly minSize belongs to the visible set, not review`() = runTest {
        val minSize = 3
        val exact = insertCluster(faceCount = minSize)

        assertThat(dao.summariesAtLeast(minSize).first().map { it.id }).containsExactly(exact)
        assertThat(dao.summariesBelow(minSize).first()).isEmpty()
    }

    @Test
    fun `zero-face clusters are excluded from both the visible and review sets`() = runTest {
        val minSize = 3
        insertCluster(faceCount = 0)

        assertThat(dao.summariesAtLeast(minSize).first()).isEmpty()
        assertThat(dao.summariesBelow(minSize).first()).isEmpty()
    }

    @Test
    fun `no cluster appears in both the visible and review sets`() = runTest {
        val minSize = 4
        insertCluster(faceCount = 0)
        insertCluster(faceCount = 1)
        insertCluster(faceCount = minSize - 1)
        insertCluster(faceCount = minSize)
        insertCluster(faceCount = minSize + 1)

        val visible = dao.summariesAtLeast(minSize).first().map { it.id }.toSet()
        val needsReview = dao.summariesBelow(minSize).first().map { it.id }.toSet()

        assertThat(visible.intersect(needsReview)).isEmpty()
    }

    @Test
    fun `review needed face count sums only the below-threshold bucket`() = runTest {
        val minSize = 3
        insertCluster(faceCount = 0)
        insertCluster(faceCount = 1)
        insertCluster(faceCount = 2)
        insertCluster(faceCount = minSize)
        insertCluster(faceCount = minSize + 10)

        assertThat(dao.reviewNeededFaceCount(minSize).first()).isEqualTo(3)
    }

    @Test
    fun `review needed face count is zero when nothing is below threshold`() = runTest {
        assertThat(dao.reviewNeededFaceCount(3).first()).isEqualTo(0)
    }

    private suspend fun insertCluster(faceCount: Int): Long = dao.insert(
        ClusterEntity(
            displayName = null,
            coverFaceId = null,
            faceCount = faceCount,
            centroid = Embeddings.toBytes(FloatArray(512)),
            createdAt = 0L,
            updatedAt = 0L
        )
    )
}
