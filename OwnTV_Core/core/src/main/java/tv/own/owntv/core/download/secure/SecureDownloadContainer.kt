package tv.own.owntv.core.download.secure

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/** Logical owner bound into every authenticated record of a protected download. */
data class SecureDownloadOwner(
    val profileId: Long,
    val sourceId: Long,
    /** Lower-case SHA-256 of the stable provider content key. The provider URL/credentials are never stored. */
    val contentKeySha256: String,
) {
    init {
        require(profileId >= 0L)
        require(sourceId >= 0L)
        require(contentKeySha256.length == SHA256_HEX_LENGTH && contentKeySha256.all(::isLowerHex))
    }

    internal fun digestBytes(): ByteArray = contentKeySha256.hexToBytes()

    companion object {
        private const val SHA256_HEX_LENGTH = 64

        fun fromContentKey(profileId: Long, sourceId: Long, contentKey: String): SecureDownloadOwner {
            require(contentKey.isNotBlank())
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(contentKey.toByteArray(StandardCharsets.UTF_8))
                .toHex()
            return SecureDownloadOwner(profileId, sourceId, digest)
        }

        private fun isLowerHex(value: Char): Boolean = value in '0'..'9' || value in 'a'..'f'
    }
}

/** Immutable facts carried by a protected-download header. */
data class SecureDownloadDescriptor(
    val fileId: UUID,
    val owner: SecureDownloadOwner,
    val keyId: String,
    val chunkSize: Int,
)

/** A content key sealed by a non-exportable key (Android Keystore in production). */
data class WrappedDownloadKey(val nonce: ByteArray, val ciphertext: ByteArray)

/** Envelope-key boundary kept Android-free so the container can be tested on the JVM. */
interface DownloadKeyCipher {
    fun wrap(keyId: String, plaintextKey: ByteArray, aad: ByteArray): WrappedDownloadKey
    fun unwrap(keyId: String, wrapped: WrappedDownloadKey, aad: ByteArray): ByteArray
    fun delete(keyId: String)
}

/**
 * Chunk-authenticated container used by ZenPlay protected downloads.
 *
 * Plaintext never touches a temporary file. A random AES-256 data key encrypts each chunk with an
 * independent random 96-bit nonce and a 128-bit GCM tag; [DownloadKeyCipher] seals that data key.
 * Full chunks have a fixed physical stride, so a reader can decrypt any logical byte range without
 * reading everything before it. The authenticated footer is the commit marker: a file without a
 * valid footer is partial and cannot be played.
 */
object SecureDownloadContainer {
    const val DEFAULT_CHUNK_SIZE: Int = 256 * 1024

    private const val MIN_CHUNK_SIZE = 4 * 1024
    private const val MAX_CHUNK_SIZE = 4 * 1024 * 1024
    private const val DATA_KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BYTES = 16
    private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
    private const val HEADER_BYTES = 256
    private const val FOOTER_BYTES = 64
    private const val RECORD_PREFIX_BYTES = 4 + 8 + 4 + NONCE_BYTES
    private const val MAX_KEY_ID_BYTES = 64
    private const val MAX_WRAPPED_KEY_BYTES = 64
    private const val VERSION = 1
    private const val FLAGS = 0
    private const val RECORD_MAGIC = 0x5A504331 // ZPC1
    private val HEADER_MAGIC = "ZPSEC001".toByteArray(StandardCharsets.US_ASCII)
    private val FOOTER_MAGIC = "ZPEND001".toByteArray(StandardCharsets.US_ASCII)
    private val CHUNK_AAD_LABEL = "chunk".toByteArray(StandardCharsets.US_ASCII)
    private val FOOTER_AAD_LABEL = "footer".toByteArray(StandardCharsets.US_ASCII)

