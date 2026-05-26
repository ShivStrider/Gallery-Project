package com.facealbum.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.facealbum.data.db.Embeddings
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.data.db.FaceEntity
import com.facealbum.data.db.PhotoEntity
import com.facealbum.data.prefs.UserPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReclusterUseCaseTest {

    private lateinit var db: FaceAlbumDatabase
    private lateinit var prefs: UserPreferences

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FaceAlbumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = UserPreferences.get(context)
        prefs.setAssignThreshold(0.9f)
        prefs.setMergeThreshold(0.95f)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `recluster responds to threshold changes without rescanning`() = runTest {
        val a = unitVec(seed = 1)
        val b = unitVec(seed = 1, jitter = 0.10f)
        insertFace(a)
        insertFace(b)

        val useCase = ReclusterUseCase(db, prefs)
        useCase.run()
        val strictClusters = db.clusterDao().all().size

        prefs.setAssignThreshold(0.3f)
        useCase.run()
        val lenientClusters = db.clusterDao().all().size

        assertThat(strictClusters).isEqualTo(2)
        assertThat(lenientClusters).isEqualTo(1)

        // Ensure preferences are really persisted/read via Flow.
        assertThat(prefs.assignThreshold.first()).isEqualTo(0.3f)
    }

    private suspend fun insertFace(embedding: FloatArray) {
        val id = System.nanoTime()
        val photoId = db.photoDao().insert(
            PhotoEntity(
                mediaStoreId = id,
                uri = "content://media/$id",
                displayName = "p.jpg",
                dateTaken = 0,
                dateModified = 0,
                processedAt = 0,
                faceCount = 1
            )
        )
        db.faceDao().insert(
            FaceEntity(
                photoId = photoId,
                clusterId = null,
                bboxLeft = 0,
                bboxTop = 0,
                bboxRight = 10,
                bboxBottom = 10,
                embedding = Embeddings.toBytes(embedding),
                quality = 1f
            )
        )
    }

    private fun unitVec(seed: Int, jitter: Float = 0f, size: Int = 512): FloatArray {
        val v = FloatArray(size)
        val rand = java.util.Random(seed.toLong())
        for (i in 0 until size) v[i] = rand.nextFloat() - 0.5f
        if (jitter != 0f) {
            val jr = java.util.Random((seed * 7919 + 13).toLong())
            for (i in 0 until size) v[i] += (jr.nextFloat() - 0.5f) * jitter
        }
        var norm = 0f
        for (x in v) norm += x * x
        val n = sqrt(norm)
        if (n > 0f) for (i in v.indices) v[i] /= n
        return v
    }
}
