package tv.own.owntv.features.live

import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenSchedulePolicyTest {
    private val zone = ZoneId.of("America/Tijuana")
    private val timeZone = TimeZone.getTimeZone(zone)
    private val now = LocalDate.of(2026, 10, 14)
        .atTime(18, 30)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun pastTimeTodayIsRejectedInsteadOfMovedToTomorrow() {
        assertNull(
            zenScheduleStartMs(now, tomorrow = false, hour = 17, minute = 0, timeZone = timeZone),
        )
    }

    @Test
    fun futureTimeTodayStaysToday() {
        val result = zenScheduleStartMs(
            now,
            tomorrow = false,
            hour = 19,
            minute = 0,
            timeZone = timeZone,
        )
        val expected = LocalDate.of(2026, 10, 14).atTime(19, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(expected, result)
    }

    @Test
    fun tomorrowExplicitlySchedulesTomorrow() {
        val result = zenScheduleStartMs(
            now,
            tomorrow = true,
            hour = 17,
            minute = 0,
            timeZone = timeZone,
        )
        val expected = LocalDate.of(2026, 10, 15).atTime(17, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(expected, result)
        assertTrue(requireNotNull(result) > now)
    }
}