    fun create(
        file: File,
        descriptor: SecureDownloadDescriptor,
        keyCipher: DownloadKeyCipher,
        random: SecureRandom = SecureRandom(),
    ): SecureDownloadWriter {
        validateDescriptor(descriptor)
        file.parentFile?.let { parent ->
            if ((!parent.isDirectory && !parent.mkdirs()) || !parent.isDirectory) {
                throw IOException("Protected download directory is unavailable")
            }
        }
        val dataKey = ByteArray(DATA_KEY_BYTES).also(random::nextBytes)
        val aad = headerAad(descriptor)
        val wrapped = try {
            keyCipher.wrap(descriptor.keyId, dataKey, aad)
        } catch (failure: Throwable) {
            dataKey.fill(0)
            throw failure
        }
        if (wrapped.nonce.size != NONCE_BYTES ||
            wrapped.ciphertext.size !in (GCM_TAG_BYTES + 1)..MAX_WRAPPED_KEY_BYTES
        ) {
            dataKey.fill(0)
            throw IOException("Invalid wrapped content key")
        }
        val raf = try {
            RandomAccessFile(file, "rw")
        } catch (failure: Throwable) {
            dataKey.fill(0)
            throw failure
        }
        return try {
            raf.setLength(0L)
            writeHeader(raf, descriptor, wrapped)
            SecureDownloadWriter(raf, descriptor, dataKey, random, startChunk = 0L)
        } catch (failure: Throwable) {
            dataKey.fill(0)
            runCatching { raf.close() }
            runCatching { file.delete() }
            throw failure
        }
    }

    /**
     * Continue a partial container from its last fully committed chunk. An interrupted record (or
     * footer) is discarded; its nonce is never reused because the replacement chunk gets a fresh one.
     */
    fun resume(
        file: File,
        expectedOwner: SecureDownloadOwner,
        keyCipher: DownloadKeyCipher,
        random: SecureRandom = SecureRandom(),
    ): SecureDownloadWriter {
        val raf = RandomAccessFile(file, "rw")
        var dataKey: ByteArray? = null
        try {
            val header = readHeader(raf)
            requireOwner(header.descriptor.owner, expectedOwner)
            dataKey = unwrapDataKey(header, keyCipher)
            if (hasFooterMagic(raf)) throw IOException("Protected download is already complete")

            val stride = fullRecordBytes(header.descriptor.chunkSize)
            val bodyBytes = (raf.length() - HEADER_BYTES).coerceAtLeast(0L)
            val completeChunks = bodyBytes / stride
            var index = 0L
            while (index < completeChunks) {
                // A complete stride is not necessarily an intact record. Authenticate every
                // retained chunk before the writer adopts it, otherwise corrupted ciphertext
                // could be carried through resume and committed under a valid footer.
                val plaintext = readChunk(
                    raf,
                    header.descriptor,
                    dataKey,
                    index,
                    header.descriptor.chunkSize,
                )
                plaintext.fill(0)
                index++
            }
            val cleanLength = Math.addExact(HEADER_BYTES.toLong(), Math.multiplyExact(completeChunks, stride))
            raf.setLength(cleanLength)
            raf.seek(cleanLength)
            return SecureDownloadWriter(raf, header.descriptor, dataKey, random, completeChunks).also {
                dataKey = null // writer owns and wipes it
            }
        } catch (failure: Throwable) {
            dataKey?.fill(0)
            runCatching { raf.close() }
            throw failure
        }
    }

    fun open(
        file: File,
        keyCipher: DownloadKeyCipher,
        expectedOwner: SecureDownloadOwner? = null,
    ): SecureDownloadReader {
        val raf = RandomAccessFile(file, "r")
        var dataKey: ByteArray? = null
        try {
            val header = readHeader(raf)
            expectedOwner?.let { requireOwner(header.descriptor.owner, it) }
            dataKey = unwrapDataKey(header, keyCipher)
            val footer = readAndVerifyFooter(raf, header.descriptor, dataKey)
            validatePhysicalLength(raf, header.descriptor.chunkSize, footer)
            return SecureDownloadReader(
                raf = raf,
                descriptor = header.descriptor,
                dataKey = dataKey,
                plaintextLength = footer.plaintextLength,
                chunkCount = footer.chunkCount,
            ).also { dataKey = null }
        } catch (failure: Throwable) {
            dataKey?.fill(0)
            runCatching { raf.close() }
            throw failure
        }
    }

