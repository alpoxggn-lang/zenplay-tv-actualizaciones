package tv.own.owntv.core.download.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM envelope key that never leaves Android Keystore. */
class AndroidKeystoreDownloadKeyCipher : DownloadKeyCipher {
    override fun wrap(keyId: String, plaintextKey: ByteArray, aad: ByteArray): WrappedDownloadKey {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(keyId, create = true))
        cipher.updateAAD(aad)
        return WrappedDownloadKey(cipher.iv.copyOf(), cipher.doFinal(plaintextKey))
    }

    override fun unwrap(keyId: String, wrapped: WrappedDownloadKey, aad: ByteArray): ByteArray {
        require(wrapped.nonce.size == GCM_NONCE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(keyId, create = false),
            GCMParameterSpec(GCM_TAG_BITS, wrapped.nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(wrapped.ciphertext)
    }

    override fun delete(keyId: String) {
        synchronized(KEYSTORE_LOCK) {
            val store = store()
            val alias = alias(keyId)
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        }
    }

    /** Crypto-erases every protected-download key owned by this app. */
    fun deleteAll() {
        synchronized(KEYSTORE_LOCK) {
            val store = store()
            val aliases = store.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (alias.startsWith(ALIAS_PREFIX)) store.deleteEntry(alias)
            }
        }
    }

    private fun key(keyId: String, create: Boolean): SecretKey = synchronized(KEYSTORE_LOCK) {
        require(keyId.isNotBlank() && keyId.length <= MAX_KEY_ID_CHARS)
        val store = store()
        val alias = alias(keyId)
        (store.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        if (!create) throw java.security.KeyStoreException("Protected download key is unavailable")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }

    private fun store(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private fun alias(keyId: String): String = ALIAS_PREFIX + keyId

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_PREFIX = "zenplay_protected_download_v1_"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_NONCE_BYTES = 12
        const val MAX_KEY_ID_CHARS = 64
        val KEYSTORE_LOCK = Any()
    }
}
