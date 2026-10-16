package tv.own.owntv.core.download

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard for the cross-profile/source download ownership fixes added in v47. */
class DownloadOwnershipSafetyContractTest {
    private fun source(relative: String): String = File("src/main/java/$relative").readText()

    @Test
    fun `stable enqueue never replaces an active row and shared legacy files are reference counted`() {
        val dao = source("tv/own/owntv/core/database/dao/DownloadDao.kt")
        val manager = source("tv/own/owntv/core/download/DownloadManager.kt")

        assertTrue(dao.contains("@Insert(onConflict = OnConflictStrategy.IGNORE)"))
        assertFalse(dao.contains("@Insert(onConflict = OnConflictStrategy.REPLACE)"))
        assertTrue(manager.contains("downloadDao.getByIdentity(profileId, sourceId, contentKey)"))
        assertTrue(manager.contains("if (downloadDao.countForFilePath(path) != 0) return"))
        assertTrue(manager.contains("replaceProtectedTarget(current)"))
        assertTrue(manager.contains("ProtectedDownloadStore"))
    }

    @Test
    fun `overlapping workers serialize and cancellation reaches blocking network reads`() {
        val engine = source("tv/own/owntv/core/download/DownloadEngine.kt")

        assertTrue(engine.contains("drainMutex.withLock"))
        assertTrue(engine.contains("launch(start = CoroutineStart.LAZY)"))
        assertTrue(engine.contains("active[next.id] = job"))
        assertTrue(engine.contains("watchDownloadCancellation(call::cancel)"))
        assertTrue(engine.contains("protectedFileId(d.filePath)"))
        assertTrue(engine.contains("ownerOf(d)"))
    }

    @Test
    fun `transfer and owner deletion revalidate then revoke access`() {
        val engine = source("tv/own/owntv/core/download/DownloadEngine.kt")
        val sourceRepository = source("tv/own/owntv/core/repository/SourceRepository.kt")
        val profileManager = source("tv/own/owntv/core/profile/ProfileManager.kt")

        assertTrue(engine.contains("authorized(current) == null"))
        assertTrue(engine.contains("AUTHORIZATION_RECHECK_MS"))
        assertTrue(sourceRepository.contains("downloadManager.removeForSource(source.id)"))
        assertTrue(profileManager.contains("downloadManager.removeForProfile(stored.id)"))
    }

    @Test
    fun `bulk removal releases every attempted suppression even when suspension is cancelled`() {
        val manager = source("tv/own/owntv/core/download/DownloadManager.kt")
        val removal = manager
            .substringAfter("private suspend fun removeRows(")
            .substringBefore("private fun ownerOf(")

        val tryBlock = removal.indexOf("try {")
        val attempted = removal.indexOf("attempted += row.id")
        val suspendTransfer = removal.indexOf("engine.suspendTransfer(row.id)")
        val release = removal.indexOf("attempted.forEach(engine::release)")
        assertTrue(tryBlock >= 0)
        assertTrue(attempted > tryBlock)
        assertTrue(suspendTransfer > attempted)
        assertTrue(release > suspendTransfer)
    }

    @Test
    fun `retry and delete reread the current row after writer suspension`() {
        val manager = source("tv/own/owntv/core/download/DownloadManager.kt")
        val retry = manager.substringAfter("fun retry(").substringBefore("/** Stop the running download")
        val delete = manager.substringAfter("fun delete(").substringBefore("/** Cancel and remove every download")

        assertTrue(
            retry.indexOf("downloadDao.getByIdForProfile(owned.id, owned.profileId)") >
                retry.indexOf("engine.suspendTransfer(owned.id)"),
        )
        assertTrue(retry.indexOf("try {") < retry.indexOf("engine.suspendTransfer(owned.id)"))
        assertTrue(retry.indexOf("finally {") > retry.indexOf("engine.suspendTransfer(owned.id)"))
        assertTrue(retry.contains("replaceProtectedTarget(current)"))
        assertTrue(
            delete.indexOf("downloadDao.getByIdForProfile(owned.id, owned.profileId)") >
                delete.indexOf("engine.suspendTransfer(owned.id)"),
        )
        assertTrue(delete.indexOf("try {") < delete.indexOf("engine.suspendTransfer(owned.id)"))
        assertTrue(delete.indexOf("finally {") > delete.indexOf("engine.suspendTransfer(owned.id)"))
        assertTrue(delete.contains("deleteFileIfUnreferenced(current.filePath)"))
        assertTrue(
            delete.indexOf("revokeProtectedBeforeUnlink(current.filePath)") <
                delete.indexOf("downloadDao.deleteByIdForProfile(current.id, current.profileId)"),
        )
    }