    /** Header-only inspection for lifecycle cleanup; it does not create or unwrap a key. */
    fun inspect(file: File): SecureDownloadDescriptor = RandomAccessFile(file, "r").use { readHeader(it).descriptor }

    internal fun encryptChunk(
        dataKey: ByteArray,
        descriptor: SecureDownloadDescriptor,
        index: Long,
        plaintext: ByteArray,
        length: Int,
        nonce: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(chunkAad(descriptor, index, length))
        return cipher.doFinal(plaintext, 0, length)
    }

    internal fun decryptChunk(
        dataKey: ByteArray,
        descriptor: SecureDownloadDescriptor,
        index: Long,
        plainLength: Int,
        nonce: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(chunkAad(descriptor, index, plainLength))
        return cipher.doFinal(ciphertext)
    }

    internal fun recordOffset(index: Long, chunkSize: Int): Long = Math.addExact(
        HEADER_BYTES.toLong(),
        Math.multiplyExact(index, fullRecordBytes(chunkSize)),
    )

    internal fun recordPrefixBytes(): Int = RECORD_PREFIX_BYTES
    internal fun tagBytes(): Int = GCM_TAG_BYTES

    private data class Header(val descriptor: SecureDownloadDescriptor, val wrappedKey: WrappedDownloadKey)
    private data class Footer(val chunkCount: Long, val plaintextLength: Long)
    private data class RecordPrefix(val index: Long, val plainLength: Int, val nonce: ByteArray)

    private fun unwrapDataKey(header: Header, keyCipher: DownloadKeyCipher): ByteArray {
        val key = keyCipher.unwrap(
            header.descriptor.keyId,
            header.wrappedKey,
            headerAad(header.descriptor),
        )
        if (key.size != DATA_KEY_BYTES) {
            key.fill(0)
            throw IOException("Invalid protected data key")
        }
        return key
    }

    private fun validateDescriptor(descriptor: SecureDownloadDescriptor) {
        require(descriptor.chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE)
        val keyId = descriptor.keyId.toByteArray(StandardCharsets.UTF_8)
        require(keyId.isNotEmpty() && keyId.size <= MAX_KEY_ID_BYTES)
    }

    private fun writeHeader(raf: RandomAccessFile, descriptor: SecureDownloadDescriptor, wrapped: WrappedDownloadKey) {
        val keyId = descriptor.keyId.toByteArray(StandardCharsets.UTF_8)
        val bytes = ByteArrayOutputStream(HEADER_BYTES)
        DataOutputStream(bytes).use { out ->
            out.write(HEADER_MAGIC)
            out.writeInt(VERSION)
            out.writeInt(HEADER_BYTES)
            out.writeInt(descriptor.chunkSize)
            out.writeInt(FLAGS)
            out.writeLong(descriptor.fileId.mostSignificantBits)
            out.writeLong(descriptor.fileId.leastSignificantBits)
            out.writeLong(descriptor.owner.profileId)
            out.writeLong(descriptor.owner.sourceId)
            out.write(descriptor.owner.digestBytes())
            out.writeInt(keyId.size)
            out.write(keyId)
            out.write(ByteArray(MAX_KEY_ID_BYTES - keyId.size))
            out.writeInt(wrapped.nonce.size)
            out.write(wrapped.nonce)
            out.write(ByteArray(NONCE_BYTES - wrapped.nonce.size))
            out.writeInt(wrapped.ciphertext.size)
            out.write(wrapped.ciphertext)
            out.write(ByteArray(MAX_WRAPPED_KEY_BYTES - wrapped.ciphertext.size))
            val remaining = HEADER_BYTES - bytes.size()
            if (remaining < 0) throw IOException("Protected download header overflow")
            out.write(ByteArray(remaining))
        }
        val header = bytes.toByteArray()
        if (header.size != HEADER_BYTES) throw IOException("Invalid protected download header size")
        raf.seek(0L)
        raf.write(header)
    }

