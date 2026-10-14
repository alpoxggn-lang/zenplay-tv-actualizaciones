package tv.own.owntv.core.network

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogRetryPolicyTest {
    @Test
    fun `equal jitter stays in the upper half of each exponential window`() {
        val lowerBound = CatalogRetryPolicy(random = RetryRandom { 0L })
        val upperBound = CatalogRetryPolicy(random = RetryRandom { upper -> upper - 1L })

        assertEquals(375L, lowerBound.delayBeforeRetry(failedAttempt = 1))
        assertEquals(749L, upperBound.delayBeforeRetry(failedAttempt = 1))
        assertEquals(750L, lowerBound.delayBeforeRetry(failedAttempt = 2))
        assertEquals(1_499L, upperBound.delayBeforeRetry(failedAttempt = 2))
        assertEquals(1_500L, lowerBound.delayBeforeRetry(failedAttempt = 3))
        assertEquals(2_999L, upperBound.delayBeforeRetry(failedAttempt = 3))
    }

    @Test
    fun `exponential window stops growing at its cap`() {
        val policy = CatalogRetryPolicy(random = RetryRandom { 0L })

        assertEquals(1_500L, policy.delayBeforeRetry(failedAttempt = 4))
        assertEquals(1_500L, policy.delayBeforeRetry(failedAttempt = Int.MAX_VALUE))
    }

    @Test
    fun `numeric Retry-After is a minimum wait and is safely capped`() {
        val policy = CatalogRetryPolicy(random = RetryRandom { 0L })

        assertEquals(12_000L, policy.delayBeforeRetry(failedAttempt = 1, retryAfterHeader = " 12 "))
        assertEquals(60_000L, policy.delayBeforeRetry(failedAttempt = 1, retryAfterHeader = Long.MAX_VALUE.toString()))
    }

    @Test
    fun `HTTP-date Retry-After uses the injected clock`() {
        val nowMs = 1_700_000_000_000L
        val retryAt = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(nowMs + 8_000L).atZone(ZoneOffset.UTC),
        )
        val policy = CatalogRetryPolicy(
            random = RetryRandom { 0L },
            clock = RetryClock { nowMs },
        )

        assertEquals(8_000L, policy.delayBeforeRetry(failedAttempt = 1, retryAfterHeader = retryAt))
    }

    @Test
    fun `past or malformed Retry-After falls back to equal jitter`() {
        val nowMs = 1_700_000_000_000L
        val past = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(nowMs - 10_000L).atZone(ZoneOffset.UTC),
        )
        val policy = CatalogRetryPolicy(
            random = RetryRandom { 0L },
            clock = RetryClock { nowMs },
        )

        assertEquals(375L, policy.delayBeforeRetry(failedAttempt = 1, retryAfterHeader = past))
        assertEquals(375L, policy.delayBeforeRetry(failedAttempt = 1, retryAfterHeader = "not-a-date"))
    }

    @Test
    fun `invalid attempt is rejected without consuming randomness`() {
        var randomCalls = 0
        val policy = CatalogRetryPolicy(random = RetryRandom { randomCalls++; 0L })

        assertThrows(IllegalArgumentException::class.java) {
            policy.delayBeforeRetry(failedAttempt = 0)
        }
        assertEquals(0, randomCalls)
    }
}
