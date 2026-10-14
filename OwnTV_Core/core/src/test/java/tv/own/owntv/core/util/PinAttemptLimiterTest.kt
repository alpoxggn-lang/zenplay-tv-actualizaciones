package tv.own.owntv.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinAttemptLimiterTest {
    @Test
    fun `five failures block verification until cooldown expires`() {
        var now = 1_000L
        val limiter = PinAttemptLimiter { now }
        repeat(PinAttemptLimiter.MAX_FAILURES) {
            assertFalse(limiter.verify("profile:7") { false })
        }
        assertEquals(PinAttemptLimiter.BASE_COOLDOWN_MS, limiter.remainingDelayMs("profile:7"))
        assertFalse("a correct PIN is not evaluated during cooldown", limiter.verify("profile:7") { true })

        now += PinAttemptLimiter.BASE_COOLDOWN_MS
        assertTrue(limiter.verify("profile:7") { true })
        assertEquals(0L, limiter.remainingDelayMs("profile:7"))
    }

    @Test
    fun `attempt buckets are independent`() {
        val limiter = PinAttemptLimiter { 0L }
        repeat(PinAttemptLimiter.MAX_FAILURES) { limiter.verify("profile:1") { false } }
        assertFalse(limiter.verify("profile:1") { true })
        assertTrue(limiter.verify("profile:2") { true })
    }
}