    private fun readHeader(raf: RandomAccessFile): Header {
        if (raf.length() < HEADER_BYTES) throw EOFException("Protected download header is incomplete")
        raf.seek(0L)
        val bytes = ByteArray(HEADER_BYTES).also(raf::readFully)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(HEADER_MAGIC.size).also(input::readFully)
        if (!magic.contentEquals(HEADER_MAGIC)) throw IOException("Invalid protected download magic")
        if (input.readInt() != VERSION || input.readInt() != HEADER_BYTES) {
            throw IOException("Unsupported protected download version")
        }
        val chunkSize = input.readInt()
        if (chunkSize !in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) throw IOException("Invalid protected chunk size")
        if (input.readInt() != FLAGS) throw IOException("Unsupported protected download flags")
        val fileId = UUID(input.readLong(), input.readLong())
        val profileId = input.readLong()
        val sourceId = input.readLong()
        val digest = ByteArray(32).also(input::readFully).toHex()
        val keyLength = input.readInt()
        if (keyLength !in 1..MAX_KEY_ID_BYTES) throw IOException("Invalid protected key id")
        val keyArea = ByteArray(MAX_KEY_ID_BYTES).also(input::readFully)
        val keyId = String(keyArea, 0, keyLength, StandardCharsets.UTF_8)
        val nonceLength = input.readInt()
        if (nonceLength != NONCE_BYTES) throw IOException("Invalid wrapped-key nonce")
        val nonceArea = ByteArray(NONCE_BYTES).also(input::readFully)
        val wrappedLength = input.readInt()
        if (wrappedLength !in (GCM_TAG_BYTES + 1)..MAX_WRAPPED_KEY_BYTES) {
            throw IOException("Invalid wrapped content key")
        }
        val wrappedArea = ByteArray(MAX_WRAPPED_KEY_BYTES).also(input::readFully)
        val descriptor = runCatching {
            SecureDownloadDescriptor(
                fileId = fileId,
                owner = SecureDownloadOwner(profileId, sourceId, digest),
                keyId = keyId,
                chunkSize = chunkSize,
            ).also(::validateDescriptor)
        }.getOrElse { throw IOException("Invalid protected download owner", it) }
        return Header(
            descriptor,
            WrappedDownloadKey(nonceArea, wrappedArea.copyOf(wrappedLength)),
        )
    }

    private fun writeFooter(
        raf: RandomAccessFile,
        descriptor: SecureDownloadDescriptor,
        dataKey: ByteArray,
        chunkCount: Long,
        plaintextLength: Long,
        random: SecureRandom,
    ) {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(footerAad(descriptor, chunkCount, plaintextLength))
        val tag = cipher.doFinal()
        if (tag.size != GCM_TAG_BYTES) throw IOException("Invalid protected footer tag")
        val footer = ByteArrayOutputStream(FOOTER_BYTES)
        DataOutputStream(footer).use { out ->
            out.write(FOOTER_MAGIC)
            out.writeInt(VERSION)
            out.writeLong(chunkCount)
            out.writeLong(plaintextLength)
            out.write(nonce)
            out.write(tag)
            out.write(ByteArray(FOOTER_BYTES - footer.size()))
        }
        val bytes = footer.toByteArray()
        if (bytes.size != FOOTER_BYTES) throw IOException("Invalid protected footer size")
        raf.write(bytes)
    }

