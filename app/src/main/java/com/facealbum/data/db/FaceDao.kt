package com.facealbum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FaceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(face: FaceEntity): Long

    @Query("UPDATE faces SET clusterId = :clusterId WHERE id = :faceId")
    suspend fun assignToCluster(faceId: Long, clusterId: Long?)

    @Query("UPDATE faces SET clusterId = :toCluster WHERE clusterId = :fromCluster")
    suspend fun reassignCluster(fromCluster: Long, toCluster: Long)

    @Query("SELECT * FROM faces WHERE clusterId = :clusterId")
    suspend fun facesInCluster(clusterId: Long): List<FaceEntity>

    @Query("SELECT photoId FROM faces WHERE clusterId = :clusterId GROUP BY photoId")
    suspend fun photoIdsInCluster(clusterId: Long): List<Long>

    @Query("SELECT * FROM faces WHERE photoId = :photoId")
    suspend fun facesForPhoto(photoId: Long): List<FaceEntity>

    @Query("DELETE FROM faces WHERE id = :faceId")
    suspend fun delete(faceId: Long)

    @Query("DELETE FROM faces WHERE photoId = :photoId")
    suspend fun deleteFacesForPhoto(photoId: Long)

    @Query("DELETE FROM faces")
    suspend fun clear()

    /** Drop every face's cluster assignment without deleting the face rows. */
    @Query("UPDATE faces SET clusterId = NULL")
    suspend fun clearAllClusterAssignments()

    /** Ordered by quality desc so re-clustering seeds new clusters from the best faces first. */
    @Query("SELECT * FROM faces ORDER BY quality DESC")
    suspend fun allOrderedByQualityDesc(): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE id = :faceId LIMIT 1")
    suspend fun findById(faceId: Long): FaceEntity?

    /**
     * Quality lookup for a batch of face ids (cover faces at cache load).
     * Callers must chunk [ids] below SQLite's 999-variable limit.
     */
    @Query("SELECT id, quality FROM faces WHERE id IN (:ids)")
    suspend fun qualitiesForIds(ids: List<Long>): List<FaceQualityRow>
}

/** Projection row for [FaceDao.qualitiesForIds]. */
data class FaceQualityRow(
    val id: Long,
    val quality: Float
)
