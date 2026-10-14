package tv.own.owntv.core.sync.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.own.owntv.core.database.dao.RemoteSyncDao
import tv.own.owntv.core.database.entity.RemoteSyncOutboxEntity
import tv.own.owntv.core.database.entity.RemoteSyncStateEntity

class RemoteSyncLocalStoreTest {
    private val profileUuid = "550e8400-e29b-41d4-a716-446655440000"
    private val eventUuid = "67e55044-10b1-426f-9247-bb680e5fe0c8"

    @Test
    fun `factory creates deterministic upsert and delete wire rows`() {
        val factory = RemoteSyncEventFactory(
            clock = RemoteSyncClock { 1234L },
            eventIds = RemoteSyncEventIds { eventUuid },
        )

        val upsert = factory.create(
            profileUuid,
            RemoteSyncEntityType.PROGRESS,
            "movie:42",
            RemoteSyncOperation.UPSERT,
            "{\"positionMs\":9000}",
        )
        assertEquals(eventUuid, upsert.eventId)
        assertEquals("progress", upsert.entityType)
        assertEquals("upsert", upsert.operation)
        assertEquals(1234L, upsert.occurredAt)

        val deletion = factory.create(
            profileUuid,
            RemoteSyncEntityType.FAVORITE,
            "movie:42",
            RemoteSyncOperation.DELETE,
            "ignored",
        )
        assertEquals("delete", deletion.operation)
        assertNull(deletion.payload)
    }

    @Test
    fun `factory rejects unstable identity and payload-free upsert`() {
        val factory = RemoteSyncEventFactory(eventIds = RemoteSyncEventIds { eventUuid })

        assertThrows(IllegalArgumentException::class.java) {
            factory.create("local-id", RemoteSyncEntityType.HISTORY, "movie:1", RemoteSyncOperation.DELETE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(profileUuid, RemoteSyncEntityType.HISTORY, "movie:1", RemoteSyncOperation.UPSERT)
        }
    }

    @Test
    fun `cursor survives a later failed attempt and advances only on success`() = runBlocking {
        var now = 100L
        val dao = FakeRemoteSyncDao()
        val store = RemoteSyncLocalStore(dao, clock = RemoteSyncClock { now })

        store.recordSuccess("account", "cursor-7")
        now = 200L
        store.recordFailure("account", "offline")

        assertEquals("cursor-7", store.state("account")?.remoteCursor)
        assertEquals(100L, store.state("account")?.lastSuccessfulSyncAt)
        assertEquals(200L, store.state("account")?.lastAttemptAt)
        assertEquals("offline", store.state("account")?.lastError)

        now = 300L
        store.recordSuccess("account", "cursor-8")
        assertEquals("cursor-8", store.state("account")?.remoteCursor)
        assertEquals(300L, store.state("account")?.lastSuccessfulSyncAt)
        assertNull(store.state("account")?.lastError)
    }

    @Test
    fun `ready enforces bounded transport batches`() = runBlocking {
        val store = RemoteSyncLocalStore(FakeRemoteSyncDao())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.ready(0) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.ready(RemoteSyncLocalStore.MAX_BATCH_SIZE + 1) }
        }
        Unit
    }

    private class FakeRemoteSyncDao : RemoteSyncDao {
        private val outbox = LinkedHashMap<String, RemoteSyncOutboxEntity>()
        private val states = HashMap<String, RemoteSyncStateEntity>()
        private val pending = MutableStateFlow(0)

        override suspend fun enqueue(event: RemoteSyncOutboxEntity) {
            val old = outbox.values.firstOrNull {
                it.profileUuid == event.profileUuid && it.entityType == event.entityType && it.entityKey == event.entityKey
            }
            old?.let { outbox.remove(it.eventId) }
            outbox[event.eventId] = event
            pending.value = outbox.size
        }

        override suspend fun ready(now: Long, limit: Int): List<RemoteSyncOutboxEntity> =
            outbox.values.filter { it.nextAttemptAt <= now }.sortedWith(
                compareBy<RemoteSyncOutboxEntity> { it.occurredAt }.thenBy { it.eventId },
            ).take(limit)

        override suspend fun acknowledge(eventIds: List<String>) {
            eventIds.forEach(outbox::remove)
            pending.value = outbox.size
        }

        override suspend fun markRetry(eventId: String, nextAttemptAt: Long, error: String?) {
            outbox[eventId]?.let {
                outbox[eventId] = it.copy(
                    attemptCount = it.attemptCount + 1,
                    nextAttemptAt = nextAttemptAt,
                    lastError = error,
                )
            }
        }

        override fun observePendingCount(): Flow<Int> = pending

        override suspend fun getAllOutboxOnce(): List<RemoteSyncOutboxEntity> = outbox.values.toList()

        override suspend fun putState(state: RemoteSyncStateEntity) {
            states[state.scope] = state
        }

        override suspend fun state(scope: String): RemoteSyncStateEntity? = states[scope]

        override fun observeState(scope: String): Flow<RemoteSyncStateEntity?> =
            MutableStateFlow(states[scope])

        override suspend fun clearState(scope: String) {
            states.remove(scope)
        }
    }
}
