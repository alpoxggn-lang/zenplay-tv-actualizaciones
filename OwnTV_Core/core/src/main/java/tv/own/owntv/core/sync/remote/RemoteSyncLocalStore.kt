package tv.own.owntv.core.sync.remote

import java.util.UUID
import tv.own.owntv.core.database.dao.RemoteSyncDao
import tv.own.owntv.core.database.entity.ProfileSyncId
import tv.own.owntv.core.database.entity.RemoteSyncOutboxEntity
import tv.own.owntv.core.database.entity.RemoteSyncStateEntity

/** Wire-stable entity names shared with the future Android/desktop synchronization contract. */
enum class RemoteSyncEntityType(val wireName: String) {
    PROFILE("profile"),
    FAVORITE("favorite"),
    HISTORY("history"),
    PROGRESS("progress"),
}

enum class RemoteSyncOperation(val wireName: String) {
    UPSERT("upsert"),
    DELETE("delete"),
}

fun interface RemoteSyncClock {
    fun nowMs(): Long
}

fun interface RemoteSyncEventIds {
    fun next(): String
}

/**
 * Creates transport-neutral outbox rows. Keeping UUID/time sources injectable makes queue behavior
 * deterministic in unit tests and prevents a future HTTP layer from inventing a second event shape.
 */
class RemoteSyncEventFactory(
    private val clock: RemoteSyncClock = RemoteSyncClock(System::currentTimeMillis),
    private val eventIds: RemoteSyncEventIds = RemoteSyncEventIds { UUID.randomUUID().toString() },
) {
    fun create(
        profileUuid: String,
        entityType: RemoteSyncEntityType,
        entityKey: String,
        operation: RemoteSyncOperation,
        payload: String? = null,
    ): RemoteSyncOutboxEntity {
        require(ProfileSyncId.isValid(profileUuid)) { "profileUuid must be a canonical UUID" }
        require(entityKey.isNotBlank()) { "entityKey must not be blank" }
        require(operation == RemoteSyncOperation.DELETE || !payload.isNullOrBlank()) {
            "upsert mutations require a payload"
        }
        val eventId = eventIds.next()
        require(ProfileSyncId.isValid(eventId)) { "eventId source returned a non-canonical UUID" }
        return RemoteSyncOutboxEntity(
            eventId = eventId,
            profileUuid = profileUuid,
            entityType = entityType.wireName,
            entityKey = entityKey,
            operation = operation.wireName,
            payload = payload.takeUnless { operation == RemoteSyncOperation.DELETE },
            occurredAt = clock.nowMs(),
        )
    }
}

/**
 * Durable local synchronization boundary. Phase 1 intentionally stops here: a later WorkManager
 * transport will read [ready], POST those idempotent event ids, acknowledge them, then store its
 * server cursor. Until that transport exists, no network request and no credential leaves Room.
 */
class RemoteSyncLocalStore(
    private val dao: RemoteSyncDao,
    private val factory: RemoteSyncEventFactory = RemoteSyncEventFactory(),
    private val clock: RemoteSyncClock = RemoteSyncClock(System::currentTimeMillis),
) {
    suspend fun enqueue(
        profileUuid: String,
        entityType: RemoteSyncEntityType,
        entityKey: String,
        operation: RemoteSyncOperation,
        payload: String? = null,
    ): RemoteSyncOutboxEntity = factory.create(
        profileUuid = profileUuid,
        entityType = entityType,
        entityKey = entityKey,
        operation = operation,
        payload = payload,
    ).also { dao.enqueue(it) }

    suspend fun ready(limit: Int = DEFAULT_BATCH_SIZE): List<RemoteSyncOutboxEntity> {
        require(limit in 1..MAX_BATCH_SIZE) { "limit must be between 1 and $MAX_BATCH_SIZE" }
        return dao.ready(clock.nowMs(), limit)
    }

    suspend fun acknowledge(eventIds: Collection<String>) {
        if (eventIds.isNotEmpty()) dao.acknowledge(eventIds.distinct())
    }

    suspend fun markRetry(eventId: String, nextAttemptAt: Long, error: String?) {
        require(ProfileSyncId.isValid(eventId)) { "eventId must be a canonical UUID" }
        require(nextAttemptAt >= 0) { "nextAttemptAt must not be negative" }
        dao.markRetry(eventId, nextAttemptAt, error?.take(MAX_ERROR_LENGTH))
    }

    suspend fun state(scope: String): RemoteSyncStateEntity? = dao.state(validScope(scope))

    suspend fun recordAttempt(scope: String) {
        val key = validScope(scope)
        val old = dao.state(key)
        dao.putState(
            (old ?: RemoteSyncStateEntity(scope = key)).copy(
                lastAttemptAt = clock.nowMs(),
                lastError = null,
            ),
        )
    }

    suspend fun recordSuccess(scope: String, cursor: String?) {
        val key = validScope(scope)
        val now = clock.nowMs()
        val old = dao.state(key)
        dao.putState(
            (old ?: RemoteSyncStateEntity(scope = key)).copy(
                remoteCursor = cursor,
                lastSuccessfulSyncAt = now,
                lastAttemptAt = now,
                lastError = null,
            ),
        )
    }

    suspend fun recordFailure(scope: String, error: String?) {
        val key = validScope(scope)
        val old = dao.state(key)
        dao.putState(
            (old ?: RemoteSyncStateEntity(scope = key)).copy(
                lastAttemptAt = clock.nowMs(),
                lastError = error?.take(MAX_ERROR_LENGTH),
            ),
        )
    }

    private fun validScope(scope: String): String = scope.trim().also {
        require(it.isNotEmpty()) { "scope must not be blank" }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 100
        const val MAX_BATCH_SIZE = 500
        private const val MAX_ERROR_LENGTH = 512
    }
}
