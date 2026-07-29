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
 * SQLite caps bind variables at 999; an unchunked IN() lookup crashes for a
 * person appearing in >999 photos. [findByIdsChunked] must not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhotoDaoChunkingTest {

    private lateinit var db: FaceAlbumDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FaceAlbumDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `chunked lookup returns every row past the 999 variable limit`() = runTest {
        val count = 1_200
        val ids = ArrayList<Long>(count)
        for (i in 1..count) {
            ids += db.photoDao().insert(
                PhotoEntity(
                    mediaStoreId = i.toLong(),
                    uri = "content://media/$i",
                    displayName = "p$i.jpg",
                    dateTaken = i.toLong(),
                    dateModified = i.toLong(),
                    processedAt = 0L,
                    faceCount = 0
                )
            )
        }

        val loaded = db.photoDao().findByIdsChunked(ids)

        assertThat(loaded).hasSize(count)
        assertThat(loaded.map { it.id }.toSet()).isEqualTo(ids.toSet())
    }

    @Test
    fun `chunked lookup with empty input returns empty`() = runTest {
        assertThat(db.photoDao().findByIdsChunked(emptyList())).isEmpty()
    }
}
