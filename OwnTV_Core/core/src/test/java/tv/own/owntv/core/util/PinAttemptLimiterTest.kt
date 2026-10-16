package tv.own.owntv.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinAttemptLimiterTest {
    private class FakeStore : PinAttemptStore {
        val values = mutableMapOf<String, PinAttemptRecord>()
        override fun read(key: String): PinAttemptRecord? = values[key]
        override fun write(key: String, record: PinAttemptRecord) { values[key] = record }
        override fun remove(key: String) { values.remove(key) }
    }

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

    @Test
    fun `cooldown and failed attempts survive process recreation in same boot`() {
        var elapsed = 10_000L
        var wall = 1_000_000L
        val store = FakeStore()
        fun limiter() = PinAttemptLimiter({ elapsed }, { wall }, { 7L }, store)

        repeat(PinAttemptLimiter.MAX_FAILURES) { limiter().verify("profile:1") { false } }
        var current = limiter()
        assertEquals(PinAttemptLimiter.BASE_COOLDOWN_MS, current.remainingDelayMs("profile:1"))
        assertFalse(current.verify("profile:1") { true })

        elapsed += PinAttemptLimiter.BASE_COOLDOWN_MS
        wall += PinAttemptLimiter.BASE_COOLDOWN_MS
        assertTrue(limiter().verify("profile:1") { true })
        assertFalse(store.values.containsKey("profile:1"))
    }

    @Test
    fun `cooldown is conservatively re-anchored after reboot`() {
        var elapsed = 20_000L
        var wall = 2_000_000L
        var boot = 3L
        val store = FakeStore()
        var limiter = PinAttemptLimiter({ elapsed }, { wall }, { boot }, store)
        repeat(PinAttemptLimiter.MAX_FAILURES) { limiter.verify("profile:2") { false } }

        elapsed = 1_000L
        wall += 8_000L
        boot++
        limiter = PinAttemptLimiter({ elapsed }, { wall }, { boot }, store)
        assertEquals(PinAttemptLimiter.BASE_COOLDOWN_MS, limiter.remainingDelayMs("profile:2"))
        assertFalse(limiter.verify("profile:2") { true })
    }

    @Test
    fun `backwards wall clock across reboot never clears cooldown`() {
        var elapsed = 20_000L
        var wall = 2_000_000L
        var boot = 3L
        val store = FakeStore()
        var limiter = PinAttemptLimiter({ elapsed }, { wall }, { boot }, store)
        repeat(PinAttemptLimiter.MAX_FAILURES) { limiter.verify("profile:3") { false } }

        elapsed = 1_000L
        wall -= 60_000L
        boot++
        limiter = PinAttemptLimiter({ elapsed }, { wall }, { boot }, store)
        assertEquals(PinAttemptLimiter.BASE_COOLDOWN_MS, limiter.remainingDelayMs("profile:3"))
        assertFalse(limiter.verify("profile:3") { true })
    }

    @Test
    fun `forward wall clock across reboot never clears cooldown`() {
        var elapsed = 20_000L
        var wall = 2_000_000L
        var boot = 3L
        val store = FakeStore()
        var limiter = PinAttemptLimiter({ elapsed }, { wall }, { boot }, store)
        repeat(PinAttemptLimiter.MAX_FAILURES) { limiter.verify("profile:4") { false } }

        elapsed = 1_000L
        wall += 365L * 24L * 60L * 60L * 1_000L
        boot++
        limiter = PinAttemptLimiter({ elapsed }, { wall }, { boot }, store)
        assertEquals(PinAttemptLimiter.BASE_COOLDOWN_MS, limiter.remainingDelayMs("profile:4"))
        assertFalse(limiter.verify("profile:4") { true })
    }

    @Test
    fun `reboot recovery is anchored once instead of restarting on every check`() {
        var elapsed = 20_000L
        var wall = 2_000_000L
        var boot = 3L
        val store = FakeStore()
        var limiter = PinAttemptLimiter({ elapsed }, { wall }, { boot }, store)
        repeat(PinAttemptLimiter.MAX_FAILURES) { limiter.verify("profile:5") { false } }

        elapsed = 1_000L
        wall += 8_000L
        boot++
        limiter = PinAttemptLimiter({ elapsed }, { wall }, { boot }, store)
        assertEquals(PinAttemptLimiter.BASE_COOLDOWN_MS, limiter.remainingDelayMs("profile:5"))
        elapsed += 10_000L
        assertEquals(PinAttemptLimiter.BASE_COOLDOWN_MS - 10_000L, limiter.remainingDelayMs("profile:5"))
    }
}
