package tv.own.owntv.mobile.ui.screens.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedPlaybackProfileGateTest {
    @Test
    fun `stale callback cannot start a previous profiles file`() {
        val gate = ProtectedPlaybackProfileGate()
        gate.activeProfileChanged(2L)
        var playerCalled = false

        val started = gate.startIfCurrent(expectedProfileId = 1L, freshlyReadProfileId = 1L) {
            playerCalled = true
        }

        assertFalse(started)
        assertFalse(playerCalled)
        assertFalse(gate.owns(1L))
    }

    @Test
    fun `fresh mismatch refuses playback even before collector catches up`() {
        val gate = ProtectedPlaybackProfileGate()
        gate.activeProfileChanged(1L)
        var playerCalled = false

        val started = gate.startIfCurrent(expectedProfileId = 1L, freshlyReadProfileId = 2L) {
            playerCalled = true
        }

        assertFalse(started)
        assertFalse(playerCalled)
    }

    @Test
    fun `profile change after start requests immediate revocation`() {
        val gate = ProtectedPlaybackProfileGate()
        gate.activeProfileChanged(1L)

        assertTrue(gate.startIfCurrent(1L, 1L) {})
        assertTrue(gate.owns(1L))
        assertTrue(gate.activeProfileChanged(2L))
    }
}
