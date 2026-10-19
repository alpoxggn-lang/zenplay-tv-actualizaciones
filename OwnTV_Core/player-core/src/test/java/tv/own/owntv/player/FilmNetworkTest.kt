package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.player.PlayerBudget

/** N18 — films' buffer, timeout, underrun recovery and reconnects. */
class FilmNetworkTest {

    private val lowSpec = PlayerBudget("48MiB", "16MiB", readaheadSecs = "10", cacheSecs = "30", lowSpec = true)

    @Test
    fun `Auto keeps the device tier and mpv's own timeout`() {
        assertEquals(30, FilmNetwork.bufferSecs(0, lowSpec))
        assertEquals("10", FilmNetwork.readaheadSecs(0, lowSpec))
        assertEquals(60, FilmNetwork.mpvTimeoutSecs(0))
        assertEquals(60, FilmNetwork.exoReadTimeoutSecs(0))
    }

    @Test
    fun `a choice replaces the seconds`() {
        assertEquals(300, FilmNetwork.bufferSecs(300, lowSpec))
        assertEquals("300", FilmNetwork.readaheadSecs(300, lowSpec))
        assertEquals(10, FilmNetwork.mpvTimeoutSecs(10))
        assertEquals(10, FilmNetwork.exoReadTimeoutSecs(10))
    }

    @Test
    fun `slow-link recovery waits longer only after an underrun`() {
        assertEquals(5, FilmNetwork.mpvRebufferSecs(0, lowSpec))
        assertEquals(8_000, FilmNetwork.exoRebufferMs(0, lowSpec))
        assertEquals(2_500, FilmNetwork.EXO_START_BUFFER_MS)
    }

    @Test
    fun `recovery is bounded even when the user requests a very deep cache`() {
        assertEquals(FilmNetwork.MPV_REBUFFER_SECS, FilmNetwork.mpvRebufferSecs(300, lowSpec))
        assertEquals(FilmNetwork.EXO_REBUFFER_MS, FilmNetwork.exoRebufferMs(300, lowSpec))
    }

    @Test
    fun `the default budget is the single reload films always had`() {
        assertEquals(1, FilmNetwork.nextReconnect(used = 0, lastAtPosMs = 0, posMs = 5_000, budget = 1))
        assertNull(FilmNetwork.nextReconnect(used = 1, lastAtPosMs = 5_000, posMs = 20_000, budget = 1))
    }

    @Test
    fun `a minute of playback since the last reopen earns the budget back`() {
        assertEquals(1, FilmNetwork.nextReconnect(used = 1, lastAtPosMs = 5_000, posMs = 5_000 + FilmNetwork.HEALTHY_MS, budget = 1))
    }

    @Test
    fun `quick drops use the budget up, then stop`() {
        var used = 0
        var at = 0L
        var pos = 10_000L
        repeat(3) {
            used = FilmNetwork.nextReconnect(used, at, pos, budget = 3) ?: error("budget ran out early")
            at = pos
            pos += 5_000
        }
        assertNull(FilmNetwork.nextReconnect(used, at, pos, budget = 3))
    }
}
