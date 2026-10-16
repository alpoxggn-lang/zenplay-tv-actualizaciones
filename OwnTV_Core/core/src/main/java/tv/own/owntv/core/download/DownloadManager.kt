package tv.own.owntv.core.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.storage.MediaFolders
import tv.own.owntv.core.storage.MediaRoot
import tv.own.owntv.core.storage.MediaTarget
import tv.own.owntv.core.storage.StorageAccess
import tv.own.owntv.core.settings.SettingsRepository

/** Free/total bytes of the volume backing the download root. */
data class DownloadStorageInfo(
    val freeBytes: Long,
    val totalBytes: Long,
    /** The chosen folder is missing (USB removed, permission withdrawn), so the app's own folder is in use. */
    val usingFallback: Boolean = false,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedFraction: Float get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Phase 12 — downloads movies & series episodes for offline playback. Files go under the user-chosen
 * download folder, organised as `Movies/<name>.<ext>` and `Series/<show>/Season N/<episode>.<ext>`.
 *
 * This is the queue-control half only: it writes [DownloadDao] rows and hands the actual transfers
 * to [DownloadWorker]/[DownloadEngine], which run in a foreground service and therefore survive the
 * user leaving the app (audit item DL1). [DownloadDao] remains the single source of truth, and
 * downloads still run strictly one at a time.
 */
class DownloadManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val settings: SettingsRepository,
    private val engine: DownloadEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Serializes physical-target creation and row/file mutations, including owner cleanup. */
    private val mutationMutex = Mutex()

    init {
        // Anything left QUEUED — or RUNNING when the process was killed — is picked up again.
        engine.markQueued()
        scope.launch { DownloadWorker.kick(context, settings.downloadsWifiOnlyNow()) }
        // Turning "Wi-Fi only" on has to reach a transfer that is already running on mobile data, and
        // the queue's own work is KEEP — it would keep the constraint it was enqueued with. So the
        // switch replaces the work instead: the running row stays RUNNING, and the replacement worker
        // resumes it from the partial file once the network it is now waiting for arrives.
        scope.launch {
            settings.downloadsWifiOnly.drop(1).distinctUntilChanged().collect { wifiOnly ->
                DownloadWorker.kick(context, wifiOnly, replace = true)
            }
        }
    }

    fun observe(profileId: Long): Flow<List<DownloadEntity>> = downloadDao.observeForProfile(profileId)

    /** Episode downloads for one series (poster-panel aggregate status). */
    fun observeForSeries(profileId: Long, seriesId: Long): Flow<List<DownloadEntity>> =
        downloadDao.observeForSeries(profileId, seriesId)

    /** Free/total space of the volume holding the current download root (for the Downloads storage bar). */
    suspend fun storageInfo(): DownloadStorageInfo = withContext(Dispatchers.IO) {
        val configured = settings.downloadRoot.first()
        val space = runCatching { MediaRoot.of(context, configured).space() }
            .getOrNull() ?: MediaRoot.Path(StorageAccess.defaultRoot(context)).space()
        DownloadStorageInfo(
            freeBytes = space.freeBytes,
            totalBytes = space.totalBytes,
            usingFallback = runCatching { MediaRoot.isFallback(context, configured) }.getOrDefault(false),
        )
    }

    /**
     * Queue a download into `<root>/<relativeDir>/<fileName>`.
     *
     * The destination is made now rather than when the transfer starts, because that is the moment
     * the row's `filePath` has to name something real — a SAF document has to be created before it
     * has a URI at all, where a path could simply be written down.
     *
     * A chosen folder that is missing right now falls back to the app's own folder ([MediaRoot.of]).
     * A root that still cannot produce a file — the volume went away mid-way — still gets a row, with no path. The engine then fails it in the ordinary way and the user sees
     * a failed download saying so, which is what happened before this could fail at all. Queuing
     * nothing would leave the Download button looking broken.
     */
    fun enqueue(
        profileId: Long, sourceId: Long, contentKey: String,
        mediaType: MediaType, itemId: Long, title: String, posterUrl: String?,
        streamUrl: String, relativeDir: String, fileName: String,
    ) {
        scope.launch {
            val inserted = mutationMutex.withLock {
                if (profileId < 0L || sourceId < 0L || contentKey.isBlank() ||
                    !engine.accepts(profileId, sourceId)
                ) return@withLock false
                // Check before touching storage: an existing row may have been moved/exported to a
                // different path, and creating its old scoped target would leak an empty orphan.
                if (downloadDao.getByIdentity(profileId, sourceId, contentKey) != null) {
                    return@withLock false
                }
                val root = MediaRoot.of(context, settings.downloadRoot.first())
                val target = root.child(
                    DownloadIdentity.scopedDirectory(profileId, sourceId, relativeDir),
                    DownloadIdentity.scopedFileName(fileName, contentKey),
                )
                if (target == null) {
                    android.util.Log.w(TAG, "cannot create download target in ${root.stored} for $fileName")
                }
                val rowId = downloadDao.insertIfAbsent(
                    DownloadEntity(
                        profileId = profileId, sourceId = sourceId, contentKey = contentKey,
                        mediaType = mediaType, itemId = itemId, title = title,
                        posterUrl = posterUrl, streamUrl = streamUrl, filePath = target?.stored,
                        status = DownloadStatus.QUEUED,
                    ),
                )
                if (rowId == -1L) deleteFileIfUnreferenced(target?.stored)
                rowId != -1L
            }
            if (inserted) kick()
        }
    }

    fun retry(download: DownloadEntity) {
        scope.launch {
            val queued = mutationMutex.withLock {
                val owned = owned(download) ?: return@withLock false
                // Stop a still-running attempt BEFORE touching its file — otherwise the writer keeps
                // streaming into the old target while retry resets the row underneath it.
                engine.suspendTransfer(owned.id)
                try {
                    // v46 and older did not isolate paths by profile/source. If another migrated row
                    // still references this target, repoint this row to a new scoped target before
                    // truncating so retry cannot destroy the other profile's completed download.
                    val target = privateRetryTarget(owned) ?: return@withLock false
                    target.truncate()
                    downloadDao.updateProgressForProfile(
                        owned.id,
                        owned.profileId,
                        DownloadStatus.QUEUED,
                        0,
                        owned.totalBytes,
                        System.currentTimeMillis(),
                    ) > 0
                } finally {
                    engine.release(owned.id)
                }
            }
            if (queued) kick()
        }
    }

    /** Stop the running download but keep the partial file so it can resume. */
    fun pause(download: DownloadEntity) {
        scope.launch {
            mutationMutex.withLock {
                val owned = owned(download) ?: return@withLock
                // Wait for the writer to actually stop before recording how far it got (DL2).
                engine.suspendTransfer(owned.id)
                try {
                    val d = downloadDao.getByIdForProfile(owned.id, owned.profileId) ?: return@withLock
                    val bytes = DownloadResume.bytesOnDisk(MediaTarget.of(context, d.filePath), d.downloadedBytes)
                    downloadDao.updateProgressForProfile(
                        d.id,
                        d.profileId,
                        DownloadStatus.PAUSED,
                        bytes,
                        d.totalBytes,
                        System.currentTimeMillis(),
                    )
                } finally {
                    engine.release(owned.id)
                }
            }
        }
    }

    /** Continue a paused download from where it stopped (HTTP Range). */
    fun resume(download: DownloadEntity) {
        scope.launch {
            val queued = mutationMutex.withLock {
                val d = owned(download) ?: return@withLock false
                // A paused v46 row may still share its title-based path with another owner. Give it
                // a private destination before it becomes QUEUED; otherwise WorkManager could append
                // this stream to another profile's completed file.
                val target = privateRetryTarget(d) ?: return@withLock false
                val bytes = DownloadResume.bytesOnDisk(target, d.downloadedBytes)
                downloadDao.updateProgressForProfile(
                    d.id,
                    d.profileId,
                    DownloadStatus.QUEUED,
                    bytes,
                    d.totalBytes,
                    System.currentTimeMillis(),
                ) > 0
            }
            if (queued) kick()
        }
    }

    fun delete(download: DownloadEntity) {
        scope.launch {
            val deleted = mutationMutex.withLock {
                val owned = owned(download) ?: return@withLock false
                engine.suspendTransfer(owned.id)
                try {
                    val removed = downloadDao.deleteByIdForProfile(owned.id, owned.profileId) > 0
                    if (removed) deleteFileIfUnreferenced(owned.filePath)
                    removed
                } finally {
                    engine.release(owned.id)
                }
            }
            if (deleted) kick()
        }
    }

    /** Cancel and remove every download owned by a profile before its FK cascade removes the rows. */
    suspend fun removeForProfile(profileId: Long) {
        if (profileId < 0L) return
        engine.revokeProfile(profileId)
        mutationMutex.withLock {
            removeRows(downloadDao.getAllForProfile(profileId)) {
                downloadDao.deleteAllForProfile(profileId)
            }
        }
        kick()
    }

    /** Cancel and remove every download from a playlist before its catalogue/credentials disappear. */
    suspend fun removeForSource(sourceId: Long) {
        if (sourceId < 0L) return
        engine.revokeSource(sourceId)
        mutationMutex.withLock {
            removeRows(downloadDao.getAllForSource(sourceId)) {
                downloadDao.deleteAllForSource(sourceId)
            }
        }
        kick()
    }

    private suspend fun removeRows(rows: List<DownloadEntity>, deleteRows: suspend () -> Unit) {
        if (rows.isEmpty()) return
        rows.forEach { engine.suspendTransfer(it.id) }
        try {
            deleteRows()
            rows.mapNotNull { it.filePath }.distinct().forEach { deleteFileIfUnreferenced(it) }
        } finally {
            rows.forEach { engine.release(it.id) }
        }
    }

    /** A migrated shared target is read-only to this row until retry gives it a private path. */
    private suspend fun privateRetryTarget(download: DownloadEntity): MediaTarget? {
        val current = MediaTarget.of(context, download.filePath) ?: return null
        val path = download.filePath ?: return null
        if (downloadDao.countForFilePath(path) <= 1) return current

        val root = MediaRoot.of(context, settings.downloadRoot.first())
        val requested = "${StorageAccess.sanitize(download.title)}.${StorageAccess.extOf(download.streamUrl)}"
        val replacement = root.child(
            DownloadIdentity.scopedDirectory(
                download.profileId,
                download.sourceId,
                MediaFolders.folderFor(download.mediaType),
            ),
            DownloadIdentity.scopedFileName(requested, download.contentKey),
        ) ?: return null
        val moved = downloadDao.updateFilePathForProfile(
            download.id,
            download.profileId,
            replacement.stored,
            System.currentTimeMillis(),
        )
        return replacement.takeIf { moved > 0 }
    }

    private suspend fun deleteFileIfUnreferenced(filePath: String?) {
        val path = filePath?.takeIf { it.isNotBlank() } ?: return
        if (downloadDao.countForFilePath(path) == 0) MediaTarget.of(context, path)?.delete()
    }

    /**
     * Resolve the row again and bind every user action to the profile that is active now. A stale
     * Compose callback from the previous profile is therefore harmless, even when it still holds a
     * perfectly valid row id.
     */
    private suspend fun owned(requested: DownloadEntity): DownloadEntity? {
        val activeProfileId = settings.activeProfileId.first()
        if (activeProfileId < 0L || requested.profileId != activeProfileId) return null
        return downloadDao.getByIdForProfile(requested.id, activeProfileId)
    }

    private fun kick() {
        engine.markQueued()
        scope.launch { DownloadWorker.kick(context, settings.downloadsWifiOnlyNow()) }
    }

    private companion object {
        const val TAG = "DownloadManager"
    }
}
