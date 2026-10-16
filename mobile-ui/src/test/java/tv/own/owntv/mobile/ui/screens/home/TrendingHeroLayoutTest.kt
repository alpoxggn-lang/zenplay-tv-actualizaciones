package tv.own.owntv.mobile.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendingHeroLayoutTest {
    @Test
    fun portraitPhoneUsesReadableCinematicFrame() {
        assertEquals(16f / 10f, trendingArtworkAspectRatio(360, 800, 360), 0.001f)
    }

    @Test
    fun landscapePhoneUsesWideFrameWithoutTabletAssumption() {
        assertEquals(18f / 8f, trendingArtworkAspectRatio(800, 360, 360), 0.001f)
    }

    @Test
    fun landscapeTabletUsesCinemaScopeFrame() {
        assertEquals(21f / 9f, trendingArtworkAspectRatio(1280, 800, 800), 0.001f)
    }

    @Test
    fun portraitTabletKeepsContentTallEnoughToRead() {
        assertEquals(16f / 10f, trendingArtworkAspectRatio(800, 1280, 800), 0.001f)
    }
}
