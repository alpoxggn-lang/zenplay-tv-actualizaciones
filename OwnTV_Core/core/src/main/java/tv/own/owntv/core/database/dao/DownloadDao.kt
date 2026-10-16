package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadStatus

/** Downloads (movies & series episodes only), per profile. */
@Dao
interface DownloadDao {
    /**
     * A second tap for the same stable item must keep the original row id. REPLACE deletes that row
     * before inserting another one, which leaves an already-running engine job detached from Room
     * while a second worker opens the same file. IGNORE makes enqueue genuinely idempotent.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(download: DownloadEntity): Long

    @Query(
        "SELECT * FROM downloads WHERE profileId = :profileId AND sourceId = :sourceId " +
            "AND contentKey = :contentKey LIMIT 1",
    )
    suspend fun getByIdentity(profileId: Long, sourceId: Long, contentKey: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE id = :id AND profileId = :profileId")
    suspend fun getByIdForProfile(id: Long, profileId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE profileId = :profileId")
    suspend fun getAllForProfile(profileId: Long): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE sourceId = :sourceId")
    suspend fun getAllForSource(sourceId: Long): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE id = :id AND profileId = :profileId")
    suspend fun deleteByIdForProfile(id: Long, profileId: Long): Int

    @Query("DELETE FROM downloads WHERE profileId = :profileId")
    suspend fun deleteAllForProfile(profileId: Long): Int

    @Query("DELETE FROM downloads WHERE sourceId = :sourceId")
    suspend fun deleteAllForSource(sourceId: Long): Int

    /** Number of live rows that still own this physical path (legacy rows may legitimately share). */
    @Query("SELECT COUNT(*) FROM downloads WHERE filePath = :filePath")
    suspend fun countForFilePath(filePath: String): Int

    @Query("SELECT * FROM downloads WHERE profileId = :profileId ORDER BY createdAt DESC")
    fun observeForProfile(profileId: Long): Flow<List<DownloadEntity>>

    /** Episode downloads belonging to one series (for the series poster-panel aggregate status strip). */
    @Query(
        "SELECT d.* FROM downloads d INNER JOIN episodes e ON e.id = d.itemId " +
            "WHERE d.profileId = :profileId AND d.mediaType = 'EPISODE' AND e.seriesId = :seriesId",
    )
    fun observeForSeries(profileId: Long, seriesId: Long): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY createdAt ASC")
    suspend fun byStatus(status: DownloadStatus): List<DownloadEntity>

    /**
     * The download queue, oldest first. RUNNING counts as pending: a row is only left in that state
     * when the process died mid-transfer, and it resumes from the partial file.
     */
    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'RUNNING') ORDER BY createdAt ASC")
    suspend fun pending(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE profileId = :profileId")
    fun count(profileId: Long): Flow<Int>

    @Query("UPDATE downloads SET status = :status, downloadedBytes = :downloaded, totalBytes = :total, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateProgress(id: Long, status: DownloadStatus, downloaded: Long, total: Long, timestamp: Long)

    @Query(
        "UPDATE downloads SET status = :status, downloadedBytes = :downloaded, totalBytes = :total, updatedAt = :timestamp " +
            "WHERE id = :id AND profileId = :profileId",
    )
    suspend fun updateProgressForProfile(
        id: Long,
        profileId: Long,
        status: DownloadStatus,
        downloaded: Long,
        total: Long,
        timestamp: Long,
    ): Int

    @Query(
        "UPDATE downloads SET itemId = :itemId, streamUrl = :streamUrl, updatedAt = :timestamp " +
            "WHERE id = :id AND profileId = :profileId AND sourceId = :sourceId AND contentKey = :contentKey",
    )
    suspend fun relink(
        id: Long,
        profileId: Long,
        sourceId: Long,
        contentKey: String,
        itemId: Long,
        streamUrl: String,
        timestamp: Long,
    ): Int

    @Query(
        "UPDATE downloads SET filePath = :filePath, updatedAt = :timestamp " +
            "WHERE id = :id AND profileId = :profileId",
    )
    suspend fun updateFilePathForProfile(id: Long, profileId: Long, filePath: String, timestamp: Long): Int
}
