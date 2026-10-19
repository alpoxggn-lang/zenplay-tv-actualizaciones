package tv.own.owntv.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeDialogContractTest {
    private fun source(relative: String) = File("src/main/java/$relative").readText()

    @Test
    fun `resume prompt owns a focus isolated window and explicit dpad route`() {
        val dialog = source("tv/own/owntv/ui/components/ResumeDialog.kt")

        assertTrue(dialog.contains("OwnTVPopup("))
        assertTrue(dialog.contains("fontScale = 1f"))
        assertTrue(dialog.contains("layoutScale = 1f"))
        assertTrue(dialog.contains("focusProperties { right = resumeFocus }"))
        assertTrue(dialog.contains("focusProperties { left = startOverFocus }"))
        assertTrue(dialog.contains("if (!decision.claim()) return"))
        assertTrue(dialog.contains("withFrameNanos { }"))
    }

    @Test
    fun `movies and both series layouts share the guarded resume prompt`() {
        val consumers = listOf(
            "tv/own/owntv/features/movies/MoviesScreen.kt",
            "tv/own/owntv/features/series/SeriesScreen.kt",
            "tv/own/owntv/features/series/ZenPlaySeriesDetails.kt",
        )

        consumers.forEach { path ->
            val screen = source(path)
            assertTrue("$path must use the shared resume dialog", screen.contains("ResumeDialog("))
            assertTrue("$path must offer resume", screen.contains("onResume ="))
            assertTrue("$path must offer start over", screen.contains("onStartOver ="))
        }
    }
}
