package tv.own.owntv.core.recording

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.RecordingEntity

class RecordingExecutionGateTest {
    private fun recording(
        profileId: Long = 7L,
        sourceId: Long = 11L,
        channelId: Long = 13L,
    ) = RecordingEntity(
        profileId = profileId,
        sourceId = sourceId,
        channelId = channelId,
        channelName = "Scheduled channel",
        streamUrl = "https://stream.invalid/live.ts",
        title = "Scheduled programme",
        programmeStartMs = 1_000L,
        programmeStopMs = 2_000L,
        startMs = 900L,
        stopMs = 2_100L,
    )

    private fun channel(id: Long = 13L, sourceId: Long = 11L) = ChannelEntity(
        id = id,
        sourceId = sourceId,
        name = "Persisted channel",
        streamUrl = "https://stream.invalid/current.ts",
    )

    @Test
    fun `missing persisted channel fails closed without consulting policy`() = runBlocking {
        var policyCalls = 0

        val allowed = recordingExecutionAllowed(
            row = recording(),
            channelById = { null },
            authorize = { _, _ -> policyCalls++; true },
        )

        assertFalse(allowed)
        assertEquals(0, policyCalls)
    }

    @Test
    fun `stale channel id pointing at another source fails closed`() = runBlocking {
        var policyCalls = 0

        val allowed = recordingExecutionAllowed(
            row = recording(sourceId = 11L),
            channelById = { channel(sourceId = 99L) },
            authorize = { _, _ -> policyCalls++; true },
        )

        assertFalse(allowed)
        assertEquals(0, policyCalls)
    }

    @Test
    fun `current profile and persisted channel are passed to final policy`() = runBlocking {
        val seen = mutableListOf<Pair<Long, ChannelEntity>>()
        val persisted = channel()

        val denied = recordingExecutionAllowed(
            row = recording(profileId = 42L),
            channelById = { id -> persisted.takeIf { it.id == id } },
            authorize = { profileId, resolved -> seen += profileId to resolved; false },
        )

        assertFalse(denied)
        assertEquals(listOf(42L to persisted), seen)
    }

    @Test
    fun `recording may start only when persisted identity and policy both allow it`() = runBlocking {
        val row = recording()
        val persisted = channel()

        val allowed = recordingExecutionAllowed(
            row = row,
            channelById = { persisted },
            authorize = { profileId, resolved -> profileId == row.profileId && resolved === persisted },
        )

        assertTrue(allowed)
    }
}
