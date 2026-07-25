package com.facealbum.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validates [FaceAlbumDatabase.MIGRATION_1_2] and [FaceAlbumDatabase.MIGRATION_2_3]
 * against the exported schemas in `app/schemas/`.
 *
 * Both migrations rebuild tables by hand (CREATE + INSERT SELECT + DROP + RENAME),
 * which is exactly the pattern that silently drifts from the entity definitions.
 * `runMigrationsAndValidate` compares the post-migration database against the
 * schema Room generated for that version and fails on any mismatch, so the
 * schema JSON files are a required input — not optional artefacts.
 *
 * Runs under Robolectric so it executes in CI's `./gradlew test`, rather than
 * needing a connected device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FaceAlbumDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FaceAlbumDatabase::class.java
    )

    @Test
    fun migrate1To2_matchesExportedSchema() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            FaceAlbumDatabase.MIGRATION_1_2
        ).close()
    }

    @Test
    fun migrate2To3_matchesExportedSchema() {
        helper.createDatabase(TEST_DB, 2).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            FaceAlbumDatabase.MIGRATION_2_3
        ).close()
    }

    @Test
    fun migrate1To3_matchesExportedSchema() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            FaceAlbumDatabase.MIGRATION_1_2,
            FaceAlbumDatabase.MIGRATION_2_3
        ).close()
    }

    /**
     * The v1 -> v2 migration rebuilds `clusters` and `albums` wholesale. The doc
     * comment promises existing rows survive that rebuild; this asserts it.
     */
    @Test
    fun migrate1To2_preservesExistingRows() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO clusters
                    (id, displayName, coverFaceId, faceCount, centroid, createdAt, updatedAt)
                VALUES (1, 'Ada', NULL, 3, X'00', 100, 200)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO albums
                    (id, clusterId, albumName, exportedRelativePath, exportedAt, photoCount)
                VALUES (1, 1, 'Ada', 'Pictures/FaceAlbum/Ada', 300, 3)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            FaceAlbumDatabase.MIGRATION_1_2
        ).use { db ->
            db.query("SELECT displayName, faceCount, personId FROM clusters WHERE id = 1").use {
                assertTrue("cluster row was dropped by the rebuild", it.moveToFirst())
                assertEquals("Ada", it.getString(0))
                assertEquals(3, it.getInt(1))
                assertTrue("personId should start out NULL", it.isNull(2))
            }
            db.query("SELECT albumName, clusterId, photoCount FROM albums WHERE id = 1").use {
                assertTrue("album row was dropped by the rebuild", it.moveToFirst())
                assertEquals("Ada", it.getString(0))
                assertEquals(1, it.getInt(1))
                assertEquals(3, it.getInt(2))
            }
        }
    }

    /**
     * The v2 -> v3 migration drops `persons`/`seed_faces` and rebuilds `clusters`
     * without `personId`. `scan_sessions` and cluster rows must survive.
     */
    @Test
    fun migrate2To3_preservesScanSessionsAndClusters() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO clusters
                    (id, displayName, coverFaceId, faceCount, centroid, createdAt, updatedAt, personId)
                VALUES (1, 'Grace', NULL, 7, X'00', 100, 200, NULL)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO scan_sessions
                    (id, startedAt, endedAt, status, photosScanned, facesAdded, errorMessage, forceFullRescan)
                VALUES (1, 100, 400, 'COMPLETED', 42, 9, NULL, 0)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            FaceAlbumDatabase.MIGRATION_2_3
        ).use { db ->
            db.query("SELECT displayName, faceCount FROM clusters WHERE id = 1").use {
                assertTrue("cluster row was dropped by the rebuild", it.moveToFirst())
                assertEquals("Grace", it.getString(0))
                assertEquals(7, it.getInt(1))
            }
            db.query("SELECT status, photosScanned FROM scan_sessions WHERE id = 1").use {
                assertTrue("scan_sessions row did not survive", it.moveToFirst())
                assertEquals("COMPLETED", it.getString(0))
                assertEquals(42, it.getInt(1))
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
