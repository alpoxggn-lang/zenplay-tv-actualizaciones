package tv.own.owntv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageLoaderPolicyTest {

    @Test
    fun `normal capable devices keep image transitions`() {
        assertTrue(shouldCrossfadeImages(legacyBuild = false, lowRamDevice = false))
    }

    @Test
    fun `legacy and low ram devices disable image transitions`() {
        assertFalse(shouldCrossfadeImages(legacyBuild = true, lowRamDevice = false))
        assertFalse(shouldCrossfadeImages(legacyBuild = false, lowRamDevice = true))
        assertFalse(shouldCrossfadeImages(legacyBuild = true, lowRamDevice = true))
    }
}
