package tv.own.owntv.core.backup

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.ProfileEntity

class BackupProfileIdentityTest {
    private val localUuid = "550e8400-e29b-41d4-a716-446655440000"
    private val incomingUuid = "67e55044-10b1-426f-9247-bb680e5fe0c8"
    private val thirdUuid = "123e4567-e89b-42d3-a456-426614174000"

    @Test
    fun `uuid match wins over a coincidental name match`() {
        val uuidOwner = profile(1, "Renamed on TV", incomingUuid)
        val sameName = profile(2, "Family", localUuid)
        val incoming = profile(90, "Family", incomingUuid)

        assertEquals(uuidOwner, matchProfileForRestore(listOf(uuidOwner, sameName), incoming))
    }

    @Test
    fun `fresh local profile adopts backup uuid on name fallback`() {
        val local = profile(1, "Family", localUuid)
        val incoming = profile(90, " family ", incomingUuid)

        assertEquals(local, matchProfileForRestore(listOf(local), incoming))
        assertEquals(
            incomingUuid,
            syncUuidForRestore(local, incoming, mapOf(localUuid to local)),
        )
    }

    @Test
    fun `incoming uuid already owned by another row is never stolen`() {
        val local = profile(1, "Family", localUuid)
        val owner = profile(2, "Guest", incomingUuid)
        val incoming = profile(90, "Family", incomingUuid)

        assertEquals(
            localUuid,
            syncUuidForRestore(local, incoming, mapOf(localUuid to local, incomingUuid to owner)),
        )
    }

    private fun profile(id: Long, name: String, uuid: String) = ProfileEntity(
        id = id,
        name = name,
        avatarColor = 0,
        syncUuid = uuid,
    )
}
