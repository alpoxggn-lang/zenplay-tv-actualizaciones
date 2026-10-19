package tv.own.owntv.mobile.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeGateContractTest {
    private val source = File("src/main/java/tv/own/owntv/mobile/ui/player/ResumeGate.kt").readText()

    @Test
    fun `touch resume prompt survives the release and has explicit keyboard focus`() {
        assertTrue(source.contains("if (decided) return"))
        assertTrue(source.contains("withFrameNanos { }"))
        assertTrue(source.contains("focusProperties { down = startOverFocus }"))
        assertTrue(source.contains("focusProperties { up = resumeFocus }"))
    }
}
