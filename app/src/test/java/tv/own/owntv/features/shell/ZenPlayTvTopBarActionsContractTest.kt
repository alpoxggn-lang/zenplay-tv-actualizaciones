package tv.own.owntv.features.shell

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the TV/Fire top strip cheap, remote-focusable and wired to the existing manual sync. */
class ZenPlayTvTopBarActionsContractTest {
    private fun source(path: String) = File("src/main/java/$path").readText()

    @Test
    fun `refresh is a labelled dpad action beside search and uses the catalogue sync`() {
        val topBar = source("tv/own/owntv/features/shell/components/TopBar.kt")
        val shell = source("tv/own/owntv/features/shell/OwnTVShell.kt")
        val row = topBar.substringAfter("ProfileNavButton(").substringBefore("destinations.forEach")

        assertTrue(row.indexOf("SearchNavButton(") < row.indexOf("RefreshNavButton("))
        assertTrue(row.contains("onClick = onRefreshClick"))
        assertTrue(topBar.contains("contentDescription = label"))
        assertTrue(topBar.contains("testTag(\"topbar_refresh_content\")"))
        assertTrue(shell.contains("onRefreshClick = refreshZenContent"))
        assertTrue(shell.contains("zenShellVm.refreshAllNow"))
        assertTrue(shell.contains("homeVm.refreshTrendingNow"))
    }

    @Test
    fun `navigation uses one glass panel rather than one backdrop per button`() {
        val topBar = source("tv/own/owntv/features/shell/components/TopBar.kt")
        val main = topBar.substringAfter("fun TopBar(").substringBefore("private fun NowPlayingNavButton")

        assertTrue(main.contains(".glass("))
        assertTrue(main.contains("GlassSurface.TOPBAR"))
        assertTrue(main.contains(".focusGroup()"))
        assertFalse(main.contains("surface = GlassSurface.TOPBAR"))
    }
}