    private fun readAndVerifyFooter(
        raf: RandomAccessFile,
        descriptor: SecureDownloadDescriptor,
        dataKey: ByteArray,
    ): Footer {
        if (raf.length() < HEADER_BYTES + FOOTER_BYTES) throw EOFException("Protected download is incomplete")
        raf.seek(raf.length() - FOOTER_BYTES)
        val bytes = ByteArray(FOOTER_BYTES).also(raf::readFully)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(FOOTER_MAGIC.size).also(input::readFully)
        if (!magic.contentEquals(FOOTER_MAGIC)) throw IOException("Protected download has no commit footer")
        if (input.readInt() != VERSION) throw IOException("Unsupported protected footer version")
        val chunkCount = input.readLong()
        val plaintextLength = input.readLong()
        if (chunkCount < 0L || plaintextLength < 0L) throw IOException("Invalid protected footer lengths")
        val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
        val tag = ByteArray(GCM_TAG_BYTES).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(footerAad(descriptor, chunkCount, plaintextLength))
        cipher.doFinal(tag)
        return Footer(chunkCount, plaintextLength)
    }

    private fun validatePhysicalLength(raf: RandomAccessFile, chunkSize: Int, footer: Footer) {
        val expectedChunks = if (footer.plaintextLength == 0L) 0L
        else Math.addExact((footer.plaintextLength - 1L) / chunkSize, 1L)
        if (expectedChunks != footer.chunkCount) throw IOException("Protected footer chunk count mismatch")
        val fullBeforeLast = (footer.chunkCount - 1L).coerceAtLeast(0L)
        val lastLength = if (footer.chunkCount == 0L) 0 else Math.toIntExact(
            footer.plaintextLength - Math.multiplyExact(fullBeforeLast, chunkSize.toLong()),
        )
        if (footer.chunkCount > 0L && lastLength !in 1..chunkSize) {
            throw IOException("Invalid final protected chunk length")
        }
        var expected = Math.addExact(
            HEADER_BYTES.toLong(),
            Math.multiplyExact(fullBeforeLast, fullRecordBytes(chunkSize)),
        )
        if (footer.chunkCount > 0L) {
            expected = Math.addExact(expected, (RECORD_PREFIX_BYTES + lastLength + GCM_TAG_BYTES).toLong())
        }
        expected = Math.addExact(expected, FOOTER_BYTES.toLong())
        if (raf.length() != expected) throw IOException("Protected download physical length mismatch")
    }

    private fun readRecordPrefix(raf: RandomAccessFile, offset: Long): RecordPrefix {
        raf.seek(offset)
        if (raf.readInt() != RECORD_MAGIC) throw IOException("Invalid protected chunk magic")
        val index = raf.readLong()
        val plainLength = raf.readInt()
        val nonce = ByteArray(NONCE_BYTES).also(raf::readFully)
        return RecordPrefix(index, plainLength, nonce)
    }

    private fun hasFooterMagic(raf: RandomAccessFile): Boolean {
        if (raf.length() < HEADER_BYTES + FOOTER_BYTES) return false
        raf.seek(raf.length() - FOOTER_BYTES)
        return ByteArray(FOOTER_MAGIC.size).also(raf::readFully).contentEquals(FOOTER_MAGIC)
    }

    private fun requireOwner(actual: SecureDownloadOwner, expected: SecureDownloadOwner) {
        if (actual != expected) throw SecurityException("Protected download owner mismatch")
    }

