package tv.own.owntv.mobile.ui.screens.guide

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

class GuideRecordingAccessTest {
    private fun channel(
        id: Long = 7L,
        sourceId: Long = 11L,
        categoryId: Long? = 13L,
        streamUrl: String = "https://provider.example/live",
    ) = ChannelEntity(
        id = id,
        sourceId = sourceId,
        categoryId = categoryId,
        name = "Canal",
        streamUrl = streamUrl,
    )

    @Test
    fun `missing active profile fails closed before database access`() = runBlocking {
        var loads = 0

        val result = authorizePersistedGuideChannel(
            profileId = null,
            requested = channel(),
            load = { loads++; channel() },
            allows = { _, _ -> true },
        )

        assertNull(result)
        assertEquals(0, loads)
    }

    @Test
    fun `missing or source-replaced persisted channel fails closed`() = runBlocking {
        val requested = channel(sourceId = 11L)

        assertNull(
            authorizePersistedGuideChannel(
                profileId = 3L,
                requested = requested,
                load = { null },
                allows = { _, _ -> true },
            ),
        )
        assertNull(
            authorizePersistedGuideChannel(
                profileId = 3L,
                requested = requested,
                load = { channel(sourceId = 99L) },
                allows = { _, _ -> true },
            ),
        )
    }

    @Test
    fun `policy receives and mutation returns only persisted identity`() = runBlocking {
        val requested = channel(categoryId = 1L, streamUrl = "https://stale.example/live")
        val stored = channel(categoryId = 22L, streamUrl = "https://provider.example/current")
        var authorized: ChannelEntity? = null

        val result = authorizePersistedGuideChannel(
            profileId = 3L,
            requested = requested,
            load = { stored },
            allows = { _, candidate ->
                authorized = candidate
                candidate.categoryId == 22L
            },
        )

        assertSame(stored, authorized)
        assertSame(stored, result)
    }

    @Test
    fun `policy denial fails closed`() = runBlocking {
        val stored = channel()

        val result = authorizePersistedGuideChannel(
            profileId = 3L,
            requested = stored,
            load = { stored },
            allows = { _, _ -> false },
        )

        assertNull(result)
    }
}
