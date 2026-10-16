package tv.own.owntv.core.download.secure

import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureDownloadContainerTest {
    private val owner = SecureDownloadOwner.fromContentKey(7L, 11L, "movie:provider:42")

    @Test
    fun `round trip and random reads cross authenticated chunk boundaries`() = withTempFile { file ->
        val keys = MemoryKeyCipher()
        val chunkSize = 4 * 1024
        val input = Random(91).nextBytes(chunkSize * 3 + 177)
        val descriptor = SecureDownloadDescriptor(UUID.randomUUID(), owner, "test-key", chunkSize)

        SecureDownloadContainer.create(file, descriptor, keys, seededRandom()).use { writer ->
            writer.write(input, 0, 37)
            writer.write(input, 37, input.size - 37)
            assertEquals((input.size / chunkSize * chunkSize).toLong(), writer.committedPlaintextBytes)
            assertEquals(input.size.toLong(), writer.finish())
        }

        SecureDownloadContainer.open(file, keys, owner).use { reader ->
            assertEquals(input.size.toLong(), reader.plaintextLength)
            val all = ByteArray(input.size)
            assertEquals(input.size, reader.readAt(0, all, 0, all.size))
            assertArrayEquals(input, all)

            val position = chunkSize - 19L
            val slice = ByteArray(113)
            assertEquals(slice.size, reader.readAt(position, slice, 0, slice.size))
            assertArrayEquals(input.copyOfRange(position.toInt(), position.toInt() + slice.size), slice)

            val stream = reader.openInputStream()
            assertEquals(input.size - 64L, stream.skip(input.size - 64L))
            assertArrayEquals(input.takeLast(64).toByteArray(), stream.readBytes())
        }
    }

    @Test
    fun `empty and exact-chunk files have authenticated commit footers`() {
        listOf(0, 4 * 1024, 8 * 1024).forEach { size ->
            withTempFile { file ->
                val keys = MemoryKeyCipher()
                val descriptor = SecureDownloadDescriptor(UUID.randomUUID(), owner, "key-$size", 4 * 1024)
                val input = Random(size).nextBytes(size)
                val writer = SecureDownloadContainer.create(file, descriptor, keys, seededRandom())
                writer.write(input)
                writer.finish()
                SecureDownloadContainer.open(file, keys, owner).use { reader ->
                    assertEquals(size.toLong(), reader.plaintextLength)
                    assertArrayEquals(input, reader.openInputStream().readBytes())
                }
            }
        }
    }

    @Test
    fun `interrupted final chunk is discarded and resumes at committed plaintext`() = withTempFile { file ->
        val keys = MemoryKeyCipher()
        val chunkSize = 4 * 1024
        val input = Random(13).nextBytes(chunkSize * 3 + 211)
        val descriptor = SecureDownloadDescriptor(UUID.randomUUID(), owner, "resume-key", chunkSize)

        SecureDownloadContainer.create(file, descriptor, keys, seededRandom()).use { writer ->
            writer.write(input, 0, chunkSize * 2 + 99)
            assertEquals((chunkSize * 2).toLong(), writer.committedPlaintextBytes)
            // close without finish simulates cancellation/process loss: the 99 plaintext bytes existed only in RAM.
        }

        SecureDownloadContainer.resume(file, owner, keys, seededRandom()).use { writer ->
            assertEquals((chunkSize * 2).toLong(), writer.committedPlaintextBytes)
            writer.write(input, chunkSize * 2, input.size - chunkSize * 2)
            writer.finish()
        }

        SecureDownloadContainer.open(file, keys, owner).use { reader ->
            assertArrayEquals(input, reader.openInputStream().readBytes())
        }
    }

    @Test
    fun `resume rejects full-size chunks with altered ciphertext or tag`() {
        val chunkSize = 4 * 1024
        val recordPrefixBytes = 4 + 8 + 4 + 12
        listOf(
            "ciphertext" to (256L + recordPrefixBytes + 37L),
            "tag" to (256L + recordPrefixBytes + chunkSize + 15L),
        ).forEach { (part, byteOffset) ->
            withTempFile { file ->
                val keys = MemoryKeyCipher()
                val descriptor = SecureDownloadDescriptor(UUID.randomUUID(), owner, "resume-tamper-$part", chunkSize)
                SecureDownloadContainer.create(file, descriptor, keys, seededRandom()).use { writer ->
                    writer.write(Random(28).nextBytes(chunkSize * 2))
                    // Closing without finish leaves two full committed chunks and no footer.
                }
                flipByte(file, byteOffset)
                val lengthBeforeResume = file.length()

                assertThrows(Exception::class.java) {
                    SecureDownloadContainer.resume(file, owner, keys, seededRandom())
                }
                // Fail closed before truncating or adopting any part of the damaged container.
                assertEquals(lengthBeforeResume, file.length())
            }
        }
    }

    @Test
    fun `resume rejects a full-size record with a malformed chunk index`() = withTempFile { file ->
        val chunkSize = 4 * 1024
        val recordPrefixBytes = 4 + 8 + 4 + 12
        val stride = recordPrefixBytes + chunkSize + 16
        val keys = MemoryKeyCipher()
        val descriptor = SecureDownloadDescriptor(UUID.randomUUID(), owner, "resume-index-tamper", chunkSize)
        SecureDownloadContainer.create(file, descriptor, keys, seededRandom()).use { writer ->
            writer.write(Random(29).nextBytes(chunkSize * 2))
        }
        // The second record's stored index starts after its 4-byte magic.
        flipByte(file, 256L + stride + 4L + 7L)
        val lengthBeforeResume = file.length()

        assertThrows(Exception::class.java) {
            SecureDownloadContainer.resume(file, owner, keys, seededRandom())
        }
        assertEquals(lengthBeforeResume, file.length())
    }

    @Test
    fun `chunk tampering fails authentication instead of returning plaintext`() = withTempFile { file ->
        val keys = MemoryKeyCipher()
        val descriptor = SecureDownloadDescriptor(UUID.randomUUID(), owner, "tamper-key", 4 * 1024)
        val input = Random(3).nextBytes(6 * 1024)
        SecureDownloadContainer.create(file, descriptor, keys, seededRandom()).use {
            it.write(input)
            it.finish()
        }
        java.io.RandomAccessFile(file, "rw").use { raf ->
            val ciphertextByte = 256L + 4 + 8 + 4 + 12 + 57
            raf.seek(ciphertextByte)
            val original = raf.readByte()
            raf.seek(ciphertextByte)
            raf.writeByte(original.toInt() xor 0x40)
        }
        SecureDownloadContainer.open(file, keys, owner).use { reader ->
            assertThrows(Exception::class.java) {
                reader.readAt(0, ByteArray(128), 0, 128)
            }
        }
    }

    @Test
    fun `partial container is never exposed as playable media`() = withTempFile { file ->
        val keys = MemoryKeyCipher()
        val descriptor = SecureDownloadDescriptor(UUID.randomUUID(), owner, "partial-key", 4 * 1024)
        SecureDownloadContainer.create(file, descriptor, keys, seededRandom()).use {
            it.write(Random(5).nextBytes(5 * 1024))
            // No finish: a cancelled transfer has authenticated full chunks but no commit footer.
        }
        assertThrows(Exception::class.java) {
            SecureDownloadContainer.open(file, keys, owner)
        }
    }

    @Test
    fun `footer tampering cannot forge a completed length`() = withTempFile { file ->
        val keys = MemoryKeyCipher()
        val descriptor = SecureDownloadDescriptor(UUID.randomUUID(), owner, "footer-key", 4 * 1024)
        SecureDownloadContainer.create(file, descriptor, keys, seededRandom()).use {
            it.write(Random(7).nextBytes(5 * 1024))
            it.finish()
        }
        java.io.RandomAccessFile(file, "rw").use { raf ->
            // Footer layout: magic(8), version(4), chunkCount(8), plaintextLength(8).
            raf.seek(raf.length() - 64 + 8 + 4 + 8)
            val original = raf.readByte()
            raf.seek(raf.filePointer - 1)
            raf.writeByte(original.toInt() xor 0x01)
        }
        assertThrows(Exception::class.java) {
            SecureDownloadContainer.open(file, keys, owner)
        }
    }

    @Test
    fun `wrong profile source or content identity is rejected before playback`() = withTempFile { file ->
        val keys = MemoryKeyCipher()
        val descriptor = SecureDownloadDescriptor(UUID.randomUUID(), owner, "owner-key", 4 * 1024)
        SecureDownloadContainer.create(file, descriptor, keys, seededRandom()).use {
            it.write(ByteArray(10))
            it.finish()
        }
        val wrong = SecureDownloadOwner.fromContentKey(owner.profileId + 1, owner.sourceId, "movie:provider:42")
        assertThrows(SecurityException::class.java) {
            SecureDownloadContainer.open(file, keys, wrong)
        }
    }

    @Test
    fun `deleting envelope key makes copied ciphertext unreadable`() = withTempFile { file ->
        val keys = MemoryKeyCipher()
        val descriptor = SecureDownloadDescriptor(UUID.randomUUID(), owner, "revoked-key", 4 * 1024)
        SecureDownloadContainer.create(file, descriptor, keys, seededRandom()).use {
            it.write(ByteArray(32) { 7 })
            it.finish()
        }
        keys.delete(descriptor.keyId)
        assertThrows(Exception::class.java) {
            SecureDownloadContainer.open(file, keys, owner)
        }
    }

    private fun seededRandom(): SecureRandom = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1729L) }

    private fun flipByte(file: File, byteOffset: Long) {
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.seek(byteOffset)
            val original = raf.readByte()
            raf.seek(byteOffset)
            raf.writeByte(original.toInt() xor 0x40)
        }
    }

    private inline fun withTempFile(block: (File) -> Unit) {
        val file = kotlin.io.path.createTempFile("zenplay-secure-", ".zpd").toFile()
        try {
            block(file)
        } finally {
            file.delete()
        }
    }

    private class MemoryKeyCipher : DownloadKeyCipher {
        private val keys = mutableMapOf<String, SecretKey>()

        override fun wrap(keyId: String, plaintextKey: ByteArray, aad: ByteArray): WrappedDownloadKey {
            val key = keys.getOrPut(keyId) {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            return WrappedDownloadKey(cipher.iv, cipher.doFinal(plaintextKey))
        }

        override fun unwrap(keyId: String, wrapped: WrappedDownloadKey, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                keys.getValue(keyId),
                GCMParameterSpec(128, wrapped.nonce),
            )
            cipher.updateAAD(aad)
            return cipher.doFinal(wrapped.ciphertext)
        }

        override fun delete(keyId: String) {
            keys.remove(keyId)
        }
    }
}
