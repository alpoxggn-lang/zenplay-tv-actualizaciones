package tv.own.owntv.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateResumeTest {
    @Test
    fun `valid partial content appends exactly at existing byte`() {
        assertEquals(
            UpdateResponsePlan(UpdateWriteMode.APPEND, 100L),
            planUpdateResponse(40L, 206, "bytes 40-99/100", 60L),
        )
    }

    @Test
    fun `server ignoring range restarts instead of corrupting apk`() {
        assertEquals(
            UpdateResponsePlan(UpdateWriteMode.RESTART, 100L),
            planUpdateResponse(40L, 200, null, 100L),
        )
    }

    @Test
    fun `wrong content range is rejected`() {
        assertNull(planUpdateResponse(40L, 206, "bytes 41-99/100", 59L))
        assertNull(planUpdateResponse(40L, 206, "bytes nonsense", 60L))
    }

    @Test
    fun `range not satisfiable accepts an already complete file only`() {
        assertEquals(
            UpdateResponsePlan(UpdateWriteMode.ALREADY_COMPLETE, 100L),
            planUpdateResponse(100L, 416, "bytes */100", 0L),
        )
        assertNull(planUpdateResponse(99L, 416, "bytes */100", 0L))
    }

    @Test
    fun `content range parser rejects overrun`() {
        assertNull(parseUpdateContentRange("bytes 0-100/100"))
    }
}
