package com.facealbum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    /**
     * Targeted stat update used by the clusterer. Deliberately never touches
     * displayName, so a concurrent user rename can't be clobbered by a
     * cached entity write. Returns the number of rows updated — 0 means the
     * cluster was deleted externally.
     */
    @Query(
        """
        UPDATE clusters
        SET centroid = :centroid,
            faceCount = :faceCount,
            coverFaceId = :coverFaceId,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateStats(
        id: Long,
        centroid: ByteArray,
        faceCount: Int,
        coverFaceId: Long?,
        updatedAt: Long
    ): Int

    @Query("UPDATE clusters SET displayName = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

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

    /**
     * The exact complement of [summariesAtLeast]: clusters with 0 < faceCount
     * < minSize. Every non-empty cluster lands in exactly one of these two
     * queries — this one uses `<` where the other uses `>=`, so a cluster of
     * precisely `minSize` faces is visible there and never repeated here, and
     * `faceCount = 0` (a cluster mid-recompute, or awaiting [deleteEmpty]) is
     * excluded from both rather than appearing as a phantom "unassigned"
     * group.
     *
     * These are the clusters the "Minimum group size" setting currently makes
     * unreachable outright — a person seen in one or two photos has no way to
     * be renamed or merged. Surfacing them under a "Review needed" entry
     * fixes that without touching clustering itself; see P4.3 notes for why
     * a second (lower) assign threshold in the clusterer was deliberately
     * left as a separate, future decision.
     */
    @Query(
        """
        SELECT c.id AS id,
               c.displayName AS displayName,
               c.faceCount AS faceCount,
               p.uri AS coverPhotoUri
        FROM clusters c
        LEFT JOIN faces f ON f.id = c.coverFaceId
        LEFT JOIN photos p ON p.id = f.photoId
        WHERE c.faceCount > 0 AND c.faceCount < :minSize
        ORDER BY c.faceCount DESC, c.updatedAt DESC
        """
    )
    fun summariesBelow(minSize: Int): Flow<List<ClusterSummary>>

    /** Total faces sitting in the below-threshold bucket, for the grid entry's subtitle. */
    @Query(
        """
        SELECT COALESCE(SUM(faceCount), 0) FROM clusters
        WHERE faceCount > 0 AND faceCount < :minSize
        """
    )
    fun reviewNeededFaceCount(minSize: Int): Flow<Int>

    @Query("DELETE FROM clusters WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Row count only. [all] would load every centroid BLOB just to size the
     * list, which is wasteful when the caller only wants a number.
     */
    @Query("SELECT COUNT(*) FROM clusters")
    suspend fun count(): Int

    @Query("DELETE FROM clusters")
    suspend fun clear()
}
