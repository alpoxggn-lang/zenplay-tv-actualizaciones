package tv.own.owntv.core.update

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateExecutionPolicyTest {
    @Test
    fun `only one update writer enters the staging file at a time`() = runBlocking {
        val gate = UpdateTransferGate()
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        coroutineScope {
            List(8) {
                async(Dispatchers.Default) {
                    gate.exclusively {
                        val now = active.incrementAndGet()
                        maximum.updateAndGet { previous -> maxOf(previous, now) }
                        delay(5)
                        active.decrementAndGet()
                    }
                }
            }.forEach { it.await() }
        }

        assertEquals(1, maximum.get())
    }

    @Test
    fun `cancelled writer releases gate for resumed work`() = runBlocking {
        val gate = UpdateTransferGate()
        val entered = CompletableDeferred<Unit>()
        val first = launch {
            gate.exclusively {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        first.cancelAndJoin()

        val enteredAfterCancellation = withTimeout(1_000) {
            gate.exclusively { true }
        }
        assertTrue(enteredAfterCancellation)
    }

    @Test
    fun `cancelling transfer interrupts its blocking operation before job can finish`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val unblock = CountDownLatch(1)
        val cancelCalled = AtomicBoolean()
        val transfer = launch(Dispatchers.IO) {
            val watcher = watchTransferCancellation {
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
        withTimeout(2_000) { transfer.join() }

        assertTrue(cancelCalled.get())
    }

    @Test
    fun `foreground launch retries remain bounded`() {
        assertTrue(shouldRetryUpdateWork(runAttemptCount = 0, maxAutomaticRetries = 4))
        assertTrue(shouldRetryUpdateWork(runAttemptCount = 3, maxAutomaticRetries = 4))
        assertFalse(shouldRetryUpdateWork(runAttemptCount = 4, maxAutomaticRetries = 4))
    }

    @Test
    fun `installer confirmation requires user action when app is backgrounded`() {
        assertEquals(InstallApprovalRoute.OPEN_NOW, installApprovalRoute(userIsActive = true))
        assertEquals(InstallApprovalRoute.NOTIFY, installApprovalRoute(userIsActive = false))
    }

    @Test
    fun `killed process recommits durable installer approval exactly once`() {
        assertFalse(
            shouldRecoverInstallApproval(
                durableApprovalPending = true,
                commitOwnedByThisProcess = true,
                recoveryAlreadyRunning = false,
            ),
        )
        assertTrue(
            shouldRecoverInstallApproval(
                durableApprovalPending = true,
                commitOwnedByThisProcess = false,
                recoveryAlreadyRunning = false,
            ),
        )
        assertFalse(
            shouldRecoverInstallApproval(
                durableApprovalPending = true,
                commitOwnedByThisProcess = false,
                recoveryAlreadyRunning = true,
            ),
        )
        assertFalse(
            shouldRecoverInstallApproval(
                durableApprovalPending = false,
                commitOwnedByThisProcess = false,
                recoveryAlreadyRunning = false,
            ),
        )
    }
}
