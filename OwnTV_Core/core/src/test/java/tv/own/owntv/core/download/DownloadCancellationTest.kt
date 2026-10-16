package tv.own.owntv.core.download

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCancellationTest {
    @Test
    fun `cancelling download interrupts blocking network operation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val unblock = CountDownLatch(1)
        val cancelCalled = AtomicBoolean()
        val transfer = launch(Dispatchers.IO) {
            val watcher = watchDownloadCancellation {
                cancelCalled.set(true)
                unblock.countDown()
            }
            try {
                entered.complete(Unit)
                unblock.await()
            } finally {
                watcher.cancel()
            }
        }

        entered.await()
        transfer.cancel()
        withTimeout(2_000) { transfer.cancelAndJoin() }

        assertTrue(cancelCalled.get())
    }
}
