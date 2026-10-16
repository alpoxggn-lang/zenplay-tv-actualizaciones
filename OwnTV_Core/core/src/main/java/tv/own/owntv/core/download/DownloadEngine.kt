package tv.own.owntv.core.download

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.own.owntv.core.content.ContentAccessPolicy
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.download.secure.ProtectedDownloadStore
import tv.own.owntv.core.download.secure.SecureDownloadOwner
import tv.own.owntv.core.download.secure.SecureDownloadWriter
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StreamUrlResolver
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Separates permanent source deletion from the temporary gates used for credential rotation and
 * provider entitlement.
 *
 * A single tombstone set cannot safely support both operations: removing that tombstone after an
 * edit or subscription renewal can accidentally undo a concurrent real deletion. Rotation holds
 * are reference-counted so two overlapping edits cannot re-enable enqueueing until both finish;
 * entitlement is independently reversible after a provider-confirmed renewal.
 */
internal class DownloadSourceRevocations {
    private val permanent = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val entitlementRevoked = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val rotationHolds = ConcurrentHashMap<Long, Int>()

    fun accepts(sourceId: Long): Boolean =
        sourceId !in permanent && sourceId !in entitlementRevoked && !rotationHolds.containsKey(sourceId)

    fun revokePermanently(sourceId: Long) {
        permanent += sourceId
    }

    fun holdForRotation(sourceId: Long) {
        rotationHolds.compute(sourceId) { _, count -> (count ?: 0) + 1 }
    }

    fun releaseRotation(sourceId: Long) {
        rotationHolds.computeIfPresent(sourceId) { _, count ->
            (count - 1).takeIf { it > 0 }
        }
    }

    fun revokeEntitlement(sourceId: Long) {
        entitlementRevoked += sourceId
    }

    /** A confirmed renewal removes only the reversible entitlement gate. */
    fun restoreEntitlement(sourceId: Long) {
        entitlementRevoked -= sourceId
    }
}

/** What the foreground notification shows about the transfer currently running. */
data class DownloadProgress(val title: String, val downloadedBytes: Long, val totalBytes: Long)

/**
 * The transfer half of downloads, split out of [DownloadManager] so it can run inside
 * [DownloadWorker] — a foreground service — instead of an app-scoped coroutine that Android kills
 * as soon as the user leaves OwnTV (audit item DL1).
 *
 * [DownloadDao] stays the single source of truth: the engine holds no queue of its own, it just
 * drains whatever rows are QUEUED/RUNNING, one at a time, until none are left.
 */
