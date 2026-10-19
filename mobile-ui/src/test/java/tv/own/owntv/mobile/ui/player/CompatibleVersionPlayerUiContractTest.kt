package tv.own.owntv.mobile.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the phone/tablet presentation boundary for catalogue-compatible VOD fallback. */
class CompatibleVersionPlayerUiContractTest {
    private fun source() = File("src/main/java/tv/own/owntv/mobile/ui/player/PlayerScreen.kt").readText()

    @Test
    fun `compatible action is limited to movies and episodes and confirms first`() {
        val screen = source()
        val errorPanel = screen.substringAfter("private fun ErrorPanel(")
            .substringBefore("private fun CompatibleVersionConfirmationDialog(")
        val errorActions = errorPanel.substringBefore(
            "if (confirmCompatibleVersion && compatibleAlternativeAvailable)",
        )

        assertTrue(screen.contains("compatibleAlternativeLabel: String? = null"))
        assertTrue(screen.contains("onPlayCompatibleVersion: (() -> Unit)? = null"))
        assertTrue(screen.contains("film?.mediaType == MediaType.MOVIE"))
        assertTrue(screen.contains("film?.mediaType == MediaType.EPISODE"))
        assertTrue(screen.contains("compatibleAlternativeLabel = compatibleAlternativeLabel.takeIf"))
        assertTrue(screen.contains("onPlayCompatibleVersion = onPlayCompatibleVersion.takeIf"))
        assertTrue(errorPanel.contains("compatibleAlternativeLabel != null"))
        assertTrue(errorPanel.contains("onPlayCompatibleVersion != null"))
        assertTrue(errorPanel.contains("Button(onClick = { confirmCompatibleVersion = true })"))
        assertTrue(errorPanel.contains("R.string.player_compatible_version_action"))
        assertTrue(errorPanel.contains("R.string.common_retry"))
        // The failed-player CTA opens confirmation and cannot switch catalogue entries itself.
        assertFalse(errorActions.contains("onPlayCompatibleVersion()"))
    }

    @Test
    fun `confirmation explains position and is the only callback gate`() {
        val screen = source()
        val confirmation = screen.substringAfter("private fun CompatibleVersionConfirmationDialog(")
            .substringBefore("private fun NextEpisodeCard(")
        val confirmationGate = screen.substringAfter("if (confirmCompatibleVersion && compatibleAlternativeAvailable)")
            .substringBefore("/** Explicit confirmation")

        assertTrue(confirmationGate.contains("onPlayCompatibleVersion()"))
        assertTrue(confirmation.contains("R.string.player_compatible_version_message"))
        assertTrue(confirmation.contains("R.string.player_compatible_version_alternative"))
        assertTrue(confirmation.contains("confirmButton"))
        assertTrue(confirmation.contains("Button(onClick = onConfirm)"))
        assertTrue(confirmation.contains("R.string.common_cancel"))
    }

    @Test
    fun `audio only backdrop cannot cover a terminal error`() {
        val screen = source()

        assertTrue(screen.contains("val noPicture = audioOnlyMedia && error == null && !inPip"))
    }
}
