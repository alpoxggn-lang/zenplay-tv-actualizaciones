package tv.own.owntv.features.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingStatus

class PlayerRecordingStateTest {
    private fun recording(
        id: Long,
        channelId: Long,
        status: RecordingStatus = RecordingStatus.RECORDING,
        stopMs: Long = 10_000L,
    ) = RecordingEntity(
        id = id,
        profileId = 7L,
        sourceId = 3L,
        channelId = channelId,
        channelName = "Channel $channelId",
        streamUrl = "https://example.invalid/$channelId.ts",
        title = "Channel $channelId",
        programmeStartMs = 1_000L,
        programmeStopMs = stopMs,
        startMs = 1_000L,
        stopMs = stopMs,
        status = status,
    )

    @Test
    fun recordingIsBoundToItsOwnChannelAndId() {
        val channelA = recording(id = 31L, channelId = 100L)
        val result = reconcilePlayerRecordings(
            tracked = mapOf(100L to channelA),
            stored = listOf(channelA),
            nowMs = 2_000L,
        )

        assertEquals(31L, result[100L]?.id)
        assertFalse(result.containsKey(200L))
    }

    @Test
    fun terminalOrTimedOutRecordingDisappearsFromHudState() {
        val running = recording(id = 31L, channelId = 100L)
        val completed = running.copy(status = RecordingStatus.COMPLETED)

        assertTrue(
            reconcilePlayerRecordings(mapOf(100L to running), listOf(completed), 2_000L).isEmpty(),
        )
        assertTrue(
            reconcilePlayerRecordings(mapOf(100L to running), listOf(running), 10_001L).isEmpty(),
        )
    }

    @Test
    fun anotherRecordingIdCannotReplaceTrackedChannel() {
        val tracked = recording(id = 31L, channelId = 100L)
        val unrelated = recording(id = 99L, channelId = 100L)

        val result = reconcilePlayerRecordings(
            tracked = mapOf(100L to tracked),
            stored = listOf(unrelated),
            nowMs = 2_000L,
        )

        assertEquals(31L, result.getValue(100L).id)
    }
}
