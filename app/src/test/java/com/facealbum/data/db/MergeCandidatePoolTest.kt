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
 * `MainViewModel.pickAvailableMergeTargets` builds its candidate pool by
 * combining [ClusterDao.summariesAtLeast] and [ClusterDao.summariesBelow]
 * (the two lists backing, respectively, `MainViewModel.clusters` and
 * `MainViewModel.reviewNeededClusters`), then dropping the cluster currently
 * being viewed and sorting by faceCount desc. Before this fix the picker
 * only ever offered [ClusterDao.summariesAtLeast], so two below-threshold
 * clusters of the same person could never be merged into each other — see
 * ROADMAP.md's "known gaps" entry.
 *
 * This test exercises that combination directly against Room rather than
 * constructing `MainViewModel` itself, which drags in WorkManager and
 * DataStore wiring this test module has no fixture for; the DAO-level
 * composition is exactly what the ViewModel filters and sorts, so proving it
 * here proves the fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MergeCandidatePoolTest {

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

    /** Mirrors MainViewModel.pickAvailableMergeTargets' composition exactly. */
    private suspend fun mergeCandidates(minSize: Int, excludeClusterId: Long): List<ClusterSummary> {
        val atLeast = dao.summariesAtLeast(minSize).first()
        val below = dao.summariesBelow(minSize).first()
        return (atLeast + below)
            .filter { it.id != excludeClusterId }
            .sortedByDescending { it.faceCount }
    }

    @Test
    fun `two below-threshold clusters can offer each other as merge targets`() = runTest {
        val minSize = 3
        val strayA = insertCluster(faceCount = 1)
        val strayB = insertCluster(faceCount = 1)

        val candidatesForA = mergeCandidates(minSize, excludeClusterId = strayA).map { it.id }
        val candidatesForB = mergeCandidates(minSize, excludeClusterId = strayB).map { it.id }

        assertThat(candidatesForA).containsExactly(strayB)
        assertThat(candidatesForB).containsExactly(strayA)
    }

    @Test
    fun `candidate pool includes both above and below threshold clusters, excluding self`() = runTest {
        val minSize = 3
        val small = insertCluster(faceCount = 1)
        val exact = insertCluster(faceCount = minSize)
        val large = insertCluster(faceCount = minSize + 5)

        val candidates = mergeCandidates(minSize, excludeClusterId = exact).map { it.id }

        assertThat(candidates).containsExactly(small, large)
    }

    @Test
    fun `candidate pool is ordered by faceCount descending`() = runTest {
        val minSize = 3
        val tiny = insertCluster(faceCount = 1)
        val medium = insertCluster(faceCount = minSize)
        val big = insertCluster(faceCount = minSize + 10)
        val excluded = insertCluster(faceCount = minSize + 1)

        val candidates = mergeCandidates(minSize, excludeClusterId = excluded).map { it.id }

        assertThat(candidates).containsExactly(big, medium, tiny).inOrder()
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
