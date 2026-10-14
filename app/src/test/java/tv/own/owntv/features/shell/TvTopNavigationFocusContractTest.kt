package tv.own.owntv.features.shell

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the D-pad route from browse destinations back to the single global TV navigation bar. */
class TvTopNavigationFocusContractTest {
    private fun source(relative: String): String = File("src/main/java/$relative").readText()

    @Test
    fun `destination group routes an up exit to the selected top navigation item`() {
        val shell = source("tv/own/owntv/features/shell/OwnTVShell.kt")
        val focusHelpers = source("tv/own/owntv/ui/components/FocusTrap.kt")

        assertTrue(shell.contains(".returnToTopNavigationOnUp(sidebarFocus)"))
        assertTrue(
            focusHelpers.substringAfter("fun Modifier.returnToTopNavigationOnUp")
                .substringBefore("\n}\n")
                .contains("requestedFocusDirection == FocusDirection.Up"),
        )
        assertTrue(focusHelpers.contains("topNavigationFocus.requestFocus()"))
    }

    @Test
    fun `browse panes protect only their bottom edge and leave up available`() {
        val categoryRail = source("tv/own/owntv/features/shell/components/CategoryRail.kt")
        val movies = source("tv/own/owntv/features/movies/MoviesScreen.kt")
        val series = source("tv/own/owntv/features/series/SeriesScreen.kt")

        listOf(categoryRail, movies, series).forEach { browseSource ->
            assertTrue(browseSource.contains("trapDownFocusExit"))
            assertFalse(browseSource.contains("trapVerticalFocusExit()"))
        }

        val downOnly = source("tv/own/owntv/ui/components/FocusTrap.kt")
            .substringAfter("fun Modifier.trapDownFocusExit")
            .substringBefore("\n}\n")
        assertTrue(downOnly.contains("requestedFocusDirection == FocusDirection.Down"))
        assertFalse(downOnly.contains("FocusDirection.Up"))
    }

    @Test
    fun `live channel pane no longer traps an up exit`() {
        val live = source("tv/own/owntv/features/live/LiveScreen.kt")
        val exitPolicy = live.substringAfter("if (lockedKey == null) {")
            .substringAfter("Modifier.focusProperties")
            .substringBefore("} else {")

        assertTrue(exitPolicy.contains("requestedFocusDirection == FocusDirection.Down"))
        assertTrue(exitPolicy.contains("requestedFocusDirection == FocusDirection.Right"))
        assertFalse(exitPolicy.contains("requestedFocusDirection == FocusDirection.Up"))
    }
}
