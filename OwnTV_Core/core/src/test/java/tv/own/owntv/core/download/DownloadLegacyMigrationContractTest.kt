package tv.own.owntv.core.download

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Upgrade regression guards: installing a new APK must never discard legacy offline media. */
class DownloadLegacyMigrationContractTest {
    private val manager = File(
        "src/main/java/tv/own/owntv/core/download/DownloadManager.kt",
    ).readText()

    @Test
    fun `completed plaintext is committed verified and adopted before its old path can be deleted`() {
        val migration = manager
            .substringAfter("private suspend fun migrateCompletedLegacyDownload(")
            .substringBefore("private suspend fun migrateIncompleteLegacyDownload(")

        val read = migration.indexOf("source.openInput()")
        val finish = migration.indexOf("writer.finish()")
        val reopen = migration.indexOf("protectedStore.open(handle.fileId, owner)")
        val decryptAll = migration.indexOf("reader.openInputStream()")
        val compareHash = migration.indexOf("MessageDigest.isEqual(verified.sha256, copied.sha256)")
        val journal = migration.indexOf("stageLegacyCleanup(oldPath)")
        val adopt = migration.indexOf("downloadDao.updateFilePathForProfile(")
        val deleteOld = migration.indexOf("deleteFileIfUnreferenced(oldPath)")

        assertTrue(read >= 0)
        assertTrue(finish > read)
        assertTrue(reopen > finish)
        assertTrue(decryptAll > reopen)
        assertTrue(compareHash > decryptAll)
        assertTrue(journal > compareHash)
        assertTrue(adopt > journal)
        assertTrue(deleteOld > adopt)
        assertTrue(migration.contains("digest.update(buffer, 0, count)"))
        assertTrue(migration.contains("fingerprint.bytes != reader.plaintextLength"))
        assertTrue(migration.contains("reportedLengthBefore"))
        assertTrue(migration.contains("sourceAfter.sha256"))
        assertTrue(migration.contains("withContext(NonCancellable)"))
    }

    @Test
    fun `failed or cancelled completed migration keeps the row and plaintext source`() {
        val migration = manager
            .substringAfter("private suspend fun migrateCompletedLegacyDownload(")
            .substringBefore("private suspend fun migrateIncompleteLegacyDownload(")

        assertFalse(migration.contains("downloadDao.deleteByIdForProfile"))
        assertTrue(migration.contains("if (!adopted) discardUnadoptedProtected(handle.fileId"))
        assertTrue(migration.contains("catch (cancelled: kotlinx.coroutines.CancellationException)"))
        assertFalse(manager.contains("purgeLegacyPlaintextDownloads"))
    }

    @Test
    fun `incomplete plaintext is detached without deleting its physical file`() {
        val migration = manager
            .substringAfter("private suspend fun migrateIncompleteLegacyDownload(")
            .substringBefore("private suspend fun quarantineLegacyPartial(")

        assertTrue(migration.contains("protectedStore.create(it)"))
        assertTrue(migration.contains("downloadDao.updateFilePathForProfile("))
        assertTrue(migration.contains("DownloadStatus.QUEUED"))
        assertTrue(migration.contains("quarantineLegacyPartial(download)"))
        assertFalse(
            migration.lineSequence().any {
                it.trimStart().startsWith("deleteFileIfUnreferenced(")
            },
        )
        assertFalse(migration.contains("MediaTarget.of(context, download.filePath)?.delete()"))
    }

    @Test
    fun `startup migration runs before queue activation`() {
        val init = manager.substringAfter("init {").substringBefore("// Turning \"Wi-Fi only\"")
        assertTrue(
            init.indexOf("migrateLegacyPlaintextDownloads()") < init.indexOf("engine.markQueued()"),
        )
        assertTrue(init.indexOf("retryLegacyPlaintextCleanup()") < init.indexOf("engine.markQueued()"))
    }

    @Test
    fun `legacy row with negative source id is failed closed without rebinding or deleting plaintext`() {
        val migration = manager
            .substringAfter("private suspend fun migrateLegacyPlaintextDownloads()")
            .substringBefore("private suspend fun migrateCompletedLegacyDownload(")
        val deferred = manager
            .substringAfter("private suspend fun deferUnownedLegacyMigration(")
            .substringBefore("private suspend fun fingerprint(")

        assertTrue(migration.contains("current.sourceId < 0L"))
        assertTrue(migration.contains("deferUnownedLegacyMigration(current)"))
        assertFalse(migration.contains("repairLegacyOwner"))
        assertFalse(migration.contains("downloadDao.repairLegacyOwner"))
        assertTrue(deferred.contains("quarantineLegacyPartial(download)"))
        assertFalse(deferred.contains("deleteFileIfUnreferenced"))
        assertFalse(deferred.contains("MediaTarget"))
    }

    @Test
    fun `migration releases a suppressed transfer when suspension is cancelled`() {
        val migration = manager
            .substringAfter("private suspend fun migrateLegacyPlaintextDownloads()")
            .substringBefore("private suspend fun migrateCompletedLegacyDownload(")
        val tryStart = migration.indexOf("try {")
        val suspend = migration.indexOf("engine.suspendTransfer(rowId)")
        val finally = migration.indexOf("finally {")
        val release = migration.indexOf("engine.release(rowId)")

        assertTrue(tryStart >= 0)
        assertTrue(suspend > tryStart)
        assertTrue(finally > suspend)
        assertTrue(release > finally)
    }
}
