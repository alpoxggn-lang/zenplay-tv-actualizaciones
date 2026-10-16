package tv.own.owntv.core.download.secure

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** App-private storage and key lifecycle for protected mobile downloads. */
class ProtectedDownloadStore(
    context: Context,
    private val keyCipher: DownloadKeyCipher = AndroidKeystoreDownloadKeyCipher(),
) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.noBackupFilesDir, DIRECTORY).apply { mkdirs() }
    private val deletionJournal = ProtectedDownloadDeletionJournal(directory, ::file)

    init {
        deletionJournal.retryPendingDeletes()
    }

    data class WriteHandle(
        val fileId: UUID,
        val contentUri: Uri,
        val writer: SecureDownloadWriter,
    )

    fun create(
        owner: SecureDownloadOwner,
        chunkSize: Int = SecureDownloadContainer.DEFAULT_CHUNK_SIZE,
    ): WriteHandle {
        var fileId: UUID
        do {
            fileId = UUID.randomUUID()
        } while (deletionJournal.isTombstoned(fileId) || file(fileId).exists())
        val descriptor = SecureDownloadDescriptor(fileId, owner, keyId(owner), chunkSize)
        val writer = SecureDownloadContainer.create(file(fileId), descriptor, keyCipher)
        return WriteHandle(fileId, contentUri(fileId), writer)
    }

    fun resume(fileId: UUID, owner: SecureDownloadOwner): WriteHandle {
        requireReadable(fileId)
        requireFileIdentity(fileId, SecureDownloadContainer.inspect(file(fileId)))
        return WriteHandle(
            fileId = fileId,
            contentUri = contentUri(fileId),
            writer = SecureDownloadContainer.resume(file(fileId), owner, keyCipher),
        )
    }

    fun open(fileId: UUID, expectedOwner: SecureDownloadOwner? = null): SecureDownloadReader {
        requireReadable(fileId)
        val reader = SecureDownloadContainer.open(file(fileId), keyCipher, expectedOwner)
        return if (reader.descriptor.fileId == fileId) reader else {
            reader.close()
            throw SecurityException("Protected download file identity mismatch")
        }
    }

    fun inspect(fileId: UUID): SecureDownloadDescriptor {
        requireReadable(fileId)
        return SecureDownloadContainer.inspect(file(fileId)).also { requireFileIdentity(fileId, it) }
    }

    fun exists(fileId: UUID): Boolean = !deletionJournal.isTombstoned(fileId) && file(fileId).isFile

    /** Persist access revocation before changing the Room reference that protects this file. */
    fun revoke(fileId: UUID): Boolean = deletionJournal.revoke(fileId)

    /** Persist revocation, then attempt physical cleanup; startup retries if bytes remain. */
    fun delete(fileId: UUID): Boolean = deletionJournal.delete(fileId)

    /** Delete the non-exportable owner key first, then its now-unreadable ciphertext. */
    fun destroyOwner(profileId: Long, sourceId: Long) {
        val ownerFiles = descriptors().filter { (_, descriptor) ->
            descriptor.owner.profileId == profileId && descriptor.owner.sourceId == sourceId
        }
        revoke(ownerFiles)
        ownerFiles.map { it.second.keyId }.distinct().forEach(keyCipher::delete)
        deletionJournal.retryPendingDeletes()
    }

    fun destroyProfile(profileId: Long) = destroyMatching { it.owner.profileId == profileId }

    fun destroySource(sourceId: Long) = destroyMatching { it.owner.sourceId == sourceId }

    fun destroyAll() {
        val files = descriptors()
        revoke(files)
        if (keyCipher is AndroidKeystoreDownloadKeyCipher) keyCipher.deleteAll()
        else files.map { it.second.keyId }.distinct().forEach(keyCipher::delete)
        deletionJournal.retryPendingDeletes()
        // Preserve the previous destroyAll cleanup for malformed or otherwise uninspectable
        // containers; they have no usable key/header to retain and are not covered by the journal.
        directory.listFiles()
            ?.filter { it.isFile && it.extension == EXTENSION }
            ?.forEach { runCatching { it.delete() } }
    }

    fun contentUri(fileId: UUID): Uri = Uri.Builder()
        .scheme("content")
        .authority(authority(appContext))
        .appendPath(fileId.toString())
        .build()

    fun fileId(uri: Uri): UUID? {
        if (uri.scheme != "content" || uri.authority != authority(appContext)) return null
        if (uri.pathSegments.size != 1) return null
        return runCatching { UUID.fromString(uri.pathSegments.single()) }.getOrNull()
    }

    private fun destroyMatching(predicate: (SecureDownloadDescriptor) -> Boolean) {
        val matches = descriptors().filter { predicate(it.second) }
        revoke(matches)
        // Key deletion is first on purpose: interruption can leave only ciphertext, never playable media.
        matches.map { it.second.keyId }.distinct().forEach(keyCipher::delete)
        deletionJournal.retryPendingDeletes()
    }

    private fun requireReadable(fileId: UUID) {
        if (!exists(fileId)) throw java.io.FileNotFoundException("Protected download is unavailable")
    }

    private fun requireFileIdentity(fileId: UUID, descriptor: SecureDownloadDescriptor) {
        if (descriptor.fileId != fileId) throw SecurityException("Protected download file identity mismatch")
    }

    private fun revoke(files: List<Pair<File, SecureDownloadDescriptor>>) {
        files.forEach { (_, descriptor) -> deletionJournal.delete(descriptor.fileId) }
    }

    private fun descriptors(): List<Pair<File, SecureDownloadDescriptor>> =
        directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == EXTENSION }
            ?.mapNotNull { candidate ->
                runCatching { candidate to SecureDownloadContainer.inspect(candidate) }.getOrNull()
            }
            ?.toList()
            .orEmpty()

    private fun file(fileId: UUID): File = File(directory, "$fileId.$EXTENSION")

    private fun keyId(owner: SecureDownloadOwner): String {
        val material = "${owner.profileId}:${owner.sourceId}".toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(material)
        // 224 bits keeps the id below the header/Keystore alias bound while making collisions irrelevant.
        return "k1-" + hash.copyOf(28).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val DIRECTORY = "protected-downloads"
        private const val EXTENSION = "zpd"
        private const val AUTHORITY_SUFFIX = ".protected-downloads"
        fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX
    }
}
