package tv.own.owntv.features.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the TV-only visual refresh without changing navigation or adding expensive blur layers. */
class ZenPlayTvVisualRefreshContractTest {
    private fun source(relative: String): String = File("src/main/java/$relative").readText()

    @Test
    fun `top navigation keeps one glass layer and paints brand focus inside it`() {
        val topBar = source("tv/own/owntv/features/shell/components/TopBar.kt")
        val main = topBar.substringAfter("fun TopBar(").substringBefore("private fun NowPlayingNavButton")
        val focusChrome = topBar.substringAfter("TopBarFocusChrome(").substringBefore("private fun SectionChip")

        assertTrue(main.contains("GlassSurface.TOPBAR"))
        assertTrue(main.contains("ZenPlayTopBarRim"))
        assertTrue(main.contains("zenplay_wordmark_horizontal"))
        assertTrue(focusChrome.contains("ZenPlayNavFocusedFill"))
        assertTrue(focusChrome.contains("ZenPlayNavSelectedFill"))
        assertFalse(focusChrome.contains(".glass("))
        assertFalse(topBar.contains(".blur("))
    }

    @Test
    fun `home hero and rails share the cinematic visual language`() {
        val home = source("tv/own/owntv/features/home/HomeScreen.kt")
        val liveRows = source("tv/own/owntv/features/home/HomeGuideSlice.kt")
        val header = source("tv/own/owntv/features/home/HomeSectionHeader.kt")

        assertTrue(header.contains("semantics { heading() }"))
        assertTrue(header.contains("Brush.verticalGradient"))
        assertTrue(header.contains("Brush.horizontalGradient"))
        assertTrue(home.split("HomeSectionHeader(").size - 1 >= 4)
        assertTrue(liveRows.contains("HomeSectionHeader("))
        assertTrue(home.contains("val heroShape = RoundedCornerShape(24.dp)"))
        assertTrue(home.contains("ZenPlayHeroBlue.copy(alpha = 0.10f)"))
        assertFalse(header.contains(".blur("))
    }
}