class DownloadEngine(
    private val downloadDao: DownloadDao,
    private val client: OkHttpClient,
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val streamUrlResolver: StreamUrlResolver,
    private val activityTracker: DownloadActivityTracker,
    private val contentAccess: ContentAccessPolicy,
    private val protectedStore: ProtectedDownloadStore,
) {
    private val itemResolver = DownloadItemResolver(downloadDao, movieDao, seriesDao)
    /** Jobs of transfers currently running, so pause/delete/retry can stop one precisely. */
    private val active = ConcurrentHashMap<Long, Job>()

    /** WorkManager may briefly overlap a replaced worker with its successor; only one may drain. */
    private val drainMutex = Mutex()

    /**
     * Ids the user has just paused/deleted. The drain loop skips them: without this, a pause would
     * be undone the moment the loop re-read the queue before the PAUSED row was written.
     */
    private val suppressed = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    /** Permanent process-local tombstones close the enqueue/delete race during owner removal. */
    private val revokedProfiles = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val sourceRevocations = DownloadSourceRevocations()

    /** Set whenever a row is queued, so a drain that is about to finish takes one more look. */
    @Volatile
    private var queueDirty = false

    fun markQueued() {
        queueDirty = true
    }

    /**
     * Run every pending download, oldest first, one at a time, returning when the queue is empty.
     * Each transfer runs as a child job so that cancelling it (pause/delete) does not tear down the
     * drain itself — the next queued item still starts.
     */
    suspend fun drainQueue(onProgress: (DownloadProgress) -> Unit) {
        drainMutex.withLock {
            coroutineScope {
                // Every progress report also feeds the shell's status pill, so a transfer is visible
                // in the app and not only in the notification shade.
                val report: (DownloadProgress) -> Unit = { activityTracker.progress(it); onProgress(it) }
                val seen = mutableSetOf<Long>()
                while (currentCoroutineContext().isActive) {
                    queueDirty = false
                    val next = downloadDao.pending().firstOrNull { it.id !in suppressed && it.id !in seen }
                    if (next == null) {
                        // Something was queued between the read above and now: look again rather than
                        // exit, otherwise a KEEP kick that follows it would be dropped.
                        if (queueDirty) continue else return@coroutineScope
                    }
                    seen += next.id
                    // Register before starting. Owner deletion can otherwise add `suppressed` after
                    // selection but before `active` contains the job, miss cancellation, and delete
                    // the row while this coroutine keeps writing its file.
                    val job = launch(start = CoroutineStart.LAZY) { runDownload(next.id, report) }
                    active[next.id] = job
                    if (next.id in suppressed) job.cancel() else job.start()
                    try {
                        job.join()
                    } finally {
                        active.remove(next.id, job)
                        // Completed, failed, paused or deleted — either way this transfer is no longer live.
                        activityTracker.finished()
                    }
                }
            }
        }
    }

    fun accepts(profileId: Long, sourceId: Long): Boolean =
        profileId !in revokedProfiles && sourceRevocations.accepts(sourceId)

    fun revokeProfile(profileId: Long) {
        revokedProfiles += profileId
    }

    fun revokeSource(sourceId: Long) {
        sourceRevocations.revokePermanently(sourceId)
    }

    fun revokeSourceEntitlement(sourceId: Long) {
        sourceRevocations.revokeEntitlement(sourceId)
    }

    fun restoreSourceEntitlement(sourceId: Long) {
        sourceRevocations.restoreEntitlement(sourceId)
    }

    /** Temporarily refuse new work while an existing source receives a new account identity. */
    fun holdSourceForRotation(sourceId: Long) {
        sourceRevocations.holdForRotation(sourceId)
    }

    /** Release only a rotation hold; a permanent deletion tombstone always wins and remains set. */
    fun releaseSource(sourceId: Long) {
        sourceRevocations.releaseRotation(sourceId)
    }

    /**
     * Stop the transfer of [id] if it is running and wait for it to actually let go of the file,
     * keeping the drain loop off it until [release] is called. Callers write the row's new status
     * in between, while nothing can touch it.
     */
    suspend fun suspendTransfer(id: Long) {
        suppressed += id
        active.remove(id)?.cancelAndJoin()
    }

    fun release(id: Long) {
        suppressed -= id
    }

    private suspend fun runDownload(id: Long, onProgress: (DownloadProgress) -> Unit) {
        val queued = downloadDao.getById(id) ?: return
        var d = authorized(queued)
        if (d == null) {
            android.util.Log.w(TAG, "download authorization failed id=$id")
            markFailed(id, queued.downloadedBytes, queued.totalBytes)
            return
        }
        val owner = ownerOf(d)
        val fileId = protectedFileId(d.filePath)
        if (fileId == null || !protectedStore.exists(fileId)) {
            android.util.Log.w(TAG, "protected download target unavailable id=$id")
            markFailed(id, 0, d.totalBytes)
            return
        }
        // A process can die after writing the authenticated footer but before Room records
        // COMPLETED. Recognise that committed container instead of trying to resume it.
        completedLength(fileId, owner)?.let { size ->
            downloadDao.updateProgress(id, DownloadStatus.COMPLETED, size, size, System.currentTimeMillis())
            return
        }
        // Attempt loop (plan D-3): a Stalker `create_link` URL dies after ~2-4 h, so a long download
        // can fail mid-stream. Each attempt re-resolves a FRESH URL from the stored cmd and resumes
        // with an HTTP Range from the bytes already written; a server that ignores Range restarts the
        // file from 0. M3U/Xtream pass through the resolver unchanged, so for them the loop is just a
        // plain transient-error retry with the same URL.
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            attempt++
            // A queued transfer may wait for hours. Re-read and re-authorize immediately before
            // every network attempt so a deleted profile, removed playlist or newly hidden/adult
            // item cannot start (or resume after a retry) with stale permission.
            val current = downloadDao.getById(id) ?: return
            d = authorized(current)
            if (d == null) {
                android.util.Log.w(TAG, "download authorization failed id=$id attempt=$attempt")
                markFailed(id, committedBytes(current), current.totalBytes)
                return
            }
            val done = try {
                attemptDownload(id, d, onProgress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "download attempt $attempt/$MAX_ATTEMPTS failed id=$id: ${e.message}")
                false
            }
            if (done) return
            if (!currentCoroutineContext().isActive) return // paused/deleted — status already set by caller
            if (attempt >= MAX_ATTEMPTS) { markFailed(id, committedBytes(d), d.totalBytes); return }
            delay(RETRY_DELAY_MS * attempt)
        }
    }

    /** One attempt. True means terminal (completed or access revoked); false/throws is retryable. */
    private suspend fun attemptDownload(
        id: Long,
        d: DownloadEntity,
        onProgress: (DownloadProgress) -> Unit,
    ): Boolean {
        // Resolve at download-start time, fresh every attempt — the row keeps the stored cmd as the
        // item's identity; only this attempt's HTTP request sees the minted URL. A resolve failure
        // (portal down / bad auth) is a retryable attempt like any HTTP failure.
        val (url, userAgent) = resolveTarget(d)
        val owner = ownerOf(d)
        var fileId = protectedFileId(d.filePath)
            ?: throw java.io.IOException("Missing protected download id")
        val existing = resumeHandle(fileId, owner).useWriter { it.committedPlaintextBytes }
        val rb = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            // Resume offsets count the bytes encrypted in our container. Transparent HTTP gzip
            // would make those offsets describe decoded bytes while Range addresses wire bytes.
            .header("Accept-Encoding", "identity")
        if (existing > 0) rb.header("Range", "bytes=$existing-")
        val call = client.newCall(rb.build())
        // OkHttp's synchronous read is blocking; coroutine cancellation alone does not wake it.
        // A structured watcher reaches its finally block as soon as cancellation starts; a normal
        // invokeOnCompletion callback would run only after this blocked read had already returned.
        val cancelCall = watchDownloadCancellation(call::cancel)
        try {
            call.execute().use { resp ->
            val body = resp.body
            val plan = planDownloadResponse(
                existingBytes = existing,
                responseCode = resp.code,
                contentRangeHeader = resp.header("Content-Range"),
                contentLength = body.contentLength(),
            ) ?: return false
            // A 416 is completion only when `Content-Range: bytes */N` says N is exactly the number
            // of locally authenticated bytes. A generic/mismatched 416 is never enough evidence.
            if (plan.mode == DownloadWriteMode.ALREADY_COMPLETE) {
                val size = resumeHandle(fileId, owner).useWriter { it.finish() }
                downloadDao.updateProgress(id, DownloadStatus.COMPLETED, size, size, System.currentTimeMillis())
                return true
            }
            val append = plan.mode == DownloadWriteMode.APPEND
            val total = plan.expectedTotalBytes ?: if (append) existing else 0L
            var done = if (append) existing else 0L
            val handle = if (plan.mode == DownloadWriteMode.APPEND || existing == 0L) {
                resumeHandle(fileId, owner)
            } else {
                // The server ignored Range. A protected container cannot be truncated in place
                // without risking nonce/key reuse, so replace it atomically with a fresh file/key.
                val replacement = protectedStore.create(owner)
                if (!protectedStore.revoke(fileId)) {
                    replacement.writer.close()
                    if (!protectedStore.delete(replacement.fileId)) {
                        android.util.Log.w(TAG, "replacement cleanup pending id=$id fileId=${replacement.fileId}")
                    }
                    android.util.Log.w(TAG, "cannot persist old protected download revocation id=$id")
                    return false
                }
                val moved = downloadDao.updateFilePathForProfile(
                    id,
                    d.profileId,
                    replacement.contentUri.toString(),
                    System.currentTimeMillis(),
                )
                if (moved == 0) {
                    replacement.writer.close()
                    if (!protectedStore.delete(replacement.fileId)) {
                        android.util.Log.w(TAG, "replacement cleanup pending id=$id fileId=${replacement.fileId}")
                    }
                    return true
                }
                if (!protectedStore.delete(fileId)) {
                    // The old URI is already durably revoked; startup retries physical deletion.
                    android.util.Log.w(TAG, "old protected download cleanup pending id=$id fileId=$fileId")
                }
                fileId = replacement.fileId
                done = 0L
                replacement
            }
            downloadDao.updateProgress(id, DownloadStatus.RUNNING, done, total, System.currentTimeMillis())
            onProgress(DownloadProgress(d.title, done, total))
            body.byteStream().use { input ->
                handle.writer.use { out ->
                    val buf = ByteArray(128 * 1024)
                    var responseBytes = 0L
                    var lastTick = 0L
                    var lastAuthorizationCheck = System.currentTimeMillis()
                    while (true) {
                        if (!currentCoroutineContext().isActive) return false
                        val n = input.read(buf)
                        if (n < 0) break
                        if (!acceptsDownloadResponseBlock(plan, responseBytes, n)) return false
                        out.write(buf, 0, n)
                        responseBytes = Math.addExact(responseBytes, n.toLong())
                        done = out.committedPlaintextBytes
                        val t = System.currentTimeMillis()
                        if (t - lastAuthorizationCheck >= AUTHORIZATION_RECHECK_MS) {
                            val current = downloadDao.getById(id)
                            if (current == null || authorized(current) == null) {
                                android.util.Log.w(TAG, "download authorization revoked while running id=$id")
                                if (current != null) markFailed(id, done, total)
                                return true
                            }
                            lastAuthorizationCheck = t
                        }
                        if (t - lastTick > 500) {
                            downloadDao.updateProgress(id, DownloadStatus.RUNNING, done, total, t)
                            onProgress(DownloadProgress(d.title, done, total))
                            lastTick = t
                        }
                    }
                    // A CDN may return a valid-looking but deliberately shortened 206 slice. Do not
                    // authenticate a footer until EOF also reaches the declared whole-object size.
                    if (!completesDownloadResponse(plan, existing, responseBytes)) {
                        return false
                    }
                    done = out.finish()
                }
            }
            downloadDao.updateProgress(id, DownloadStatus.COMPLETED, done, done, System.currentTimeMillis())
                return true
            }
        } finally {
            cancelCall.cancel()
        }
    }

    /**
     * Resolve a volatile catalogue id from the stable key when possible, then apply the same final
     * access policy used by playback. Returning null is intentionally fail-closed.
     */
    private suspend fun authorized(d: DownloadEntity): DownloadEntity? {
        if (!accepts(d.profileId, d.sourceId) ||
            d.profileId < 0L || d.sourceId < 0L || sourceDao.getById(d.sourceId) == null
        ) return null
        val resolved = itemResolver.resolve(d) ?: return null
        val allowed = when (resolved.mediaType) {
            MediaType.MOVIE -> movieDao.getById(resolved.itemId)?.let { contentAccess.allows(d.profileId, it) } == true
            MediaType.EPISODE -> seriesDao.getEpisodeById(resolved.itemId)
                ?.let { contentAccess.allows(d.profileId, it) } == true
            else -> false
        }
        return resolved.takeIf { allowed }
    }

    /**
     * The URL + User-Agent this download should fetch. Non-Stalker rows return their stored
     * `streamUrl` untouched (byte-identical to the pre-D-3 behavior). Stalker rows mint a playable
     * URL via `create_link` from the stored cmd — episodes carry `series=<ep>` (the season cmd is
     * shared, looked up from the episode row) — and fetch with the source's MAG-style User-Agent.
     * If the catalog item was pruned by a re-sync mid-queue, falls back to the stored URL (a Stalker
     * cmd will then fail → FAILED, which is the honest outcome).
     */
    private suspend fun resolveTarget(d: DownloadEntity): Pair<String, String> {
        val (sourceId, episode) = when (d.mediaType) {
            MediaType.EPISODE -> {
                val ep = seriesDao.getEpisodeById(d.itemId)
                val show = ep?.let { seriesDao.getSeriesById(it.seriesId) }
                show?.sourceId to ep?.episodeNumber
            }
            else -> movieDao.getById(d.itemId)?.sourceId to null
        }
        val source = sourceId?.let { sourceDao.getById(it) }
        if (source == null || !streamUrlResolver.needsResolve(source)) {
            return d.streamUrl to HttpClient.DEFAULT_USER_AGENT
        }
        val ua = source.userAgent?.takeIf { it.isNotBlank() } ?: StalkerClient.DEFAULT_MAG_USER_AGENT
        return streamUrlResolver.resolve(source, d.streamUrl, vod = true, episode = episode) to ua
    }

    private fun ownerOf(download: DownloadEntity): SecureDownloadOwner =
        SecureDownloadOwner.fromContentKey(
            profileId = download.profileId,
            sourceId = download.sourceId,
            contentKey = download.contentKey,
        )

    private fun protectedFileId(stored: String?): UUID? = stored
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?.let(protectedStore::fileId)

    private fun resumeHandle(
        fileId: UUID,
        owner: SecureDownloadOwner,
    ): ProtectedDownloadStore.WriteHandle = protectedStore.resume(fileId, owner)

    private fun completedLength(fileId: UUID, owner: SecureDownloadOwner): Long? =
        runCatching { protectedStore.open(fileId, owner).use { it.plaintextLength } }.getOrNull()

    private fun committedBytes(download: DownloadEntity): Long {
        val fileId = protectedFileId(download.filePath) ?: return 0L
        val owner = runCatching { ownerOf(download) }.getOrNull() ?: return 0L
        return completedLength(fileId, owner)
            ?: runCatching {
                resumeHandle(fileId, owner).writer.use { it.committedPlaintextBytes }
            }.getOrDefault(0L)
    }

    private inline fun <T> ProtectedDownloadStore.WriteHandle.useWriter(
        block: (SecureDownloadWriter) -> T,
    ): T = writer.use(block)

    /** Keep the real partial byte count — a 90%-then-failed download showing 0 bytes is misleading,
     *  and the partial file IS still on disk (resume/retry can use it). */
    private suspend fun markFailed(id: Long, downloaded: Long, total: Long) {
        downloadDao.updateProgress(id, DownloadStatus.FAILED, downloaded.coerceAtLeast(0), total, System.currentTimeMillis())
    }

    private companion object {
        const val TAG = "DownloadManager"

        /** Attempts per download — attempt 2/3 re-resolve the URL and resume via Range (D-3). */
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_000L
        const val AUTHORIZATION_RECHECK_MS = 2_000L
    }
}

/** Wakes a blocking OkHttp execute/read as soon as the owning transfer starts cancelling. */
internal suspend fun watchDownloadCancellation(cancelBlockingOperation: () -> Unit): Job =
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
