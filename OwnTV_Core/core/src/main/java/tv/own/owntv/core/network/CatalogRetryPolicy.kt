package tv.own.owntv.core.network

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.random.Random

/** Supplies the random part of a catalogue retry without tying the policy to a global RNG. */
internal fun interface RetryRandom {
    /** Returns a value in `0 until [upperExclusive]`. */
    fun nextLong(upperExclusive: Long): Long
}

/** Wall clock used only to interpret an HTTP-date in `Retry-After`. */
internal fun interface RetryClock {
    fun epochMillis(): Long
}

/**
 * Retry timing for the small API/catalogue requests made by [HttpClient].
 *
 * The local delay uses exponential backoff with equal jitter: half of the exponential cap is fixed
 * and the other half is random. That keeps retries far enough apart while preventing several TVs
 * refreshing at the same time from hitting the provider again in lockstep.
 *
 * A valid HTTP `Retry-After` value is a lower bound. It may be either delay-seconds or an RFC 1123
 * date. Provider waits are capped so a bad header cannot keep an in-app refresh suspended forever;
 * the next scheduled refresh remains the long-wait recovery path.
 */
internal class CatalogRetryPolicy(
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val maxRetryAfterMs: Long = DEFAULT_MAX_RETRY_AFTER_MS,
    private val random: RetryRandom = RetryRandom { upperExclusive -> Random.Default.nextLong(upperExclusive) },
    private val clock: RetryClock = RetryClock { System.currentTimeMillis() },
) {
    init {
        require(baseDelayMs > 0L) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be at least baseDelayMs" }
        require(maxRetryAfterMs >= 0L) { "maxRetryAfterMs must not be negative" }
    }

    /** Delay after [failedAttempt] (one-based) and before the next request. */
    fun delayBeforeRetry(failedAttempt: Int, retryAfterHeader: String? = null): Long {
        require(failedAttempt >= 1) { "failedAttempt must be at least 1" }

        val cap = exponentialCap(failedAttempt)
        val floor = cap / 2L
        val randomRange = cap - floor
        val equalJitterDelay = floor + if (randomRange > 0L) random.nextLong(randomRange) else 0L
        val providerDelay = retryAfterMillis(retryAfterHeader)
        return maxOf(equalJitterDelay, providerDelay ?: 0L)
    }

    private fun exponentialCap(failedAttempt: Int): Long {
        var cap = baseDelayMs
        var doublings = failedAttempt - 1
        // Once the cap is reached there is no reason to loop through a very large attempt number.
        while (doublings > 0 && cap < maxDelayMs) {
            cap = if (cap > maxDelayMs / 2L) maxDelayMs else cap * 2L
            doublings--
        }
        return cap.coerceAtMost(maxDelayMs)
    }

    private fun retryAfterMillis(header: String?): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        value.toLongOrNull()?.takeIf { it >= 0L }?.let { seconds ->
            return seconds.saturatedSecondsToMillis().coerceAtMost(maxRetryAfterMs)
        }

        val targetMs = try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            return null
        }
        return (targetMs - clock.epochMillis()).coerceAtLeast(0L).coerceAtMost(maxRetryAfterMs)
    }

    private fun Long.saturatedSecondsToMillis(): Long =
        if (this > Long.MAX_VALUE / MILLIS_PER_SECOND) Long.MAX_VALUE else this * MILLIS_PER_SECOND

    private companion object {
        const val DEFAULT_BASE_DELAY_MS = 750L
        const val DEFAULT_MAX_DELAY_MS = 3_000L
        const val DEFAULT_MAX_RETRY_AFTER_MS = 60_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
