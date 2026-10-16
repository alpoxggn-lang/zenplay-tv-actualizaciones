package tv.own.owntv.core.download

import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.download.secure.ProtectedDownloadStore
import tv.own.owntv.core.download.secure.SecureDownloadOwner
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.storage.MediaTarget
import tv.own.owntv.core.settings.SettingsRepository
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Free/total bytes of the private volume backing protected downloads. */
data class DownloadStorageInfo(
    val freeBytes: Long,
    val totalBytes: Long,
    /** Kept for UI compatibility; protected downloads always use private storage. */
    val usingFallback: Boolean = false,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedFraction: Float get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Downloads movies and series episodes for offline playback into ZenPlay's private encrypted store.
 * Legacy plaintext files are migrated with a verified copy-before-swap hand-off.
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
    private val protectedStore: ProtectedDownloadStore,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Serializes physical-target creation and row/file mutations, including owner cleanup. */
    private val mutationMutex = Mutex()
    private val legacyCleanup = LegacyPlaintextCleanupJournal(
        File(context.noBackupFilesDir, LEGACY_MIGRATION_DIRECTORY),
    )

    init {
        scope.launch {
            retryLegacyPlaintextCleanup()
            // v2.2.9 and older stored raw media in user-visible/shared locations. Completed files
            // are copied into a fresh authenticated container before Room is switched over; an
            // interrupted/failed copy therefore leaves the old row and media untouched. Partial
            // rows get a fresh protected target but their old bytes are deliberately not deleted.
            migrateLegacyPlaintextDownloads()
            retryLegacyPlaintextCleanup()
            // Anything left QUEUED — or RUNNING when the process was killed — is picked up again.
            engine.markQueued()
            DownloadWorker.kick(context, settings.downloadsWifiOnlyNow())
        }
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

    /** Free/total space of ZenPlay's private encrypted download area. */
    suspend fun storageInfo(): DownloadStorageInfo = withContext(Dispatchers.IO) {
        val space = StatFs(context.noBackupFilesDir.absolutePath)
        DownloadStorageInfo(
            freeBytes = space.availableBytes,
            totalBytes = space.totalBytes,
            usingFallback = false,
        )
    }

    /** Queue a movie or episode into a fresh app-private authenticated container. */
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
                if (mediaType != MediaType.MOVIE && mediaType != MediaType.EPISODE) {
                    return@withLock false
                }
                val owner = SecureDownloadOwner.fromContentKey(profileId, sourceId, contentKey)
                val handle = runCatching { protectedStore.create(owner) }
                    .getOrElse {
                        android.util.Log.w(TAG, "cannot create protected download target for $fileName", it)
                        return@withLock false
                    }
                // Enqueue only needs the authenticated header. The worker reopens it and owns the
                // writer for the duration of the actual transfer.
                handle.writer.close()
                val rowId = downloadDao.insertIfAbsent(
                    DownloadEntity(
                        profileId = profileId, sourceId = sourceId, contentKey = contentKey,
                        mediaType = mediaType, itemId = itemId, title = title,
                        posterUrl = posterUrl, streamUrl = streamUrl, filePath = handle.contentUri.toString(),
                        status = DownloadStatus.QUEUED,
                    ),
                )
                if (rowId == -1L) discardUnadoptedProtected(handle.fileId, "enqueue without row")
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
                try {
                    engine.suspendTransfer(owned.id)
                    // The worker may have advanced or replaced this row while suspendTransfer was
                    // joining it. Never truncate/delete the stale Compose snapshot's path.
                    val current = downloadDao.getByIdForProfile(owned.id, owned.profileId)
                        ?: return@withLock false
                    replaceProtectedTarget(current) ?: return@withLock false
                    downloadDao.updateProgressForProfile(
                        current.id,
                        current.profileId,
                        DownloadStatus.QUEUED,
                        0,
                        current.totalBytes,
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
                try {
                    engine.suspendTransfer(owned.id)
                    val d = downloadDao.getByIdForProfile(owned.id, owned.profileId) ?: return@withLock
                    val bytes = protectedBytes(d) ?: 0L
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
                val bytes = ensureProtectedTarget(d) ?: return@withLock false
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
                try {
                    engine.suspendTransfer(owned.id)
                    // Re-read after the active writer has released the row; its current path is the
                    // only path this delete is allowed to clean up.
                    val current = downloadDao.getByIdForProfile(owned.id, owned.profileId)
                        ?: return@withLock false
                    runCatching { stageLegacyCleanup(current.filePath) }.getOrElse {
                        android.util.Log.w(TAG, "cannot journal download deletion id=${current.id}", it)
                        return@withLock false
                    }
                    if (!revokeProtectedBeforeUnlink(current.filePath)) {
                        android.util.Log.w(TAG, "cannot persist protected download revocation id=${current.id}")
                        return@withLock false
                    }
                    val removed = downloadDao.deleteByIdForProfile(current.id, current.profileId) > 0
                    if (removed) deleteFileIfUnreferenced(current.filePath)
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
            removeRows(downloadDao.getAllForProfile(profileId), beforeDelete = {
                // Keystore aliases are destroyed before Room/ciphertext so an interrupted cleanup
                // can leave only unreadable bytes, never playable media.
                protectedStore.destroyProfile(profileId)
            }) {
                downloadDao.deleteAllForProfile(profileId)
            }
        }
        kick()
    }

    /**
     * Replace a source's authorization identity without leaving the source permanently tombstoned.
     *
     * The source gate is raised before waiting for [mutationMutex], closing the enqueue race. Existing
     * transfers are stopped, their keys and rows are destroyed, and [updateSource] runs while queue
     * mutations are still excluded. The gate is lowered only after [sourceStillExists] confirms that
     * the source survived the update. A concurrent real deletion remains permanently revoked because
     * [DownloadEngine.releaseSource] only releases the temporary rotation hold.
     */
    suspend fun rotateSourceIdentity(
        sourceId: Long,
        sourceStillExists: suspend () -> Boolean,
        updateSource: suspend () -> Unit,
    ) {
        if (sourceId < 0L) {
            updateSource()
            return
        }
        engine.holdSourceForRotation(sourceId)
        try {
            mutationMutex.withLock {
                removeRows(downloadDao.getAllForSource(sourceId), beforeDelete = {
                    protectedStore.destroySource(sourceId)
                }) {
                    downloadDao.deleteAllForSource(sourceId)
                }
                updateSource()
            }
        } finally {
            val exists = try {
                sourceStillExists()
            } catch (_: Exception) {
                false
            }
            if (exists) {
                engine.releaseSource(sourceId)
                kick()
            }
        }
    }

    /** Cancel and remove every download from a playlist before its catalogue/credentials disappear. */
    suspend fun removeForSource(sourceId: Long) {
        if (sourceId < 0L) return
        engine.revokeSource(sourceId)
        mutationMutex.withLock {
            removeRows(downloadDao.getAllForSource(sourceId), beforeDelete = {
                protectedStore.destroySource(sourceId)
            }) {
                downloadDao.deleteAllForSource(sourceId)
            }
        }
        kick()
    }

    /**
     * A provider-confirmed expiry/revocation destroys this source's protected media but uses a
     * reversible gate. A later confirmed renewal may therefore download again; source deletion's
     * permanent tombstone remains a separate operation in [removeForSource].
     */
    suspend fun revokeEntitlementForSource(sourceId: Long) {
        if (sourceId < 0L) return
        engine.revokeSourceEntitlement(sourceId)
        mutationMutex.withLock {
            removeRows(downloadDao.getAllForSource(sourceId), beforeDelete = {
                protectedStore.destroySource(sourceId)
            }) {
                downloadDao.deleteAllForSource(sourceId)
            }
        }
        kick()
    }

    /** Remove only the reversible entitlement gate after a provider-confirmed renewal. */
    fun restoreEntitlementForSource(sourceId: Long) {
        if (sourceId < 0L) return
        engine.restoreSourceEntitlement(sourceId)
        kick()
    }

    /** Logout/account replacement: revoke every key before application data is cleared. */
    suspend fun removeAllProtected() {
        mutationMutex.withLock {
            val rows = downloadDao.getAll()
            removeRows(rows, beforeDelete = { protectedStore.destroyAll() }) {
                downloadDao.deleteAll()
            }
        }
    }

    private suspend fun removeRows(
        rows: List<DownloadEntity>,
        beforeDelete: suspend () -> Unit = {},
        deleteRows: suspend () -> Unit,
    ) {
        val attempted = ArrayList<Long>(rows.size)
        try {
            rows.forEach { row ->
                // Record before the suspending call: cancellation while cancelAndJoin is waiting
                // must still release this suppression token in finally.
                attempted += row.id
                engine.suspendTransfer(row.id)
            }
            val currentRows = rows.mapNotNull { row ->
                downloadDao.getByIdForProfile(row.id, row.profileId)
            }
            currentRows.mapNotNull { it.filePath }.distinct().forEach(::stageLegacyCleanup)
            beforeDelete()
            deleteRows()
            currentRows.mapNotNull { it.filePath }.distinct().forEach { deleteFileIfUnreferenced(it) }
        } finally {
            attempted.forEach(engine::release)
        }
    }

    private fun ownerOf(download: DownloadEntity): SecureDownloadOwner =
        SecureDownloadOwner.fromContentKey(download.profileId, download.sourceId, download.contentKey)

    private fun protectedFileId(stored: String?): UUID? = stored
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?.let(protectedStore::fileId)

    /** Plaintext bytes authenticated in complete chunks, or null when the target is absent/invalid. */
    private fun protectedBytes(download: DownloadEntity): Long? {
        val fileId = protectedFileId(download.filePath) ?: return null
        if (!protectedStore.exists(fileId)) return null
        val owner = runCatching { ownerOf(download) }.getOrNull() ?: return null
        return runCatching { protectedStore.open(fileId, owner).use { it.plaintextLength } }
            .getOrElse {
                runCatching {
                    protectedStore.resume(fileId, owner).writer.use { writer ->
                        writer.committedPlaintextBytes
                    }
                }.getOrNull()
            }
    }

    private suspend fun ensureProtectedTarget(download: DownloadEntity): Long? =
        protectedBytes(download) ?: replaceProtectedTarget(download)

    /** Fresh key/file rather than truncation, so AES-GCM nonces can never be reused on retry. */
    private suspend fun replaceProtectedTarget(download: DownloadEntity): Long? {
        val owner = runCatching { ownerOf(download) }.getOrNull() ?: return null
        val oldPath = download.filePath
        val handle = runCatching { protectedStore.create(owner) }.getOrNull() ?: return null
        handle.writer.close()
        // Persist the cleanup obligation before the Room pointer changes. A process death after
        // the update can therefore be recovered on the next startup.
        val moved = try {
            stageLegacyCleanup(oldPath)
            if (!revokeProtectedBeforeUnlink(oldPath)) {
                throw IOException("Cannot persist old protected download revocation")
            }
            downloadDao.updateFilePathForProfile(
                download.id,
                download.profileId,
                handle.contentUri.toString(),
                System.currentTimeMillis(),
            )
        } catch (failure: Exception) {
            discardUnadoptedProtected(handle.fileId, "replace target after failure")
            android.util.Log.w(TAG, "cannot replace protected download id=${download.id}", failure)
            return null
        }
        if (moved == 0) {
            discardUnadoptedProtected(handle.fileId, "replace target without row")
            return null
        }
        deleteFileIfUnreferenced(oldPath)
        return 0L
    }

    /**
     * Upgrade plaintext downloads without making an application update destructive.
     *
     * A completed legacy file remains the source of truth until a protected copy has been fully
     * committed, reopened and length-checked. The Room pointer then changes inside a
     * [NonCancellable] hand-off, and only an unreferenced old path is removed afterwards. For an
     * incomplete row we never import unauthenticated partial bytes: it receives an empty protected
     * target and restarts from zero (or remains paused/failed), while the old physical file is left
     * alone. If even that target cannot be created, the row is failed so no queue can resume the
     * plaintext destination. [DownloadEngine] also rejects every non-protected target as a second
     * fail-closed boundary.
     */
    private suspend fun migrateLegacyPlaintextDownloads() = mutationMutex.withLock {
        val legacy = downloadDao.getAll().filter { protectedFileId(it.filePath) == null }
        legacy.forEach { snapshot ->
            val rowId = snapshot.id
            try {
                engine.suspendTransfer(rowId)
                // Re-read after the transfer has released the row. It may have completed, been
                // deleted, or already been migrated by a previous initialization attempt.
                val current = downloadDao.getByIdForProfile(rowId, snapshot.profileId)
                    ?: return@forEach
                if (protectedFileId(current.filePath) != null) return@forEach
                // Rows without a valid owner cannot safely be rebound to an authenticated file:
                // the legacy item id may have been reused by another source. Keep their plaintext
                // bytes and row untouched and non-runnable until ownership can be established by
                // an explicit repair path.
                if (current.profileId < 0L || current.sourceId < 0L || current.contentKey.isBlank()) {
                    deferUnownedLegacyMigration(current)
                    return@forEach
                }
                val row = current
                if (row.status == DownloadStatus.COMPLETED) {
                    migrateCompletedLegacyDownload(row)
                } else {
                    migrateIncompleteLegacyDownload(row)
                }
            } finally {
                engine.release(rowId)
            }
        }
    }

    /**
     * Copy one completed plaintext file into protected storage and atomically adopt it.
     * Returns false without changing the row or source file when any validation/copy step fails.
     */
    private suspend fun migrateCompletedLegacyDownload(download: DownloadEntity): Boolean {
        val oldPath = download.filePath?.takeIf { it.isNotBlank() } ?: return false
        // Opening the stream is the readability check. Some SAF providers incorrectly report
        // exists=false for a still-readable persisted document, so do not trust metadata here.
        val source = MediaTarget.of(context, oldPath) ?: return false
        val owner = runCatching { ownerOf(download) }.getOrNull() ?: return false
        val handle = runCatching { protectedStore.create(owner) }
            .getOrElse {
                android.util.Log.w(TAG, "cannot create protected target for legacy download id=${download.id}", it)
                return false
            }
        var adopted = false
        try {
            val reportedLengthBefore = source.length().takeIf { it > 0L }
            val copied = handle.writer.use { writer ->
                source.openInput().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    val digest = MessageDigest.getInstance(SHA_256)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        writer.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        total = Math.addExact(total, count.toLong())
                    }
                    val committed = writer.finish()
                    if (committed != total) throw IOException("Protected migration length mismatch")
                    MigrationFingerprint(total, digest.digest())
                }
            }
            if (reportedLengthBefore != null && copied.bytes != reportedLengthBefore) {
                throw IOException("Legacy source was truncated while migrating")
            }
            val verified = protectedStore.open(handle.fileId, owner).use { reader ->
                val fingerprint = fingerprint(reader.openInputStream())
                if (fingerprint.bytes != reader.plaintextLength) {
                    throw IOException("Protected migration did not decrypt completely")
                }
                fingerprint
            }
            if (verified.bytes != copied.bytes || !MessageDigest.isEqual(verified.sha256, copied.sha256)) {
                throw IOException("Protected migration hash verification failed")
            }

            // When the provider exposes a meaningful length, re-read the source once after the
            // encrypted verification. This catches same-size rewrites as well as truncation while
            // the first pass was in progress; a one-time legacy migration favors integrity over
            // speed. Providers that cannot report a size still get source-vs-container SHA-256.
            if (reportedLengthBefore != null) {
                val sourceAfter = source.openInput().use { fingerprint(it) }
                val reportedLengthAfter = source.length()
                if (reportedLengthAfter != reportedLengthBefore ||
                    sourceAfter.bytes != copied.bytes ||
                    !MessageDigest.isEqual(sourceAfter.sha256, copied.sha256)
                ) {
                    throw IOException("Legacy source changed while migrating")
                }
            }

            // Cancellation must not land between the durable Room switch and ownership of the new
            // container. Once this block begins, either the row adopts the verified file or the new
            // file remains unreferenced and is removed by finally.
            withContext(NonCancellable) {
                // Journal first, then switch Room. If the process dies anywhere after this point,
                // startup can finish plaintext cleanup without ever touching the ciphertext.
                stageLegacyCleanup(oldPath)
                val moved = downloadDao.updateFilePathForProfile(
                    download.id,
                    download.profileId,
                    handle.contentUri.toString(),
                    System.currentTimeMillis(),
                )
                if (moved == 0) return@withContext
                adopted = true
                // Shared legacy paths are deleted only after the last Room reference moved.
                deleteFileIfUnreferenced(oldPath)
            }
            return adopted
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w(TAG, "cannot migrate legacy download id=${download.id}", failure)
            return false
        } finally {
            if (!adopted) discardUnadoptedProtected(handle.fileId, "failed legacy migration")
        }
    }

    /** Invalid legacy ownership is recoverable metadata debt, never permission to remove media. */
    private suspend fun deferUnownedLegacyMigration(download: DownloadEntity) {
        android.util.Log.w(TAG, "legacy download id=${download.id} awaits ownership repair")
        // Even a formerly COMPLETED legacy row must not be presented as a playable protected
        // download when its ownership cannot be authenticated. Preserve the original file path and
        // bytes so a later explicit recovery can still inspect the legacy item.
        quarantineLegacyPartial(download)
    }

    private suspend fun fingerprint(input: InputStream): MigrationFingerprint {
        val digest = MessageDigest.getInstance(SHA_256)
        val buffer = ByteArray(MIGRATION_BUFFER_BYTES)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
            total = Math.addExact(total, count.toLong())
        }
        return MigrationFingerprint(total, digest.digest())
    }

    private data class MigrationFingerprint(val bytes: Long, val sha256: ByteArray)

    /**
     * Detach a partial legacy row from plaintext without consuming or deleting those bytes.
     * QUEUED/RUNNING rows restart securely; a user's PAUSED/FAILED choice remains unchanged.
     */
    private suspend fun migrateIncompleteLegacyDownload(download: DownloadEntity): Boolean {
        val owner = runCatching { ownerOf(download) }.getOrNull()
        val handle = owner?.let { runCatching { protectedStore.create(it) }.getOrNull() }
        if (handle == null) {
            quarantineLegacyPartial(download)
            return false
        }
        handle.writer.close()
        var adopted = false
        try {
            withContext(NonCancellable) {
                val moved = downloadDao.updateFilePathForProfile(
                    download.id,
                    download.profileId,
                    handle.contentUri.toString(),
                    System.currentTimeMillis(),
                )
                if (moved == 0) return@withContext
                adopted = true
                val status = when (download.status) {
                    DownloadStatus.PAUSED -> DownloadStatus.PAUSED
                    DownloadStatus.FAILED -> DownloadStatus.FAILED
                    else -> DownloadStatus.QUEUED
                }
                downloadDao.updateProgressForProfile(
                    download.id,
                    download.profileId,
                    status,
                    0L,
                    download.totalBytes,
                    System.currentTimeMillis(),
                )
                // Do not call deleteFileIfUnreferenced(oldPath): update migrations are explicitly
                // non-destructive, even for an incomplete plaintext file.
            }
            return adopted
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (!adopted) withContext(NonCancellable) { quarantineLegacyPartial(download) }
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w(TAG, "cannot detach legacy partial download id=${download.id}", failure)
            if (!adopted) quarantineLegacyPartial(download)
            return false
        } finally {
            if (!adopted) discardUnadoptedProtected(handle.fileId, "failed incomplete migration")
        }
    }

    /** Keep a legacy partial row visible but outside the runnable queue. */
    private suspend fun quarantineLegacyPartial(download: DownloadEntity) {
        downloadDao.updateProgressForProfile(
            download.id,
            download.profileId,
            DownloadStatus.FAILED,
            download.downloadedBytes,
            download.totalBytes,
            System.currentTimeMillis(),
        )
    }

    /** Persist only plaintext paths. Protected content is deliberately outside this mechanism. */
    private fun stageLegacyCleanup(filePath: String?) {
        val path = filePath?.takeIf { it.isNotBlank() } ?: return
        if (protectedFileId(path) == null) legacyCleanup.add(path)
    }

    /** Retry journaled cleanup at startup while retaining every still-referenced or failed path. */
    private suspend fun retryLegacyPlaintextCleanup() = mutationMutex.withLock {
        val remaining = linkedSetOf<String>()
        legacyCleanup.entries().forEach { path ->
            // A corrupt/old journal entry must never be allowed to address protected ciphertext.
            if (protectedFileId(path) != null) return@forEach
            if (downloadDao.countForFilePath(path) != 0 || !deleteLegacyPlaintext(path)) {
                remaining += path
            }
        }
        legacyCleanup.replace(remaining)
    }

    private fun deleteLegacyPlaintext(path: String): Boolean {
        if (protectedFileId(path) != null) return false
        val target = MediaTarget.of(context, path) ?: return false
        if (target.delete()) return true
        // File.delete() returns false when the path is already absent; that obligation is complete.
        // SAF providers are less reliable, so a failed document delete remains journaled.
        return target is MediaTarget.Path && !target.file.exists()
    }

    private suspend fun deleteFileIfUnreferenced(filePath: String?): Boolean {
        val path = filePath?.takeIf { it.isNotBlank() } ?: return true
        if (downloadDao.countForFilePath(path) != 0) return false
        protectedFileId(path)?.let {
            // A durable tombstone makes the URI unreadable immediately, but a failed physical
            // delete remains pending and must not be treated as completed cleanup.
            return protectedStore.delete(it)
        }
        // Security migration cleanup only; new downloads never write plaintext MediaTargets.
        val deleted = deleteLegacyPlaintext(path)
        if (deleted) legacyCleanup.remove(path) else legacyCleanup.add(path)
        return deleted
    }

    /** A protected URI must be durably revoked before its last Room reference is removed. */
    private suspend fun revokeProtectedBeforeUnlink(filePath: String?): Boolean {
        val path = filePath?.takeIf { it.isNotBlank() } ?: return true
        val fileId = protectedFileId(path) ?: return true
        return downloadDao.countForFilePath(path) > 1 || protectedStore.revoke(fileId)
    }

    /** Unadopted files have no Room owner; revocation failure is still surfaced for diagnostics. */
    private fun discardUnadoptedProtected(fileId: UUID, reason: String) {
        if (!protectedStore.delete(fileId)) {
            android.util.Log.w(TAG, "protected download cleanup pending after $reason fileId=$fileId")
        }
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
        const val SHA_256 = "SHA-256"
        const val MIGRATION_BUFFER_BYTES = 128 * 1024
        const val LEGACY_MIGRATION_DIRECTORY = "download-migration"
    }
}
