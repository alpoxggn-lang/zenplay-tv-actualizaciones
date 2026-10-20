package tv.own.owntv

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PipAvSyncContractTest {
    @Test
    fun `installed universal activity reanchors after returning from pip and rotation`() {
        val activity = File("src/main/java/tv/own/owntv/MainActivity.kt").readText()
        assertTrue(activity.contains("if (returningFromPip && usesTouchShell()) scheduleAvSyncReanchor()"))
        assertTrue(activity.contains("override fun onConfigurationChanged"))
        assertTrue(activity.contains("delay(350L)"))
        assertTrue(activity.contains("!mobileEngine.audioOnly.value"))
        assertTrue(activity.contains("!mobileEngine.audioOnlyMedia.value"))
        assertTrue(activity.contains("mobileEngine.reanchorAudioVideo()"))
    }
}
