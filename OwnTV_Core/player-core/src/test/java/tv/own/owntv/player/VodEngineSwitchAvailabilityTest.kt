package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodEngineSwitchAvailabilityTest {
    @Test
    fun `plain loaded movie or episode may switch engines`() {
        assertTrue(vodEngineSwitchAvailable(isLive = false, drmProtected = false, hasStream = true))
    }

    @Test
    fun `live drm and empty sessions never expose vod switch`() {
        assertFalse(vodEngineSwitchAvailable(isLive = true, drmProtected = false, hasStream = true))
        assertFalse(vodEngineSwitchAvailable(isLive = false, drmProtected = true, hasStream = true))
        assertFalse(vodEngineSwitchAvailable(isLive = false, drmProtected = false, hasStream = false))
    }
}
