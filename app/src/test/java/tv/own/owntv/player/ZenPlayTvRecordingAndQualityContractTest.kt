package tv.own.owntv.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the understandable REC glyph and the real-track-only TV quality picker. */
class ZenPlayTvRecordingAndQualityContractTest {
    private fun source(path: String) = File("src/main/java/$path").readText()

    @Test
    fun `recording action uses a dedicated rec ring and its accessible record label`() {
        val icons = source("tv/own/owntv/ui/components/OwnTVIcon.kt")
        val chrome = source("tv/own/owntv/player/PlayerHudChrome.kt")

        assertTrue(icons.contains("OwnTVIcon.RECORD ->"))
        assertTrue(chrome.contains("OwnTVIcon.RECORD"))
        assertTrue(chrome.contains("R.string.recording_stop"))
        assertTrue(chrome.contains("R.string.recording_record"))
        assertFalse(
            chrome.substringAfter("if (onRecordThis != null)")
                .substringBefore("            }")
                .contains("OwnTVIcon.LIVE_TV"),
        )
    }

    @Test
    fun `quality menu is driven by engine tracks and is hidden for fixed quality sources`() {
        val hud = source("tv/own/owntv/player/PlayerHud.kt")
        val dialogs = source("tv/own/owntv/player/PlayerHudDialogs.kt")

        assertTrue(hud.contains("player.videoQualities.collectAsStateWithLifecycle()"))
        assertTrue(hud.contains("hasQuality = videoQualities.isNotEmpty()"))
        assertTrue(hud.contains("QualityDialog(videoQualities, videoQualityPick"))
        assertTrue(dialogs.contains("currentQuality == null"))
        assertTrue(dialogs.contains("HudDialog.QUALITY"))
        assertFalse(dialogs.contains("listOf(2160, 1080, 720"))
    }
}
