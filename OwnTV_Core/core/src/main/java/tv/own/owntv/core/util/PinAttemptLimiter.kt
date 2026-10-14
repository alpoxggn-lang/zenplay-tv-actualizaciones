package tv.own.owntv.core.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide brute-force guard for every local PIN prompt.
 *
 * Five consecutive failures start a 30-second cooldown. Further failures after each cooldown
 * double the delay up to five minutes; a valid PIN clears the state. The monotonic clock keeps a
 * device time change from skipping the wait.
 */
class PinAttemptLimiter(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class State(var failures: Int = 0, var blockedUntilMs: Long = 0L)

    private val states = ConcurrentHashMap<String, State>()

    @Synchronized
    fun verify(key: String, check: () -> Boolean): Boolean {
        val now = clockMs()
        val state = states.getOrPut(key) { State() }
        if (now < state.blockedUntilMs) return false
        if (check()) {
            states.remove(key)
            return true
        }
        state.failures++
        if (state.failures >= MAX_FAILURES) {
            val exponent = (state.failures - MAX_FAILURES).coerceIn(0, MAX_EXPONENT)
            state.blockedUntilMs = now + (BASE_COOLDOWN_MS shl exponent).coerceAtMost(MAX_COOLDOWN_MS)
        }
        return false
    }

    @Synchronized
    fun remainingDelayMs(key: String): Long =
        ((states[key]?.blockedUntilMs ?: 0L) - clockMs()).coerceAtLeast(0L)

    companion object {
        const val MAX_FAILURES = 5
        const val BASE_COOLDOWN_MS = 30_000L
        const val MAX_COOLDOWN_MS = 300_000L
        private const val MAX_EXPONENT = 4
    }
}

/** One guard shared by TV and touch shells, settings dialogs and Core mutation revalidation. */
object PinAttemptGuard {
    private val limiter = PinAttemptLimiter()

    fun verify(key: String, check: () -> Boolean): Boolean = limiter.verify(key, check)
    fun remainingDelayMs(key: String): Long = limiter.remainingDelayMs(key)
}
