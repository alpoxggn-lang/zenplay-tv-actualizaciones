package tv.own.owntv.core.download

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Durable retry list for plaintext files left behind by the protected-download migration.
 *
 * The pending path is persisted *before* Room adopts the encrypted container. Both the committed
 * and staging snapshots are read on startup, so a process death on either side of the rename can
 * only cause an extra harmless delete attempt; it cannot lose the cleanup obligation. This journal
 * never contains protected-container URIs and therefore can never delete encrypted downloads.
 */
internal class LegacyPlaintextCleanupJournal(directory: File) {
    private val journal = File(directory.apply { mkdirs() }, JOURNAL_NAME)
    private val staging = File(directory, STAGING_NAME)

    @Synchronized
    fun entries(): Set<String> = buildSet {
        addAll(readSnapshot(journal))
        addAll(readSnapshot(staging))
    }

    @Synchronized
    fun add(path: String) {
        require(path.isNotBlank())
        persist(entries() + path)
    }

    @Synchronized
    fun remove(path: String) {
        persist(entries() - path)
    }

    @Synchronized
    fun replace(paths: Set<String>) {
        persist(paths.filterTo(linkedSetOf()) { it.isNotBlank() })
    }

    private fun persist(paths: Set<String>) {
        if (paths.isEmpty()) {
            runCatching { journal.delete() }
            runCatching { staging.delete() }
            return
        }

        writeSnapshot(staging, paths.sorted())
        // If either operation fails, staging remains a complete durable snapshot and entries()
        // will merge it on the next startup. Never trade recoverability for tidiness here.
        if (journal.exists() && !journal.delete()) return
        runCatching { staging.renameTo(journal) }
    }

    private fun writeSnapshot(file: File, paths: List<String>) {
        FileOutputStream(file, false).use { output ->
            DataOutputStream(BufferedOutputStream(output)).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeInt(paths.size)
                paths.forEach { path ->
                    val bytes = path.toByteArray(Charsets.UTF_8)
                    if (bytes.size > MAX_PATH_BYTES) throw IOException("Legacy cleanup path is too long")
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
                data.flush()
                output.fd.sync()
            }
        }
    }

    private fun readSnapshot(file: File): Set<String> = runCatching {
        if (!file.isFile) return@runCatching emptySet()
        DataInputStream(BufferedInputStream(file.inputStream())).use { data ->
            if (data.readInt() != MAGIC || data.readInt() != VERSION) return@use emptySet()
            val count = data.readInt()
            if (count !in 0..MAX_ENTRIES) throw IOException("Invalid legacy cleanup entry count")
            buildSet {
                repeat(count) {
                    val length = data.readInt()
                    if (length !in 1..MAX_PATH_BYTES) throw IOException("Invalid legacy cleanup path length")
                    val bytes = ByteArray(length)
                    data.readFully(bytes)
                    add(String(bytes, Charsets.UTF_8))
                }
            }
        }
    }.getOrDefault(emptySet())

    private companion object {
        const val JOURNAL_NAME = "legacy-plaintext-cleanup.bin"
        const val STAGING_NAME = "legacy-plaintext-cleanup.pending"
        const val MAGIC = 0x5a504c43 // ZPLC
        const val VERSION = 1
        const val MAX_ENTRIES = 10_000
        const val MAX_PATH_BYTES = 1024 * 1024
    }
}
