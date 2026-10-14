package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodSeekPolicyTest {
    @Test
    fun `forward seek stays before natural end and preserves play intent`() {
        val plan = VodSeekPolicy.plan(
            currentMs = 98_000L,
            deltaMs = 10_000L,
            durationMs = 100_000L,
            wasPlaying = true,
        )

        assertEquals(98_000L, plan.targetMs)
        assertTrue(plan.resumeAfterSeek)
        assertFalse(plan.moves)
    }

    @Test
    fun `backward seek is clamped at zero and a paused item stays paused`() {
        val plan = VodSeekPolicy.plan(
            currentMs = 3_000L,
            deltaMs = -10_000L,
            durationMs = 120_000L,
            wasPlaying = false,
        )

        assertEquals(0L, plan.targetMs)
        assertFalse(plan.resumeAfterSeek)
    }

    @Test
    fun `large deltas saturate instead of overflowing`() {
        val plan = VodSeekPolicy.plan(
            currentMs = Long.MAX_VALUE - 5L,
            deltaMs = 50L,
            durationMs = 0L,
            wasPlaying = true,
        )

        assertEquals(Long.MAX_VALUE, plan.targetMs)
    }

    @Test
    fun `absolute timeline seek keeps one stable target and clamps the end`() {
        val plan = VodSeekPolicy.planTo(
            currentMs = 10_000L,
            targetMs = 99_900L,
            durationMs = 100_000L,
            wasPlaying = true,
        )

        assertEquals(10_000L, plan.startMs)
        assertEquals(98_000L, plan.targetMs)
        assertTrue(plan.resumeAfterSeek)
    }

    @Test
    fun `seek is healthy only after landing and clock resumes`() {
        val plan = VodSeekPolicy.plan(
            currentMs = 10_000L,
            deltaMs = 30_000L,
            durationMs = 120_000L,
            wasPlaying = true,
        )

        assertFalse(VodSeekPolicy.landed(plan, 12_000L))
        assertTrue(VodSeekPolicy.landed(plan, 36_000L))
        assertFalse(VodSeekPolicy.resumed(36_000L, 36_499L))
        assertTrue(VodSeekPolicy.resumed(36_000L, 36_500L))
    }

    @Test
    fun `backward seek does not count the old position as a landing`() {
        val plan = VodSeekPolicy.plan(
            currentMs = 3_000L,
            deltaMs = -10_000L,
            durationMs = 120_000L,
            wasPlaying = true,
        )

        assertFalse(VodSeekPolicy.landed(plan, 3_000L))
        assertTrue(VodSeekPolicy.landed(plan, 0L))
    }

    @Test
    fun `ranged seek is not recovered while decoder remains buffering`() {
        val plan = VodSeekPolicy.planTo(
            currentMs = 10_000L,
            targetMs = 40_000L,
            durationMs = 120_000L,
            wasPlaying = true,
        )

        assertFalse(
            VodSeekPolicy.recovered(
                plan = plan,
                landedAtMs = 40_000L,
                positionMs = 41_000L,
                isPlaying = true,
                isBuffering = true,
            ),
        )
        assertFalse(
            VodSeekPolicy.recovered(
                plan = plan,
                landedAtMs = 40_000L,
                positionMs = 41_000L,
                isPlaying = false,
                isBuffering = false,
            ),
        )
        assertTrue(
            VodSeekPolicy.recovered(
                plan = plan,
                landedAtMs = 40_000L,
                positionMs = 41_000L,
                isPlaying = true,
                isBuffering = false,
            ),
        )
    }

    @Test
    fun `paused seek completes after landing without resuming the clock`() {
        val plan = VodSeekPolicy.planTo(
            currentMs = 10_000L,
            targetMs = 40_000L,
            durationMs = 120_000L,
            wasPlaying = false,
        )

        assertFalse(
            VodSeekPolicy.recovered(
                plan = plan,
                landedAtMs = 40_000L,
                positionMs = 40_000L,
                isPlaying = false,
                isBuffering = true,
            ),
        )
        assertTrue(
            VodSeekPolicy.recovered(
                plan = plan,
                landedAtMs = 40_000L,
                positionMs = 40_000L,
                isPlaying = false,
                isBuffering = false,
            ),
        )
    }

    @Test
    fun `pause and a newer scrub invalidate outgoing watchdog tickets`() {
        val state = VodSeekRecoveryState()
        val first = state.begin(
            VodSeekPolicy.planTo(10_000L, 40_000L, 120_000L, wasPlaying = true),
        )!!
        val paused = state.updatePlayIntent(resumeAfterSeek = false)!!

        assertFalse(state.isCurrent(first))
        assertTrue(state.isCurrent(paused))
        assertFalse(state.complete(first))

        val newer = state.begin(
            VodSeekPolicy.planTo(40_000L, 80_000L, 120_000L, wasPlaying = false),
        )!!
        assertFalse(state.isCurrent(paused))
        assertTrue(state.isCurrent(newer))
        assertFalse(state.complete(paused))
        assertTrue(state.complete(newer))
        assertFalse(state.isCurrent(newer))
    }

    @Test
    fun `unconfirmed saved position wins over transient engine zero during fallback`() {
        assertEquals(
            2_720_000L,
            VodSeekPolicy.recoveryPosition(
                explicitMs = null,
                pendingTargetMs = 2_720_000L,
                currentPositionMs = 0L,
                fileOpened = true,
            ),
        )
        assertEquals(
            2_720_000L,
            VodSeekPolicy.recoveryPosition(
                explicitMs = null,
                pendingTargetMs = 2_720_000L,
                currentPositionMs = 1_000L,
                fileOpened = true,
            ),
        )
    }
}
