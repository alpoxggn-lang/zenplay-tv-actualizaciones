package tv.own.owntv.core.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-local second line of defence around the single APK staging file. */
internal class UpdateTransferGate {
    private val mutex = Mutex()

    suspend fun <T> exclusively(block: suspend () -> T): T = mutex.withLock { block() }
}

/** WorkManager starts at attempt zero, so four retries means at most five executions. */
internal fun shouldRetryUpdateWork(runAttemptCount: Int, maxAutomaticRetries: Int): Boolean =
    runAttemptCount < maxAutomaticRetries

/**
 * Runs [cancelBlockingOperation] as soon as the caller starts cancelling. Unlike a normal
 * `Job.invokeOnCompletion`, this child reaches its `finally` before the blocked parent can finish,
 * which lets OkHttp close a socket and wake `execute()` / `InputStream.read()` promptly.
 */
internal suspend fun watchTransferCancellation(cancelBlockingOperation: () -> Unit): Job =
    CoroutineScope(currentCoroutineContext()).launch(
        context = Dispatchers.IO,
        start = CoroutineStart.UNDISPATCHED,
    ) {
        try {
            awaitCancellation()
        } finally {
            cancelBlockingOperation()
        }
    }

