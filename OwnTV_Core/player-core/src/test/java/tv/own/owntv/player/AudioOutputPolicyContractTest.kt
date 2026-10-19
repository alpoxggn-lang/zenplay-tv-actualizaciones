package tv.own.owntv.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect weak-network playback from being misdiagnosed as a broken audio output. */
class AudioOutputPolicyContractTest {
    private val source = File("src/main/java/tv/own/owntv/player/AudioOutputPolicy.kt").readText()

    @Test
    fun `audio underruns stay diagnostic and cannot trigger output recovery`() {
        val underrunHandler = source.substringAfter("override fun onAudioUnderrun(")
            .substringBefore("override fun onAudioSinkError(")

        assertTrue(underrunHandler.contains("audio underrun:"))
        assertFalse(underrunHandler.contains("raise("))
        assertFalse(underrunHandler.contains("pendingReason"))
    }

    @Test
    fun `strong sink and no-advance signals remain active`() {
        val sinkHandler = source.substringAfter("override fun onAudioSinkError(")
            .substringBefore("private fun raise(")
        val poll = source.substringAfter("fun poll(isPlaying: Boolean)")
            .substringBefore("fun describe()")

        assertTrue(sinkHandler.contains("raise("))
        assertTrue(poll.contains("NO_AUDIO_GRACE_MS"))
        assertTrue(poll.contains("return \"no sound from the audio output"))
    }
}
