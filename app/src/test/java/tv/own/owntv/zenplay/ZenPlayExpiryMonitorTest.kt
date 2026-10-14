package tv.own.owntv.zenplay

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.parser.XtreamClient

class ZenPlayExpiryMonitorTest {
    private val zone = ZoneId.of("America/Tijuana")

    @Test
    fun warningWindowUsesCalendarDaysNotTwentyFourHourBuckets() {
        val now = LocalDate.of(2026, 10, 14).atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
        val tomorrow = LocalDate.of(2026, 10, 15).atTime(0, 5).atZone(zone).toInstant().toEpochMilli()

        assertEquals(1L, daysUntilExpiry(now, tomorrow, zone))
    }

    @Test
    fun expiryDayIsDayZero() {
        val now = LocalDate.of(2026, 10, 14).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val later = LocalDate.of(2026, 10, 14).atTime(22, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(0L, daysUntilExpiry(now, later, zone))
    }

    @Test
    fun datesOutsideWarningWindowRemainOutside() {
        val now = LocalDate.of(2026, 10, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        val later = LocalDate.of(2026, 10, 17).atStartOfDay(zone).toInstant().toEpochMilli()

        assertEquals(3L, daysUntilExpiry(now, later, zone))
    }

    @Test
    fun revokedAccountWinsOverFutureDate() {
        val result = classifyZenPlaySubscription(
            hasAccount = true,
            responses = listOf(
                Result.success(
                    XtreamClient.XtAccountDetails(
                        status = "Banned",
                        authOk = true,
                        expiryMs = Long.MAX_VALUE,
                    ),
                ),
            ),
        )

        assertEquals(ZenPlaySubscriptionState.Revoked, result)
    }

    @Test
    fun renewedFutureDateCancelsObsoleteWarning() {
        val now = LocalDate.of(2026, 10, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        val renewed = LocalDate.of(2027, 10, 14).atStartOfDay(zone).toInstant().toEpochMilli()

        assertEquals(
            false,
            shouldKeepExpiryNotification(ZenPlaySubscriptionState.Dated(renewed), now, zone),
        )
    }

    @Test
    fun tomorrowStillKeepsExpiryWarning() {
        val now = LocalDate.of(2026, 10, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrow = LocalDate.of(2026, 10, 15).atStartOfDay(zone).toInstant().toEpochMilli()

        assertEquals(
            true,
            shouldKeepExpiryNotification(ZenPlaySubscriptionState.Dated(tomorrow), now, zone),
        )
    }
}
