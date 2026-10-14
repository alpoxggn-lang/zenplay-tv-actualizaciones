package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.RemoteSyncOutboxEntity
import tv.own.owntv.core.database.entity.RemoteSyncStateEntity

/** Local half of remote synchronization. No network or credentials are handled here. */
@Dao
interface RemoteSyncDao {
    /**
     * Replaces an older pending mutation for the same logical entity through the table's unique
     * index. This is safe because nothing has been handed to a transport in Phase 1.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(event: RemoteSyncOutboxEntity)

    @Query(
        "SELECT * FROM remote_sync_outbox WHERE nextAttemptAt <= :now " +
            "ORDER BY occurredAt ASC, eventId ASC LIMIT :limit",
    )
    suspend fun ready(now: Long, limit: Int): List<RemoteSyncOutboxEntity>

    @Query("DELETE FROM remote_sync_outbox WHERE eventId IN (:eventIds)")
    suspend fun acknowledge(eventIds: List<String>)

    @Query(
        "UPDATE remote_sync_outbox SET attemptCount = attemptCount + 1, " +
            "nextAttemptAt = :nextAttemptAt, lastError = :error WHERE eventId = :eventId",
    )
    suspend fun markRetry(eventId: String, nextAttemptAt: Long, error: String?)

    @Query("SELECT COUNT(*) FROM remote_sync_outbox")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM remote_sync_outbox ORDER BY occurredAt ASC, eventId ASC")
    suspend fun getAllOutboxOnce(): List<RemoteSyncOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: RemoteSyncStateEntity)

    @Query("SELECT * FROM remote_sync_state WHERE scope = :scope")
    suspend fun state(scope: String): RemoteSyncStateEntity?

    @Query("SELECT * FROM remote_sync_state WHERE scope = :scope")
    fun observeState(scope: String): Flow<RemoteSyncStateEntity?>

    @Query("DELETE FROM remote_sync_state WHERE scope = :scope")
    suspend fun clearState(scope: String)
}
