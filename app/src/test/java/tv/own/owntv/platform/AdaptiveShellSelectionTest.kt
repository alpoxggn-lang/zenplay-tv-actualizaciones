package tv.own.owntv.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveShellSelectionTest {
    @Test
    fun `remote navigation mounts only the ten foot shell`() {
        assertEquals(ZenShell.REMOTE, profile(ZenNavigation.REMOTE).shell)
    }

    @Test
    fun `phone and tablet navigation mount the same touch shell`() {
        assertEquals(ZenShell.TOUCH, profile(ZenNavigation.BOTTOM_BAR).shell)
        assertEquals(ZenShell.TOUCH, profile(ZenNavigation.RAIL).shell)
    }

    private fun profile(navigation: ZenNavigation) = AdaptiveDeviceProfile(
        navigation = navigation,
        windowClass = ZenWindowClass.COMPACT,
        remoteFirst = navigation == ZenNavigation.REMOTE,
        twoPane = navigation == ZenNavigation.RAIL,
        reducedEffects = false,
        maxMultiviewTiles = 4,
    )
}

