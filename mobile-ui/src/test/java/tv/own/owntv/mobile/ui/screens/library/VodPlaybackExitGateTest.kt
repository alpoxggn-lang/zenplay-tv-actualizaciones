package tv.own.owntv.mobile.ui.screens.library

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPlaybackExitGateTest {
    @Test
    fun `blocked persistence from title A cannot stop replacement title B`() = runBlocking {
        val gate = VodPlaybackExitGate()
        gate.playbackStarted() // title A
        val exitA = gate.requestExit()!!
        val persistence = CompletableDeferred<Unit>()
        var playerStopped = false

        val delayedExit = launch(start = CoroutineStart.UNDISPATCHED) {
            persistence.await()
            if (gate.commitExit(exitA)) playerStopped = true
        }

        gate.playbackStarted() // title B opens while A's Room write is blocked
        persistence.complete(Unit)
        delayedExit.join()

        assertFalse(playerStopped)
        assertTrue(gate.commitExit(gate.requestExit()!!))
    }

    @Test
    fun `VodTuner snapshots before persistence and commits ticket before teardown`() {
        val source = File(
            "src/main/java/tv/own/owntv/mobile/ui/screens/library/VodTuner.kt",
        ).readText()
        val stop = source.substringAfter("fun stop() {")
            .substringBefore("override fun releaseToCast")

        assertTrue(stop.indexOf("val progress = progressSnapshot()") < stop.indexOf("scope.launch"))
        assertTrue(stop.indexOf("commitExit(ticket)") < stop.indexOf("player.stop()"))
        assertTrue(stop.contains("if (!playbackExitGate.commitExit(ticket)) return@launch"))
    }
}