    private fun headerAad(descriptor: SecureDownloadDescriptor): ByteArray {
        val keyId = descriptor.keyId.toByteArray(StandardCharsets.UTF_8)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(HEADER_MAGIC)
                out.writeInt(VERSION)
                out.writeInt(HEADER_BYTES)
                out.writeInt(descriptor.chunkSize)
                out.writeInt(FLAGS)
                out.writeLong(descriptor.fileId.mostSignificantBits)
                out.writeLong(descriptor.fileId.leastSignificantBits)
                out.writeLong(descriptor.owner.profileId)
                out.writeLong(descriptor.owner.sourceId)
                out.write(descriptor.owner.digestBytes())
                out.writeInt(keyId.size)
                out.write(keyId)
            }
        }.toByteArray()
    }

    private fun chunkAad(
        descriptor: SecureDownloadDescriptor,
        index: Long,
        plainLength: Int,
    ): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.write(headerAad(descriptor))
            out.write(CHUNK_AAD_LABEL)
            out.writeLong(index)
            out.writeInt(plainLength)
        }
    }.toByteArray()

    private fun footerAad(
        descriptor: SecureDownloadDescriptor,
        chunkCount: Long,
        plaintextLength: Long,
    ): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.write(headerAad(descriptor))
            out.write(FOOTER_AAD_LABEL)
            out.writeLong(chunkCount)
            out.writeLong(plaintextLength)
        }
    }.toByteArray()

    private fun fullRecordBytes(chunkSize: Int): Long =
        (RECORD_PREFIX_BYTES + chunkSize + GCM_TAG_BYTES).toLong()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun writeRecordPrefix(
        raf: RandomAccessFile,
        index: Long,
        plainLength: Int,
        nonce: ByteArray,
    ) {
        raf.writeInt(RECORD_MAGIC)
        raf.writeLong(index)
        raf.writeInt(plainLength)
        raf.write(nonce)
    }

    internal fun appendChunk(
        raf: RandomAccessFile,
        descriptor: SecureDownloadDescriptor,
        dataKey: ByteArray,
        index: Long,
        plaintext: ByteArray,
        length: Int,
        random: SecureRandom,
    ) {
        if (length !in 1..descriptor.chunkSize) throw IOException("Invalid protected chunk length")
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val encrypted = encryptChunk(dataKey, descriptor, index, plaintext, length, nonce)
        if (encrypted.size != length + GCM_TAG_BYTES) throw IOException("Invalid protected chunk ciphertext")
        writeRecordPrefix(raf, index, length, nonce)
        raf.write(encrypted)
        encrypted.fill(0)
    }

    internal fun finish(
        raf: RandomAccessFile,
        descriptor: SecureDownloadDescriptor,
        dataKey: ByteArray,
        chunkCount: Long,
        plaintextLength: Long,
        random: SecureRandom,
    ) = writeFooter(raf, descriptor, dataKey, chunkCount, plaintextLength, random)

    internal fun readChunk(
        raf: RandomAccessFile,
        descriptor: SecureDownloadDescriptor,
        dataKey: ByteArray,
        expectedIndex: Long,
        expectedLength: Int,
    ): ByteArray {
        val prefix = readRecordPrefix(raf, recordOffset(expectedIndex, descriptor.chunkSize))
        if (prefix.index != expectedIndex || prefix.plainLength != expectedLength) {
            throw IOException("Protected chunk index or length mismatch")
        }
        val encrypted = ByteArray(expectedLength + GCM_TAG_BYTES).also(raf::readFully)
        return try {
            decryptChunk(dataKey, descriptor, expectedIndex, expectedLength, prefix.nonce, encrypted)
        } finally {
            encrypted.fill(0)
        }
    }
}

