package tv.own.owntv.core.download.secure

import java.io.File
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedDownloadDeletionJournalTest {
    @Test
    fun `failed physical deletion stays revoked and is retried after restart`() = withTempDirectory { directory ->
        val fileId = UUID.randomUUID()
        val ciphertext = File(directory, "$fileId.zpd")
        val blockedTarget = File(directory, "$fileId.zpd")
        assertTrue(blockedTarget.mkdir())
        assertTrue(File(blockedTarget, "held-open").apply { writeText("busy") }.isFile)

        val firstProcess = ProtectedDownloadDeletionJournal(directory) { ciphertext }
        assertFalse(firstProcess.delete(fileId))
        assertTrue(firstProcess.isTombstoned(fileId))
        assertTrue(blockedTarget.exists())

        // A fresh journal has no in-memory state; it recovers the marker and retries cleanup.
        assertTrue(File(blockedTarget, "held-open").delete())
        val afterRestart = ProtectedDownloadDeletionJournal(directory) { ciphertext }
        afterRestart.retryPendingDeletes()
        assertTrue(afterRestart.isTombstoned(fileId))
        assertFalse(blockedTarget.exists())
    }

    @Test
    fun `completed deletion keeps tombstone so an old uri cannot become readable again`() =
        withTempDirectory { directory ->
            val fileId = UUID.randomUUID()
            val ciphertext = File(directory, "$fileId.zpd").apply { writeText("ciphertext") }
            val journal = ProtectedDownloadDeletionJournal(directory) { ciphertext }

            assertTrue(journal.delete(fileId))
            assertFalse(ciphertext.exists())
            assertTrue(journal.isTombstoned(fileId))

            val afterRestart = ProtectedDownloadDeletionJournal(directory) { ciphertext }
            assertTrue(afterRestart.isTombstoned(fileId))
            assertTrue(afterRestart.delete(fileId))
        }

    @Test
    fun `revocation reports failure when tombstone storage cannot be written`() = withTempDirectory { parent ->
        val fileId = UUID.randomUUID()
        val blockedDirectory = File(parent, "not-a-directory").apply { writeText("blocked") }
        val ciphertext = File(parent, "$fileId.zpd").apply { writeText("ciphertext") }
        val journal = ProtectedDownloadDeletionJournal(blockedDirectory) { ciphertext }

        assertFalse(journal.revoke(fileId))
        assertFalse(journal.delete(fileId))
        assertFalse(journal.isTombstoned(fileId))
        assertTrue(ciphertext.exists())
    }

    private inline fun withTempDirectory(block: (File) -> Unit) {
        val directory = kotlin.io.path.createTempDirectory("protected-download-delete-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
