package tv.own.owntv.core.download

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPlaintextCleanupJournalTest {
    @Test
    fun `entries survive recreation and removal is durable`() {
        val directory = Files.createTempDirectory("legacy-cleanup-journal").toFile()
        try {
            LegacyPlaintextCleanupJournal(directory).apply {
                add("/old/movie-one.ts")
                add("content://legacy.provider/movie-two")
            }

            val reopened = LegacyPlaintextCleanupJournal(directory)
            assertEquals(
                setOf("/old/movie-one.ts", "content://legacy.provider/movie-two"),
                reopened.entries(),
            )
            reopened.remove("/old/movie-one.ts")
            assertEquals(
                setOf("content://legacy.provider/movie-two"),
                LegacyPlaintextCleanupJournal(directory).entries(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `staging snapshot recovers an interrupted replace`() {
        val directory = Files.createTempDirectory("legacy-cleanup-staging").toFile()
        try {
            val journal = LegacyPlaintextCleanupJournal(directory)
            journal.add("/old/recover-me.mkv")
            val committed = directory.resolve("legacy-plaintext-cleanup.bin")
            val staging = directory.resolve("legacy-plaintext-cleanup.pending")
            committed.copyTo(staging, overwrite = true)
            committed.writeBytes(byteArrayOf(0x00, 0x01))

            assertTrue("/old/recover-me.mkv" in LegacyPlaintextCleanupJournal(directory).entries())
        } finally {
            directory.deleteRecursively()
        }
    }
}
