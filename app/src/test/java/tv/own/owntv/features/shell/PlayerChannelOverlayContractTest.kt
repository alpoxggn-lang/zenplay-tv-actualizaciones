package tv.own.owntv.features.shell

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerChannelOverlayContractTest {
    @Test
    fun `channel browser exposes categories and backs up before closing`() {
        val overlay = File("src/main/java/tv/own/owntv/features/shell/components/ChannelListOverlay.kt").readText()
        val shell = File("src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt").readText()

        assertTrue(overlay.contains("zenplay_player_back_categories"))
        assertTrue(overlay.contains("onClick = onOpenCategories"))
        assertTrue(shell.contains("onDismiss = { liveVm.showCategories() }"))
        assertTrue(shell.contains("liveVm.hideCategoryBrowser()"))
        assertTrue(shell.contains("showChannelList = false"))
    }

    @Test
    fun `one channel still exposes list but next needs more than one`() {
        val shell = File("src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt").readText()
        val playerCall = shell.substringAfter("PlayerHud(").substringBefore("modifier = Modifier.fillMaxSize()")

        assertTrue(playerCall.contains("onOpenChannelList = if (isTunedLive && previewChannel != null)"))
        assertTrue(shell.contains("if (showChannelList && zapChannels.isNotEmpty())"))
        assertTrue(shell.contains("if (zapSource == MainSection.LIVE_TV && liveCanZap) liveVm::zap else null"))
        assertFalse(playerCall.contains("onOpenChannelList = if (isTunedLive && liveCanZap)"))
    }

    @Test
    fun `multiview is capability gated and player scheduling reuses recording manager`() {
        val shell = File("src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt").readText()
        val live = File("src/main/java/tv/own/owntv/features/live/LiveViewModel.kt").readText()

        assertTrue(shell.contains("val multiviewAvailable = deviceProfile.maxMultiviewTiles > 1"))
        assertTrue(shell.contains("ZenScheduleRecordingDialog("))
        assertTrue(live.contains("suspend fun schedulePlayerRecordingFor("))
        assertTrue(live.substringAfter("suspend fun schedulePlayerRecordingFor(").contains("recordings.schedule("))
        assertTrue(live.contains("cancelPlayerRecordingSchedule"))
    }
}
