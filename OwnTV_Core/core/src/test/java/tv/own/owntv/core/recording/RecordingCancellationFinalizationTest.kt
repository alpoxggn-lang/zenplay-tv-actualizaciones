package tv.own.owntv.core.recording

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCancellationFinalizationTest {
    @Test
    fun `terminal persistence completes after the recording job is cancelled`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finalized = AtomicBoolean(false)
        val recording = launch {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                withRecordingFinalization {
                    // A real finalizer suspends while muxing DASH and updating Room.
                    delay(1)
                    finalized.set(true)
                }
            }
        }

        entered.await()
        recording.cancelAndJoin()

        assertTrue(finalized.get())
    }
}
