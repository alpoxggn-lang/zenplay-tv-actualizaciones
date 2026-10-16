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
        channelRemoteId: String? = null,
        epgChannelId: String? = null,
    ) = RecordingEntity(
        profileId = profileId,
        sourceId = sourceId,
        channelId = channelId,
        channelRemoteId = channelRemoteId,
        channelName = "Scheduled channel",
        epgChannelId = epgChannelId,
        streamUrl = "https://stream.invalid/live.ts",
        title = "Scheduled programme",
        programmeStartMs = 1_000L,
        programmeStopMs = 2_000L,
        startMs = 900L,
        stopMs = 2_100L,
    )

    private fun channel(
        id: Long = 13L,
        sourceId: Long = 11L,
        remoteId: String? = null,
        epgChannelId: String? = null,
    ) = ChannelEntity(
        id = id,
        sourceId = sourceId,
        name = "Persisted channel",
        streamUrl = "https://stream.invalid/current.ts",
        remoteId = remoteId,
        epgChannelId = epgChannelId,
    )

    @Test
    fun `stale local id is rebound by stable provider identity`() = runBlocking {
        val replacement = channel(id = 77L, remoteId = "xtream-42", epgChannelId = "news.example")
        val resolved = resolveRecordingChannel(
            row = recording(channelRemoteId = "xtream-42", epgChannelId = "news.example"),
            channelById = { null },
            channelByRemote = { sourceId, remoteId ->
                replacement.takeIf { sourceId == 11L && remoteId == "xtream-42" }
            },
            channelsByEpg = { _, _ -> error("stable identity must win") },
        )

        assertEquals(replacement, resolved)
    }

    @Test
    fun `stable provider identity with a different epg identity fails closed`() = runBlocking {
        val resolved = resolveRecordingChannel(
            row = recording(channelRemoteId = "xtream-42", epgChannelId = "expected-guide"),
            channelById = { null },
            channelByRemote = { _, _ -> channel(id = 77L, remoteId = "xtream-42", epgChannelId = "other-guide") },
            channelsByEpg = { _, _ -> error("a mismatched stable identity must not fall back") },
        )

        assertEquals(null, resolved)
    }

    @Test
    fun `legacy timer is rebound only by one source scoped epg match`() = runBlocking {
        val replacement = channel(id = 77L, epgChannelId = " News.Example ")
        val resolved = resolveRecordingChannel(
            row = recording(epgChannelId = "news.example"),
            channelById = { null },
            channelByRemote = { _, _ -> null },
            channelsByEpg = { sourceId, epg ->
                listOf(replacement).takeIf { sourceId == 11L && epg == "news.example" }.orEmpty()
            },
        )

        assertEquals(replacement, resolved)
    }

    @Test
    fun `ambiguous epg identity fails closed`() = runBlocking {
        val resolved = resolveRecordingChannel(
            row = recording(epgChannelId = "shared-guide"),
            channelById = { null },
            channelByRemote = { _, _ -> null },
            channelsByEpg = { _, _ ->
                listOf(
                    channel(id = 77L, epgChannelId = "shared-guide"),
                    channel(id = 78L, epgChannelId = "shared-guide"),
                )
            },
        )

        assertEquals(null, resolved)
    }

    @Test
    fun `missing stable provider identity never falls back to epg`() = runBlocking {
        var epgLookups = 0
        val resolved = resolveRecordingChannel(
            row = recording(channelRemoteId = "gone", epgChannelId = "shared-guide"),
            channelById = { null },
            channelByRemote = { _, _ -> null },
            channelsByEpg = { _, _ -> epgLookups++; listOf(channel(epgChannelId = "shared-guide")) },
        )

        assertEquals(null, resolved)
        assertEquals(0, epgLookups)
    }

    @Test
    fun `source and local row id alone are not a safe legacy identity`() = runBlocking {
        val resolved = resolveRecordingChannel(
            row = recording(),
            channelById = { channel(id = 13L, sourceId = 11L) },
            channelByRemote = { _, _ -> null },
            channelsByEpg = { _, _ -> emptyList() },
        )

        assertEquals(null, resolved)
    }

    @Test
    fun `identityless legacy timer keeps an unchanged exact local channel`() = runBlocking {
        val current = channel().copy(
            name = "Scheduled channel",
            streamUrl = "https://stream.invalid/live.ts",
        )
        val resolved = resolveRecordingChannel(
            row = recording(),
            channelById = { current },
            channelByRemote = { _, _ -> null },
            channelsByEpg = { _, _ -> emptyList() },
        )

        assertEquals(current, resolved)
    }

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
