package tv.own.owntv.core.download

import android.content.Context
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
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StreamUrlResolver
import tv.own.owntv.core.storage.MediaTarget
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
    /** Only to resolve a stored `filePath` into something writable — a document needs a resolver. */
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val client: OkHttpClient,
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val streamUrlResolver: StreamUrlResolver,
    private val activityTracker: DownloadActivityTracker,
    private val contentAccess: ContentAccessPolicy,
) {
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
    private val revokedSources = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

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
        profileId !in revokedProfiles && sourceId !in revokedSources

    fun revokeProfile(profileId: Long) {
        revokedProfiles += profileId
    }

    fun revokeSource(sourceId: Long) {
        revokedSources += sourceId
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
        // Defence in depth for databases created by older/beta builds: one physical path may have
        // multiple logical owners. The engine must never be the writer that chooses whose bytes win.
        // Manager retry/resume moves the selected row to its stable private path first.
        val storedPath = d.filePath
        if (storedPath != null && downloadDao.countForFilePath(storedPath) > 1) {
            android.util.Log.w(TAG, "refusing ambiguous shared download target id=$id path=$storedPath")
            markFailed(id, d.downloadedBytes, d.totalBytes)
            return
        }
        val target = MediaTarget.of(context, d.filePath)
        // A download folder on removable storage can simply be gone (card pulled, USB unplugged),
        // and a folder the user picked through the system picker can have its grant revoked.
        // Fail loudly rather than silently re-homing gigabytes onto internal storage.
        if (target == null || !target.ensureWritable()) {
            android.util.Log.w(TAG, "download target unavailable id=$id path=${d.filePath}")
            markFailed(id, 0, d.totalBytes)
            return
        }
        // Resume whenever a partial file is on disk — including a RUNNING row left behind by a
        // process death — instead of only after an explicit pause. retry() deletes the file first,
        // so a deliberate restart still starts from zero.
        if (target.exists() && target.length() == 0L) target.truncate()
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
                markFailed(id, target.length(), current.totalBytes)
                return
            }
            val done = try {
                attemptDownload(id, d, target, onProgress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "download attempt $attempt/$MAX_ATTEMPTS failed id=$id: ${e.message}")
                false
            }
            if (done) return
            if (!currentCoroutineContext().isActive) return // paused/deleted — status already set by caller
            if (attempt >= MAX_ATTEMPTS) { markFailed(id, target.length(), d.totalBytes); return }
            delay(RETRY_DELAY_MS * attempt)
        }
    }

    /** One attempt. True means terminal (completed or access revoked); false/throws is retryable. */
    private suspend fun attemptDownload(
        id: Long,
        d: DownloadEntity,
        target: MediaTarget,
        onProgress: (DownloadProgress) -> Unit,
    ): Boolean {
        // Resolve at download-start time, fresh every attempt — the row keeps the stored cmd as the
        // item's identity; only this attempt's HTTP request sees the minted URL. A resolve failure
        // (portal down / bad auth) is a retryable attempt like any HTTP failure.
        val (url, userAgent) = resolveTarget(d)
        val existing = DownloadResume.resumeOffset(target)
        val rb = Request.Builder().url(url).header("User-Agent", userAgent)
        if (existing > 0) rb.header("Range", "bytes=$existing-")
        val call = client.newCall(rb.build())
        // OkHttp's synchronous read is blocking; coroutine cancellation alone does not wake it.
        // A structured watcher reaches its finally block as soon as cancellation starts; a normal
        // invokeOnCompletion callback would run only after this blocked read had already returned.
        val cancelCall = watchDownloadCancellation(call::cancel)
        try {
            call.execute().use { resp ->
            // 416 on a resume = our Range starts at/after the end of the resource — the file already
            // holds every byte the server has (a completed download whose COMPLETED write was lost to
            // a crash/disconnect). Without this, the retry loop would mark a finished file FAILED.
            if (resp.code == 416 && existing > 0) {
                downloadDao.updateProgress(id, DownloadStatus.COMPLETED, existing, existing, System.currentTimeMillis())
                return true
            }
            val body = resp.body
            if (!resp.isSuccessful) return false
            val append = resp.code == 206 && existing > 0 // server honoured the Range
            val total = DownloadResume.expectedTotal(append, existing, body.contentLength())
            var done = if (append) existing else 0L
            downloadDao.updateProgress(id, DownloadStatus.RUNNING, done, total, System.currentTimeMillis())
            onProgress(DownloadProgress(d.title, done, total))
            body.byteStream().use { input ->
                target.openOutput(append).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var lastTick = 0L
                    var lastAuthorizationCheck = System.currentTimeMillis()
                    while (true) {
                        if (!currentCoroutineContext().isActive) return false
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
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
                }
            }
            val size = target.length()
            downloadDao.updateProgress(id, DownloadStatus.COMPLETED, size, size, System.currentTimeMillis())
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
        val resolved = when (d.mediaType) {
            MediaType.MOVIE -> {
                val byId = movieDao.getById(d.itemId)?.takeIf { movie ->
                    movie.sourceId == d.sourceId &&
                        (DownloadIdentity.isLegacy(d.contentKey) || DownloadIdentity.movie(movie) == d.contentKey)
                }
                val movie = byId
                    ?: DownloadIdentity.movieRemoteId(d.contentKey)
                        ?.let { movieDao.findByRemote(d.sourceId, it) }
                    // M3U rows often have no provider id. Their stable key deliberately keeps only
                    // a one-way hash of the URL (credentials must not leak into an identity), while
                    // the download row already stores the original URL. Re-link through that URL
                    // only when it is unique inside this source and recreates the exact same key.
                    ?: movieDao.findByStreamUrl(d.sourceId, d.streamUrl)
                        .singleOrNull()
                        ?.takeIf { DownloadIdentity.movie(it) == d.contentKey }
                    ?: return null
                if (!contentAccess.allows(d.profileId, movie)) return null
                d.copy(itemId = movie.id, streamUrl = movie.streamUrl)
            }
            MediaType.EPISODE -> {
                val byId = seriesDao.getEpisodeById(d.itemId)?.let { episode ->
                    val show = seriesDao.getSeriesById(episode.seriesId)
                    if (show?.sourceId == d.sourceId &&
                        (DownloadIdentity.isLegacy(d.contentKey) || DownloadIdentity.episode(show, episode) == d.contentKey)
                    ) show to episode else null
                }
                val pair = byId
                    ?: DownloadIdentity.episodeRemoteIds(d.contentKey)?.let { (showRemote, episodeRemote) ->
                        seriesDao.findSeriesByRemote(d.sourceId, showRemote)?.let { show ->
                            seriesDao.findEpisodeByRemote(show.id, episodeRemote)?.let { show to it }
                        }
                    }
                    ?: seriesDao.findEpisodesByStreamUrl(d.sourceId, d.streamUrl)
                        .singleOrNull()
                        ?.let { episode ->
                            seriesDao.getSeriesById(episode.seriesId)?.let { show ->
                                (show to episode).takeIf {
                                    DownloadIdentity.episode(show, episode) == d.contentKey
                                }
                            }
                        }
                    ?: return null
                if (!contentAccess.allows(d.profileId, pair.second)) return null
                d.copy(itemId = pair.second.id, streamUrl = pair.second.streamUrl)
            }
            else -> return null
        }
        if (resolved.itemId != d.itemId || resolved.streamUrl != d.streamUrl) {
            val updated = downloadDao.relink(
                id = d.id,
                profileId = d.profileId,
                sourceId = d.sourceId,
                contentKey = d.contentKey,
                itemId = resolved.itemId,
                streamUrl = resolved.streamUrl,
                timestamp = System.currentTimeMillis(),
            )
            if (updated == 0) return null
        }
        return resolved
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
