package tv.own.owntv.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveExoWatchdogContractTest {
    private val source = File("src/main/java/tv/own/owntv/player/LiveExoWatchdog.kt").readText()

    @Test
    fun `unsupported audio is judged only after the channel opens and stays under observation`() {
        val watcher = source.substringAfter("val opened = engine.state.first")
            .substringBefore("engine.audioOutputFailed.first")

        val openGate = watcher.indexOf("opened != LivePreviewEngine.State.PLAYING")
        val continuousWatch = watcher.indexOf("while (stillOurs())")
        val unsupportedWait = watcher.indexOf("engine.audioUnsupported.first { it }")
        val settle = watcher.indexOf("delay(TRACK_SETTLE_MS)")
        val recheck = watcher.indexOf("engine.audioUnsupported.value")

        assertTrue(openGate >= 0)
        assertTrue(continuousWatch > openGate)
        assertTrue(unsupportedWait > continuousWatch)
        assertTrue(settle > unsupportedWait)
        assertTrue(recheck > settle)
    }
}
