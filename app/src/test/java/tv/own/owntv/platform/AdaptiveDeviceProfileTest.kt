package tv.own.owntv.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveDeviceProfileTest {
    @Test
    fun televisionUsesRemoteNavigationEvenWhenOemReportsTouch() {
        val profile = capabilities(
            width = 1920,
            height = 1080,
            television = true,
            touchscreen = true,
            dpad = true,
        ).toAdaptiveDeviceProfile()

        assertEquals(ZenNavigation.REMOTE, profile.navigation)
        assertTrue(profile.remoteFirst)
        assertFalse(profile.twoPane)
        assertEquals(4, profile.maxMultiviewTiles)
    }

    @Test
    fun portraitPhoneUsesBottomNavigation() {
        val profile = capabilities(width = 412, height = 915).toAdaptiveDeviceProfile()

        assertEquals(ZenWindowClass.COMPACT, profile.windowClass)
        assertEquals(ZenNavigation.BOTTOM_BAR, profile.navigation)
        assertFalse(profile.remoteFirst)
        assertFalse(profile.twoPane)
    }

    @Test
    fun rotatedPhoneNeverBecomesATabletRail() {
        val profile = capabilities(width = 915, height = 412, smallestWidth = 412)
            .toAdaptiveDeviceProfile()

        assertEquals(ZenNavigation.BOTTOM_BAR, profile.navigation)
        assertTrue(profile.compactHandset)
        assertFalse(profile.twoPane)
    }

    @Test
    fun portraitTabletUsesBottomNavigationWithoutForcingTwoPanes() {
        val profile = capabilities(width = 800, height = 1280).toAdaptiveDeviceProfile()

        assertEquals(ZenWindowClass.MEDIUM, profile.windowClass)
        assertEquals(ZenNavigation.BOTTOM_BAR, profile.navigation)
        assertFalse(profile.twoPane)
    }

    @Test
    fun expandedTabletUsesRailAndTwoPanes() {
        val profile = capabilities(width = 1280, height = 800).toAdaptiveDeviceProfile()

        assertEquals(ZenWindowClass.EXPANDED, profile.windowClass)
        assertEquals(ZenNavigation.RAIL, profile.navigation)
        assertTrue(profile.twoPane)
    }

    @Test
    fun remoteOnlyBoxGetsTvNavigationWithoutLeanbackDeclaration() {
        val profile = capabilities(
            width = 1280,
            height = 720,
            television = false,
            touchscreen = false,
            dpad = true,
        ).toAdaptiveDeviceProfile()

        assertEquals(ZenNavigation.REMOTE, profile.navigation)
        assertTrue(profile.remoteFirst)
    }

    @Test
    fun legacyAndLowRamReduceEffectsAndMultiviewCost() {
        val legacy = capabilities(width = 1280, height = 720, legacy = true)
            .toAdaptiveDeviceProfile()
        val lowRam = capabilities(width = 1280, height = 720, lowRam = true)
            .toAdaptiveDeviceProfile()

        assertTrue(legacy.reducedEffects)
        assertEquals(2, legacy.maxMultiviewTiles)
        assertTrue(lowRam.reducedEffects)
        assertEquals(1, lowRam.maxMultiviewTiles)
    }

    private fun capabilities(
        width: Int,
        height: Int,
        television: Boolean = false,
        touchscreen: Boolean = true,
        dpad: Boolean = false,
        lowRam: Boolean = false,
        legacy: Boolean = false,
        smallestWidth: Int = minOf(width, height),
    ) = DeviceCapabilities(
        widthDp = width,
        heightDp = height,
        smallestWidthDp = smallestWidth,
        televisionUi = television,
        touchscreen = touchscreen,
        dpad = dpad,
        lowRam = lowRam,
        legacyBuild = legacy,
    )
}

