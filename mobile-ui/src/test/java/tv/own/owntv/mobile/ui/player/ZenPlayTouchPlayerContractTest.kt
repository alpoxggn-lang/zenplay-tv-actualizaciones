package tv.own.owntv.mobile.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenPlayTouchPlayerContractTest {
    private fun source(name: String) = File("src/main/java/tv/own/owntv/mobile/ui/player/$name").readText()

    @Test
    fun `primary touch toolbar is small and recording is absent`() {
        val controls = source("PlayerControls.kt")
        val toolbar = controls.substringAfter("private fun ToolBar(").substringBefore("private fun CtrlButton(")

        assertTrue(toolbar.contains("PlayerSheet.VOLUME"))
        assertTrue(toolbar.contains("PlayerSheet.PLAYBACK_SETTINGS"))
        assertTrue(toolbar.contains("onMultiview"))
        assertTrue(toolbar.contains("onPictureInPicture"))
        assertTrue(toolbar.contains("MobileIcons.PictureInPictureAlt"))
        assertTrue(toolbar.contains("player_enter_picture_in_picture"))
        assertTrue(toolbar.contains("MobileIcons.Audiotrack"))
        assertTrue(toolbar.contains("MobileIcons.FormatListBulleted"))
        listOf("RECORD", "SLEEP_TIMER", "INFO", "BRIGHTNESS", "MINI_PLAYER").forEach {
            assertFalse("unexpected touch toolbar action $it", toolbar.contains(it))
        }
    }

    @Test
    fun `grouped playback settings can switch engines for live vod and episodes`() {
        val sheets = source("PlayerSheets.kt")
        val screen = source("PlayerScreen.kt")

        assertTrue(sheets.contains("onToggleEngine: (() -> Unit)?"))
        assertTrue(sheets.contains("R.string.player_tool_engine"))
        assertTrue(sheets.contains("player.engineChip.collectAsStateWithLifecycle()"))
        assertTrue(screen.contains("tuner.toggleLiveEngine()"))
        assertTrue(screen.contains("player.toggleVodEngine()"))
        assertTrue(screen.contains("offsetSec == null || hasLocalCopy"))
        assertTrue(screen.contains("film != null && canToggleVodEngine"))
        assertTrue(screen.contains("player_switch_exo"))
        assertTrue(screen.contains("player_switch_mpv"))
    }

    @Test
    fun `live touch actions and two level channel browser are present`() {
        val controls = source("PlayerControls.kt")
        val sheets = source("PlayerSheets.kt")
        val screen = source("PlayerScreen.kt")

        assertTrue(controls.contains("LiveChannelActions("))
        assertTrue(controls.contains("zenplay_player_channels_short"))
        assertTrue(controls.contains("zenplay_player_previous_short"))
        assertTrue(controls.contains("zenplay_player_next_short"))
        assertTrue(sheets.contains("zenplay_player_back_categories"))
        assertTrue(screen.contains("playerShowingCategories = true"))
        assertTrue(screen.contains("tuner.step(1)"))
        assertTrue(screen.contains("tuner.step(-1)"))
        assertTrue(screen.contains("lowRam -> 1"))
        assertTrue(screen.contains("legacyBuild -> 2"))
        assertTrue(screen.contains("else -> 4"))
    }

    @Test
    fun `next episode is end gated and intro button is not composed`() {
        val controls = source("PlayerControls.kt")
        val screen = source("PlayerScreen.kt")

        assertFalse(controls.contains("zenplay_skip_intro"))
        assertTrue(screen.contains("msToAdvance in 0L..30_000L"))
        assertTrue(screen.contains("nextUpTitle != null"))
        assertTrue(screen.contains("nav.hasNext"))
    }

    @Test
    fun `direct scrub is absolute and vod exit saves before shared player teardown`() {
        val controls = source("PlayerControls.kt")
        val screen = source("PlayerScreen.kt")
        val seekBar = controls.substringAfter("private fun SeekBar(")
            .substringBefore("private fun LiveBar(")
        val stopAndExit = screen.substringAfter("val stopAndExit = {")
            .substringBefore("BackHandler(onBack = stopAndExit)")

        assertTrue(seekBar.contains("player.seekTo((it * duration).toLong())"))
        assertTrue(seekBar.contains("player.seekTo((dragValue * duration).toLong())"))
        assertFalse(seekBar.contains("player.seekBy((it * duration)"))
        assertTrue(screen.contains("activeEngine.seekTo(target)"))
        assertTrue(stopAndExit.contains("if (film != null) vodTuner.stop() else tuner.stop()"))
        assertFalse(stopAndExit.contains("tuner.stop()\n        vodTuner.stop()"))
    }

    @Test
    fun `system pip is capability gated and shared by live movies and episodes`() {
        val screen = source("PlayerScreen.kt")

        assertTrue(screen.contains("pip.manualEntryAvailable.collectAsStateWithLifecycle()"))
        assertTrue(screen.contains("manualPipAvailable && !audioOnly && !audioOnlyMedia"))
        assertTrue(screen.contains("pip.requestManualEntry()"))
        // One PlayerControls call serves channel, film and episode playback; no media-specific copy.
        assertTrue(screen.indexOf("PlayerControls(") == screen.lastIndexOf("PlayerControls("))
    }

    @Test
    fun `player rotates both ways episode list stays in app and portrait multiview stacks`() {
        val screen = source("PlayerScreen.kt")
        val controls = source("PlayerControls.kt")
        val nav = File("src/main/java/tv/own/owntv/mobile/ui/nav/MobileNavHost.kt").readText()
        val multiview = File(
            "src/main/java/tv/own/owntv/mobile/ui/screens/multiview/MultiviewScreen.kt",
        ).readText()

        assertTrue(screen.contains("SCREEN_ORIENTATION_FULL_SENSOR"))
        assertFalse(screen.contains("SCREEN_ORIENTATION_SENSOR_LANDSCAPE"))
        assertTrue(screen.contains("it.mediaType == MediaType.EPISODE"))
        assertTrue(controls.contains("onEpisodes"))
        assertTrue(nav.contains("navController.leavePlayerStillPlaying()"))
        assertTrue(nav.contains("detailRoute(MobileDestination.SERIES.route, LibraryTab.SERIES, seriesId)"))
        assertTrue(multiview.contains("if (landscape) state.tiles.indices.chunked(2)"))
        assertTrue(multiview.contains("else state.tiles.indices.map { listOf(it) }"))
    }
}
