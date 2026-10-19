package tv.own.owntv.features.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataNoticePlaybackContractTest {
    @Test
    fun `metadata allowance notice waits through fullscreen mini and audio playback`() {
        val shell = File("src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt").readText()
        val notice = shell.substringAfter("LaunchedEffect(budgetRefusedAt, playerMode)")
            .substringBefore("val mpvEngine")

        assertTrue(notice.contains("budgetRefusedAt > 0L"))
        assertTrue(notice.contains("playerMode == PlayerMode.NONE"))
        assertTrue(notice.contains("budgetNoticeShown = true"))
    }

    @Test
    fun `metadata notice has one shell host outside the conditional player`() {
        val shell = File("src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt").readText()
        val rootHost = shell.substringAfter("// One shell-level host for subtitle/catch-up/startup/metadata notices.")
            .substringBefore("if (showExit)")

        assertTrue(rootHost.contains("InAppToast(localSubToast)"))
        assertFalse(rootHost.contains("playerMode == PlayerMode.FULLSCREEN"))
        assertEquals(1, Regex("InAppToast\\(localSubToast\\)").findAll(shell).count())
    }
}
