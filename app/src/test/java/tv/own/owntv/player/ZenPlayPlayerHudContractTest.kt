package tv.own.owntv.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the TV/Fire player contract that is easy to regress while adding new controls. */
class ZenPlayPlayerHudContractTest {
    private fun source(path: String) = File("src/main/java/$path").readText()

    @Test
    fun `series player has no permanent intro next episode or episode list actions`() {
        val chrome = source("tv/own/owntv/player/PlayerHudChrome.kt")
        val transport = chrome.substringAfter("internal fun CenterControls(")
            .substringBefore("// ---------------- Bottom bar")

        assertFalse(transport.contains("zenplay_skip_intro"))
        assertFalse(transport.contains("player.next()"))
        assertFalse(chrome.contains("zenplay_player_episode_list"))
        assertFalse(chrome.contains("onOpenEpisodeList"))
        assertTrue(chrome.contains("internal fun NextEpisodeCard("))
    }

    @Test
    fun `hidden directional key only reveals hud`() {
        val hud = source("tv/own/owntv/player/PlayerHud.kt")
        val keyHandling = hud.substringAfter(".onPreviewKeyEvent { e ->")
            .substringBefore("if (!controlsVisible && !showNextCard)")

        assertTrue(keyHandling.contains("!inert && !controlsVisible"))
        assertTrue(keyHandling.contains("controlsVisible = true"))
        assertFalse(keyHandling.contains("DirectionUp -> { zap"))
        assertFalse(keyHandling.contains("Key.MediaNext -> { zap"))
        assertFalse(keyHandling.contains("Key.ChannelUp"))
        assertFalse(keyHandling.contains("onOpenChannelList(); true"))
        assertFalse(keyHandling.contains("player.seekBy(if (back)"))
    }

    @Test
    fun `live actions and grouped menus stay visible in hud`() {
        val chrome = source("tv/own/owntv/player/PlayerHudChrome.kt")
        val hud = source("tv/own/owntv/player/PlayerHud.kt")
        val dialogs = source("tv/own/owntv/player/PlayerHudDialogs.kt")
        val shell = source("tv/own/owntv/features/shell/OwnTVShell.kt")

        assertTrue(chrome.contains("zenplay_player_channel_list"))
        assertTrue(chrome.contains("zenplay_player_previous_channel"))
        assertTrue(chrome.contains("zenplay_player_next_channel"))
        assertTrue(chrome.contains("OwnTVIcon.BACK"))
        assertTrue(chrome.contains("OwnTVIcon.CHEVRON"))
        assertTrue(chrome.contains("onOpenDialog(HudDialog.AUDIO)"))
        assertTrue(chrome.contains("OwnTVIcon.SUBTITLE"))
        assertTrue(chrome.contains("onOpenDialog(HudDialog.SUBS)"))
        assertTrue(chrome.contains("HudDialog.PLAYBACK_SETTINGS"))
        assertTrue(chrome.contains("HudDialog.RECORD"))
        assertTrue(hud.contains("onToggleEngine = toggleCompat ?: toggleVod"))
        assertTrue(hud.contains("if (compatMode) \"MPV\" else \"ExoPlayer\""))
        assertTrue(dialogs.contains("R.string.player_tool_engine"))
        assertTrue(dialogs.contains("onToggleEngine()"))
        assertTrue(shell.contains("player.canToggleVodEngine.collectAsStateWithLifecycle()"))
        assertTrue(shell.contains("!isTunedLive && canToggleVodEngine"))
        assertTrue(hud.contains("RecordActionsDialog("))
        assertTrue(hud.contains("onScheduleRecording"))
        assertTrue(shell.contains("onPreviousChannel = zap?.let { z -> { z(-1) } }"))
        assertFalse(shell.contains("onOpenEpisodeList = playingSeries"))
    }

    @Test
    fun `playback settings do not duplicate dedicated audio and subtitle actions`() {
        val dialogs = source("tv/own/owntv/player/PlayerHudDialogs.kt")
        val settings = dialogs.substringAfter("internal fun PlaybackSettingsDialog(")
            .substringBefore("internal fun RecordActionsDialog(")

        assertFalse(settings.contains("R.string.player_audio_track"))
        assertFalse(settings.contains("R.string.player_subtitles"))
        assertFalse(settings.contains("HudDialog.AUDIO"))
        assertFalse(settings.contains("HudDialog.SUBS"))
        assertTrue(settings.contains("R.string.player_tool_speed"))
        assertTrue(settings.contains("R.string.player_tool_aspect"))
    }
}
