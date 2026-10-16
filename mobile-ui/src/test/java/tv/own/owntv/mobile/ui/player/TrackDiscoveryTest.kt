package tv.own.owntv.mobile.ui.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TrackDiscoveryTest {
    @Test
    fun `already discovered tracks are preserved without another read`() = runBlocking {
        val initial = listOf("Español")
        var reads = 0
        var waits = 0

        val result = discoverTracksWhileEmpty(
            initial = initial,
            load = { reads += 1; listOf("English") },
            waitForRetry = { waits += 1 },
        )

        assertSame(initial, result)
        assertEquals(0, reads)
        assertEquals(0, waits)
    }

    @Test
    fun `empty tracks are retried only until the first list appears`() = runBlocking {
        var reads = 0
        val waits = mutableListOf<Long>()

        val result = discoverTracksWhileEmpty(
            initial = emptyList(),
            load = {
                reads += 1
                if (reads == 3) listOf("English", "Español") else emptyList()
            },
            waitForRetry = { waits += it },
        )

        assertEquals(listOf("English", "Español"), result)
        assertEquals(3, reads)
        assertEquals(listOf(500L, 500L, 500L), waits)
    }

    @Test
    fun `empty discovery is bounded`() = runBlocking {
        var reads = 0
        var waits = 0

        val result = discoverTracksWhileEmpty<String>(
            initial = emptyList(),
            load = { reads += 1; emptyList() },
            attempts = 4,
            waitForRetry = { waits += 1 },
        )

        assertEquals(emptyList<String>(), result)
        assertEquals(4, reads)
        assertEquals(4, waits)
    }
}