/** Sequential encrypted writer. Call [finish] only after the upstream reached a valid EOF. */
class SecureDownloadWriter internal constructor(
    private val raf: RandomAccessFile,
    val descriptor: SecureDownloadDescriptor,
    private val dataKey: ByteArray,
    private val random: SecureRandom,
    startChunk: Long,
) : java.io.OutputStream(), Closeable {
    private val buffer = ByteArray(descriptor.chunkSize)
    private var buffered = 0
    private var chunkIndex = startChunk
    private var committedPlaintext = Math.multiplyExact(startChunk, descriptor.chunkSize.toLong())
    private var closed = false
    private var complete = false

    val committedPlaintextBytes: Long
        get() = committedPlaintext

    override fun write(value: Int) {
        val one = byteArrayOf(value.toByte())
        write(one, 0, 1)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkOpen()
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        var sourceOffset = offset
        var remaining = length
        while (remaining > 0) {
            val count = min(remaining, buffer.size - buffered)
            bytes.copyInto(buffer, buffered, sourceOffset, sourceOffset + count)
            buffered += count
            sourceOffset += count
            remaining -= count
            if (buffered == buffer.size) commitBuffered()
        }
    }

    /** Writes the last short chunk plus authenticated commit footer, then fsyncs and closes. */
    fun finish(): Long {
        checkOpen()
        if (buffered > 0) commitBuffered()
        val total = committedPlaintextBytes
        SecureDownloadContainer.finish(raf, descriptor, dataKey, chunkIndex, total, random)
        raf.fd.sync()
        complete = true
        close()
        return total
    }

    override fun close() {
        if (closed) return
        closed = true
        buffer.fill(0)
        dataKey.fill(0)
        runCatching { raf.close() }
    }

    private fun commitBuffered() {
        val plainLength = buffered
        SecureDownloadContainer.appendChunk(
            raf, descriptor, dataKey, chunkIndex, buffer, plainLength, random,
        )
        chunkIndex++
        committedPlaintext = Math.addExact(committedPlaintext, plainLength.toLong())
        buffer.fill(0, 0, plainLength)
        buffered = 0
    }

    private fun checkOpen() {
        check(!closed && !complete) { "Protected download writer is closed" }
    }
}

/** Seekable authenticated plaintext view over a completed container. */
class SecureDownloadReader internal constructor(
    private val raf: RandomAccessFile,
    val descriptor: SecureDownloadDescriptor,
    private val dataKey: ByteArray,
    val plaintextLength: Long,
    private val chunkCount: Long,
) : Closeable {
    private var closed = false
    private var cachedIndex = -1L
    private var cachedChunk: ByteArray? = null

    @Synchronized
    fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "Protected download reader is closed" }
        require(position >= 0L)
        require(offset >= 0 && length >= 0 && offset <= destination.size - length)
        if (length == 0) return 0
        if (position >= plaintextLength) return -1
        var logical = position
        var outputOffset = offset
        var remaining = min(length.toLong(), plaintextLength - position).toInt()
        val result = remaining
        while (remaining > 0) {
            val chunkIndex = logical / descriptor.chunkSize
            val inChunk = (logical % descriptor.chunkSize).toInt()
            val chunk = chunk(chunkIndex)
            val count = min(remaining, chunk.size - inChunk)
            if (count <= 0) throw EOFException("Protected chunk ended early")
            chunk.copyInto(destination, outputOffset, inChunk, inChunk + count)
            outputOffset += count
            remaining -= count
            logical += count
        }
        return result
    }

    fun openInputStream(): InputStream = object : InputStream() {
        private var position = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val count = readAt(position, bytes, offset, length)
            if (count > 0) position += count
            return count
        }

        override fun skip(count: Long): Long {
            if (count <= 0L) return 0L
            val moved = min(count, plaintextLength - position)
            position += moved
            return moved
        }

        override fun available(): Int = min(Int.MAX_VALUE.toLong(), plaintextLength - position).toInt()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cachedChunk?.fill(0)
        cachedChunk = null
        dataKey.fill(0)
        runCatching { raf.close() }
    }

    private fun chunk(index: Long): ByteArray {
        if (index == cachedIndex) return cachedChunk ?: throw IOException("Protected chunk cache is empty")
        if (index !in 0 until chunkCount) throw EOFException("Protected chunk is outside the file")
        val fullBeforeLast = (chunkCount - 1L).coerceAtLeast(0L)
        val expectedLength = if (index < fullBeforeLast) descriptor.chunkSize else Math.toIntExact(
            plaintextLength - Math.multiplyExact(fullBeforeLast, descriptor.chunkSize.toLong()),
        )
        val decrypted = SecureDownloadContainer.readChunk(
            raf, descriptor, dataKey, index, expectedLength,
        )
        cachedChunk?.fill(0)
        cachedIndex = index
        cachedChunk = decrypted
        return decrypted
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
}
