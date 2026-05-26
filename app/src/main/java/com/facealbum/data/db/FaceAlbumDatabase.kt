package com.facealbum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PhotoEntity::class,
        FaceEntity::class,
        ClusterEntity::class,
        AlbumEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class FaceAlbumDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun faceDao(): FaceDao
    abstract fun clusterDao(): ClusterDao
    abstract fun albumDao(): AlbumDao

    companion object {
        private const val DB_NAME = "face_album.db"

        @Volatile private var instance: FaceAlbumDatabase? = null

        fun get(context: Context): FaceAlbumDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FaceAlbumDatabase::class.java,
                    DB_NAME
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