    @Test
    fun `protected URI swaps persist old URI revocation before Room adopts the replacement`() {
        val manager = source("tv/own/owntv/core/download/DownloadManager.kt")
        val engine = source("tv/own/owntv/core/download/DownloadEngine.kt")
        val replace = manager.substringAfter("private suspend fun replaceProtectedTarget(")
            .substringBefore("/**\n     * Upgrade plaintext downloads")
        val rangeReset = engine.substringAfter("// The server ignored Range.")
            .substringBefore("downloadDao.updateProgress(id, DownloadStatus.RUNNING")

        assertTrue(
            replace.indexOf("revokeProtectedBeforeUnlink(oldPath)") <
                replace.indexOf("downloadDao.updateFilePathForProfile("),
        )
        assertTrue(
            rangeReset.indexOf("protectedStore.revoke(fileId)") <
                rangeReset.indexOf("downloadDao.updateFilePathForProfile("),
        )
    }

    @Test
    fun `source identity rotation cleans before update and only releases its temporary gate`() {
        val manager = source("tv/own/owntv/core/download/DownloadManager.kt")
        val repository = source("tv/own/owntv/core/repository/SourceRepository.kt")
        val rotation = manager
            .substringAfter("suspend fun rotateSourceIdentity(")
            .substringBefore("/** Cancel and remove every download from a playlist")
        val deletion = manager
            .substringAfter("suspend fun removeForSource(")
            .substringBefore("/** Logout/account replacement")

        assertTrue(rotation.contains("engine.holdSourceForRotation(sourceId)"))
        assertTrue(rotation.indexOf("protectedStore.destroySource(sourceId)") < rotation.lastIndexOf("updateSource()"))
        assertTrue(rotation.contains("engine.releaseSource(sourceId)"))
        assertTrue(deletion.contains("engine.revokeSource(sourceId)"))
        assertFalse(deletion.contains("engine.releaseSource(sourceId)"))

        val update = repository
            .substringAfter("suspend fun updateSource(source: SourceEntity, expectedCurrent: SourceEntity? = null)")
            .substringBefore("/**\n     * [onProgress]")
        assertTrue(update.contains("sourceUpdateMutex.withLock"))
        assertTrue(update.contains("stored.hasProtectedDownloadIdentityChange(expectedCurrent)"))
        assertTrue(update.contains("source.withProtectedDownloadIdentityFrom(stored)"))
        assertTrue(update.indexOf("sourceUpdateMutex.withLock") < update.indexOf("downloadManager.rotateSourceIdentity("))
        assertTrue(update.contains("downloadManager.rotateSourceIdentity("))
        assertTrue(update.indexOf("downloadManager.rotateSourceIdentity(") < update.indexOf("sourceDao.update(update)"))

        val identityMerge = repository
            .substringAfter("internal fun SourceEntity.withProtectedDownloadIdentityFrom(current: SourceEntity)")
            .substringBefore("private fun SyncResult.name()")
        listOf("type", "url", "username", "password", "userAgent", "httpReferer", "mac", "stalkerSerialNumber", "stalkerDeviceId", "stalkerDeviceId2", "stalkerSignature")
            .forEach { field -> assertTrue(identityMerge.contains("$field = current.$field")) }
    }

    @Test
    fun `backup source identity restore uses guarded repository outside database transaction`() {
        val backup = source("tv/own/owntv/core/backup/BackupManager.kt")
        val transactionStart = backup.indexOf("db.transaction {")
        val guardedApply = backup.indexOf("pendingSourceUpdates.forEach { (expected, updated) ->")

        assertTrue(transactionStart >= 0)
        assertTrue(guardedApply > transactionStart)
        assertTrue(backup.substring(transactionStart, guardedApply).contains("pendingSourceUpdates += existing to existing.copy("))
        assertTrue(backup.substring(guardedApply).contains("sourceRepository.updateSource(updated, expectedCurrent = expected)"))
        assertFalse(backup.contains("sourceDao.update("))
        assertTrue(backup.contains("private val sourceRepository: SourceRepository"))
    }

    @Test
    fun `sync discovered epg updates cannot overwrite a stale source identity snapshot`() {
        val dao = source("tv/own/owntv/core/database/dao/SourceDao.kt")
        val m3u = source("tv/own/owntv/core/sync/M3uSyncer.kt")
        val stalker = source("tv/own/owntv/core/sync/StalkerSyncer.kt")

        assertTrue(dao.contains("suspend fun adoptEpgUrlIfBlank(id: Long, epgUrl: String)"))
        assertTrue(dao.contains("epgUrl IS NULL OR TRIM(epgUrl) = ''"))
        assertTrue(m3u.contains("sourceDao.adoptEpgUrlIfBlank(s.id, header.urlTvg)"))
        assertTrue(stalker.contains("sourceDao.adoptEpgUrlIfBlank(s.id, url)"))
        assertFalse(m3u.contains("sourceDao.update("))
        assertFalse(stalker.contains("sourceDao.update("))
    }
}
