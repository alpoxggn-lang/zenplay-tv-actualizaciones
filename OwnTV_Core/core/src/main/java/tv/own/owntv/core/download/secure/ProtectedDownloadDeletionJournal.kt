package tv.own.owntv.core.download.secure

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Durable per-file revocation records. A record remains after deletion to prevent URI reuse. */
internal class ProtectedDownloadDeletionJournal(
    private val directory: File,
    private val target: (UUID) -> File,
) {
    private val lock = Any()

    fun isTombstoned(fileId: UUID): Boolean = tombstoneFile(fileId).isFile

    /** Persist revocation without requiring physical cleanup to succeed. */
    fun revoke(fileId: UUID): Boolean = synchronized(lock) { writeTombstone(fileId) }

    /**
     * The durable marker is committed before attempting physical deletion. False means the marker
     * could not be committed or the ciphertext still exists, so callers must keep retrying cleanup.
     */
    fun delete(fileId: UUID): Boolean = synchronized(lock) {
        if (!revoke(fileId)) return@synchronized false
        val ciphertext = target(fileId)
        if (!ciphertext.exists()) return@synchronized true
        runCatching { ciphertext.delete() }.getOrDefault(false) || !ciphertext.exists()
    }

    /** Re-attempt physical removal after process restart while retaining every revocation marker. */
    fun retryPendingDeletes() = synchronized(lock) {
        directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(TOMBSTONE_SUFFIX) }
            ?.mapNotNull { marker -> marker.name.removeSuffix(TOMBSTONE_SUFFIX).let { name ->
                runCatching { UUID.fromString(name) }.getOrNull()
            } }
            ?.forEach { fileId ->
                val ciphertext = target(fileId)
                if (ciphertext.exists()) runCatching { ciphertext.delete() }
            }
    }

    private fun writeTombstone(fileId: UUID): Boolean {
        val marker = tombstoneFile(fileId)
        if (marker.isFile) return true
        if ((!directory.isDirectory && !directory.mkdirs()) || !directory.isDirectory) return false
        val temporary = File(directory, ".$fileId.$TEMP_SUFFIX")
        return try {
            FileOutputStream(temporary).use { stream ->
                stream.write(MARKER_CONTENT)
                stream.fd.sync()
            }
            if (temporary.renameTo(marker)) true
            else marker.isFile
        } catch (_: Exception) {
            false
        } finally {
            runCatching { temporary.delete() }
        }
    }

    private fun tombstoneFile(fileId: UUID): File = File(directory, "$fileId$TOMBSTONE_SUFFIX")

    private companion object {
        const val TOMBSTONE_SUFFIX = ".zpd-deleted"
        const val TEMP_SUFFIX = "zpd-delete-tmp"
        val MARKER_CONTENT = "protected-download-revoked-v1".toByteArray(Charsets.US_ASCII)
    }
}
