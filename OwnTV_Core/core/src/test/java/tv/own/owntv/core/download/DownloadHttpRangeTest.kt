package tv.own.owntv.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadHttpRangeTest {
    @Test
    fun `matching 206 appends at authenticated byte boundary`() {
        assertEquals(
            DownloadResponsePlan(DownloadWriteMode.APPEND, 1_000L, 600L),
            planDownloadResponse(400L, 206, "bytes 400-999/1000", 600L),
        )
    }

    @Test
    fun `206 with a different start is rejected`() {
        assertNull(planDownloadResponse(400L, 206, "bytes 0-599/1000", 600L))
        assertNull(planDownloadResponse(400L, 206, "bytes 401-999/1000", 599L))
        assertNull(planDownloadResponse(400L, 206, null, 600L))
        assertNull(planDownloadResponse(400L, 206, "bytes 400-999/*", 600L))
    }

    @Test
    fun `206 whose body length disagrees with content range is rejected`() {
        assertNull(planDownloadResponse(400L, 206, "bytes 400-999/1000", 599L))
        assertNull(planDownloadResponse(400L, 206, "bytes 400-999/1000", 601L))
    }

    @Test
    fun `extra bytes are rejected before crossing declared response boundary`() {
        val plan = planDownloadResponse(400L, 206, "bytes 400-999/1000", -1L)!!

        assertEquals(true, acceptsDownloadResponseBlock(plan, receivedBytes = 500L, nextBytes = 100))
        assertEquals(false, acceptsDownloadResponseBlock(plan, receivedBytes = 500L, nextBytes = 101))
        assertEquals(false, acceptsDownloadResponseBlock(plan, receivedBytes = 600L, nextBytes = 1))
    }

    @Test
    fun `valid but shortened 206 slice cannot commit a completion footer`() {
        val shortened = planDownloadResponse(400L, 206, "bytes 400-599/1000", 200L)!!
        val complete = planDownloadResponse(400L, 206, "bytes 400-999/1000", 600L)!!

        assertEquals(false, completesDownloadResponse(shortened, existingBytes = 400L, receivedBytes = 200L))
        assertEquals(true, completesDownloadResponse(complete, existingBytes = 400L, receivedBytes = 600L))
    }

    @Test
    fun `server ignoring range requires a fresh encrypted target`() {
        assertEquals(
            DownloadResponsePlan(DownloadWriteMode.RESTART, 1_000L, 1_000L),
            planDownloadResponse(400L, 200, null, 1_000L),
        )
    }

    @Test
    fun `416 completes only when declared total equals authenticated bytes`() {
        assertEquals(
            DownloadResponsePlan(DownloadWriteMode.ALREADY_COMPLETE, 400L, 0L),
            planDownloadResponse(400L, 416, "bytes */400", 0L),
        )
        assertNull(planDownloadResponse(400L, 416, "bytes */401", 0L))
        assertNull(planDownloadResponse(400L, 416, null, 0L))
        assertNull(planDownloadResponse(0L, 416, "bytes */0", 0L))
    }

    @Test
    fun `empty and unrelated success responses are not media downloads`() {
        assertNull(planDownloadResponse(0L, 204, null, 0L))
        assertNull(planDownloadResponse(0L, 205, null, 0L))
    }

    @Test
    fun `parser rejects overruns and malformed unsatisfied ranges`() {
        assertNull(parseDownloadContentRange("bytes 0-100/100"))
        assertNull(parseDownloadContentRange("bytes */nonsense"))
        assertNull(parseDownloadContentRange("items 0-9/10"))
    }
}
