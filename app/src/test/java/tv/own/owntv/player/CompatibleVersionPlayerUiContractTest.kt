package tv.own.owntv.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the TV/Fire presentation boundary for catalogue-compatible VOD fallback. */
class CompatibleVersionPlayerUiContractTest {
    private fun source(path: String) = File("src/main/java/$path").readText()

    @Test
    fun `compatible version is a vod only primary action and recording conflict wins`() {
        val hud = source("tv/own/owntv/player/PlayerHud.kt")
        val errorUi = hud.substringAfter("// Status overlay (always shown).")
            .substringBefore("// A provider wait looks like loading")

        assertTrue(hud.contains("compatibleAlternativeLabel: String? = null"))
        assertTrue(hud.contains("onPlayCompatibleVersion: (() -> Unit)? = null"))
        assertTrue(hud.contains("val compatibleAlternativeAvailable = !isLive"))
        assertTrue(
            errorUi.indexOf("if (recordingConflict != null)") <
                errorUi.indexOf("else if (compatibleAlternativeAvailable)"),
        )
        assertTrue(errorUi.contains("R.string.player_compatible_version_action"))
        assertTrue(errorUi.contains("dialog = HudDialog.COMPATIBLE_VERSION_CONFIRM"))
        assertTrue(errorUi.contains("R.string.common_retry"))
        assertTrue(errorUi.contains("style = OwnTVButtonStyle.SECONDARY"))
        // The error CTA may only open confirmation; it must never switch the item directly.
        assertFalse(errorUi.contains("onPlayCompatibleVersion?.invoke()"))
    }

    @Test
    fun `compatible version confirmation explains position and owns the callback`() {
        val hud = source("tv/own/owntv/player/PlayerHud.kt")
        val dialogs = source("tv/own/owntv/player/PlayerHudDialogs.kt")
        val confirmation = dialogs.substringAfter("internal fun CompatibleVersionConfirmationDialog(")
            .substringBefore("private fun DialogScaffold(")
        val dispatch = hud.substringAfter("HudDialog.COMPATIBLE_VERSION_CONFIRM ->")
            .substringBefore("HudDialog.JUMP_BACK ->")

        assertTrue(dispatch.contains("CompatibleVersionConfirmationDialog("))
        assertTrue(dispatch.contains("onPlayCompatibleVersion?.invoke()"))
        assertTrue(confirmation.contains("R.string.player_compatible_version_message"))
        assertTrue(confirmation.contains("R.string.player_compatible_version_alternative"))
        assertTrue(confirmation.contains("R.string.player_compatible_version_action"))
        assertTrue(confirmation.contains("onClick = onConfirm"))
        assertTrue(confirmation.contains("R.string.common_cancel"))
    }

    @Test
    fun `audio only badge cannot cover a terminal error`() {
        val shell = source("tv/own/owntv/features/shell/OwnTVShell.kt")

        assertTrue(shell.contains("val activePlayerError by if (liveOnExo)"))
        assertTrue(shell.contains("if (audioOnlyMedia && activePlayerError == null)"))
    }

    @Test
    fun `tv episode replacement preserves queue identity and profile`() {
        val series = source("tv/own/owntv/features/series/SeriesViewModel.kt")
        val switch = series
            .substringAfter("fun playCompatibleVersion()")
            .substringBefore("suspend fun downloadedSubtitles")

        assertTrue(switch.contains("pid != expectedPlayback.profileId"))
        assertTrue(switch.contains("replacement.seasonNumber to replacement.episodeNumber"))
        assertTrue(switch.contains("logical.seasonNumber to logical.episodeNumber"))
        assertTrue(switch.contains("replacementsByNumber[number] ?: logical"))
        assertTrue(switch.contains("put(currentNumber, alternative.replacementEpisode)"))
        assertFalse(switch.contains("originalQueue.mapNotNull"))
    }
}
