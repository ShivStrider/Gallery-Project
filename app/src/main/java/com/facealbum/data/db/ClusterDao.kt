package com.facealbum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Lightweight projection joining a cluster with the URI of its cover photo
 * so the People grid can render thumbnails without a second query.
 */
data class ClusterSummary(
    val id: Long,
    val displayName: String?,
    val faceCount: Int,
    val coverPhotoUri: String?
)

@Dao
interface ClusterDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(cluster: ClusterEntity): Long

    @Update
    suspend fun update(cluster: ClusterEntity)

    @Query("UPDATE clusters SET displayName = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    /** Recompute faceCount from the faces table for a single cluster. */
    @Query(
        """
        UPDATE clusters
        SET faceCount = (SELECT COUNT(*) FROM faces WHERE clusterId = :id),
            updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun recomputeFaceCount(id: Long, now: Long)

    /** Drop clusters that ended up with zero faces (e.g. after a re-index). */
    @Query("DELETE FROM clusters WHERE faceCount = 0")
    suspend fun deleteEmpty()

    @Query("SELECT * FROM clusters WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): ClusterEntity?

    @Query("SELECT * FROM clusters ORDER BY faceCount DESC")
    suspend fun all(): List<ClusterEntity>

    @Query(
        """
        SELECT c.id AS id,
               c.displayName AS displayName,
               c.faceCount AS faceCount,
               p.uri AS coverPhotoUri
        FROM clusters c
        LEFT JOIN faces f ON f.id = c.coverFaceId
        LEFT JOIN photos p ON p.id = f.photoId
        WHERE c.faceCount >= :minSize
        ORDER BY c.faceCount DESC, c.updatedAt DESC
        """
    )
    fun summariesAtLeast(minSize: Int): Flow<List<ClusterSummary>>

    @Query("DELETE FROM clusters WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM clusters")
    suspend fun clear()
}
