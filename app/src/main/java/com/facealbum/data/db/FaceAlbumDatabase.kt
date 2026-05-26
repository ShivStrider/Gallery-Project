package com.facealbum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PhotoEntity::class,
        FaceEntity::class,
        ClusterEntity::class,
        AlbumEntity::class,
        PersonEntity::class,
        SeedFaceEntity::class,
        ScanSessionEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class FaceAlbumDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun faceDao(): FaceDao
    abstract fun clusterDao(): ClusterDao
    abstract fun albumDao(): AlbumDao
    abstract fun personDao(): PersonDao
    abstract fun seedFaceDao(): SeedFaceDao
    abstract fun scanSessionDao(): ScanSessionDao

    companion object {
        private const val DB_NAME = "face_album.db"

        @Volatile private var instance: FaceAlbumDatabase? = null

        fun get(context: Context): FaceAlbumDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FaceAlbumDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }

        /**
         * v1 → v2: introduce `persons`, `seed_faces`, `scan_sessions`, and a
         * nullable `personId` foreign key on `clusters`. All existing
         * clusters/faces/photos/albums are preserved untouched.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS persons (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        displayName TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        notes TEXT
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS seed_faces (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        personId INTEGER NOT NULL,
                        sourcePhotoUri TEXT NOT NULL,
                        embedding BLOB NOT NULL,
                        addedAt INTEGER NOT NULL,
                        FOREIGN KEY(personId) REFERENCES persons(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_seed_faces_personId ON seed_faces(personId)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scan_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        endedAt INTEGER,
                        status TEXT NOT NULL,
                        photosScanned INTEGER NOT NULL,
                        facesAdded INTEGER NOT NULL,
                        errorMessage TEXT,
                        forceFullRescan INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                // Rebuild `clusters` with the new `personId` column + FK + index.
                db.execSQL(
                    """
                    CREATE TABLE clusters_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        displayName TEXT,
                        coverFaceId INTEGER,
                        faceCount INTEGER NOT NULL,
                        centroid BLOB NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        personId INTEGER,
                        FOREIGN KEY(personId) REFERENCES persons(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO clusters_new
                        (id, displayName, coverFaceId, faceCount, centroid, createdAt, updatedAt, personId)
                    SELECT id, displayName, coverFaceId, faceCount, centroid, createdAt, updatedAt, NULL
                    FROM clusters
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE clusters")
                db.execSQL("ALTER TABLE clusters_new RENAME TO clusters")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_clusters_personId ON clusters(personId)"
                )

                // Rebuild `albums` to (a) make clusterId nullable and (b) flip the
                // FK from CASCADE to SET_NULL — otherwise reclustering (which
                // rebuilds the clusters table) would silently wipe export history.
                db.execSQL(
                    """
                    CREATE TABLE albums_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        clusterId INTEGER,
                        albumName TEXT NOT NULL,
                        exportedRelativePath TEXT NOT NULL,
                        exportedAt INTEGER NOT NULL,
                        photoCount INTEGER NOT NULL,
                        FOREIGN KEY(clusterId) REFERENCES clusters(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO albums_new
                        (id, clusterId, albumName, exportedRelativePath, exportedAt, photoCount)
                    SELECT id, clusterId, albumName, exportedRelativePath, exportedAt, photoCount
                    FROM albums
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE albums")
                db.execSQL("ALTER TABLE albums_new RENAME TO albums")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_albums_clusterId ON albums(clusterId)"
                )
            }
        }
    }
}
