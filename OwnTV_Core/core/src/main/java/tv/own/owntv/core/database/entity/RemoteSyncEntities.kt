package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One durable, idempotent user-data change waiting to be uploaded by the future remote transport.
 *
 * The logical-entity unique index deliberately coalesces repeated local changes made before an
 * upload (for example many progress ticks for the same film). REPLACE leaves only the latest event,
 * bounding the queue even while a device is offline for weeks.
 */
@Entity(
    tableName = "remote_sync_outbox",
    indices = [
        Index("profileUuid"),
        Index(value = ["nextAttemptAt", "occurredAt"]),
        Index(value = ["profileUuid", "entityType", "entityKey"], unique = true),
    ],
)
data class RemoteSyncOutboxEntity(
    @PrimaryKey val eventId: String,
    val profileUuid: String,
    /** Stable wire name such as `profile`, `favorite`, `history` or `progress`. */
    val entityType: String,
    /** Stable cross-platform identity, never a device-local Room row id. */
    val entityKey: String,
    /** `upsert` or `delete`. */
    val operation: String,
    /** Versioned JSON body. Null for a deletion. */
    val payload: String? = null,
    val occurredAt: Long,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
)

/** A server cursor and diagnostics for one account/synchronization scope. */
@Entity(tableName = "remote_sync_state")
data class RemoteSyncStateEntity(
    @PrimaryKey val scope: String,
    val remoteCursor: String? = null,
    val lastSuccessfulSyncAt: Long = 0,
    val lastAttemptAt: Long = 0,
    val lastError: String? = null,
)
