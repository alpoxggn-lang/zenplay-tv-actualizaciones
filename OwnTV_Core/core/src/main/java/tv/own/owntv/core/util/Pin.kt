package tv.own.owntv.core.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted, deliberately slow PIN hashing for profile locks.
 *
 * New values use PBKDF2-HMAC-SHA256. [verify] also accepts the old `salt:sha256(salt+pin)` format so
 * an app update never locks an existing profile; the next explicit PIN change upgrades its hash.
 */
object Pin {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6
    private const val PBKDF2_PREFIX = "pbkdf2-sha256"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val DERIVED_BITS = 256

    fun isValid(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all(Char::isDigit)

    fun hash(pin: String): String {
        require(isValid(pin)) { "PIN must contain 4 to 6 digits" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "${PBKDF2_PREFIX}\$${PBKDF2_ITERATIONS}\$${salt.toHex()}\$${derive(salt, pin, PBKDF2_ITERATIONS).toHex()}"
    }

    fun verify(pin: String, stored: String?): Boolean {
        if (stored.isNullOrBlank()) return true // no PIN set
        if (!isValid(pin)) return false
        if (stored.startsWith("${PBKDF2_PREFIX}\$")) {
            val parts = stored.split('$')
            if (parts.size != 4) return false
            val iterations = parts[1].toIntOrNull()?.takeIf { it in 10_000..1_000_000 } ?: return false
            val salt = parts[2].fromHex() ?: return false
            val expected = parts[3].fromHex() ?: return false
            val actual = derive(salt, pin, iterations)
            return MessageDigest.isEqual(actual, expected)
        }
        // Legacy v2.2.1 and earlier.
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val salt = parts[0].fromHex() ?: return false
        val expected = parts[1].fromHex() ?: return false
        return MessageDigest.isEqual(digest(salt, pin), expected)
    }

    private fun derive(salt: ByteArray, pin: String, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, DERIVED_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun digest(salt: ByteArray, pin: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray())

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun String.fromHex(): ByteArray? = takeIf { length % 2 == 0 }?.let { value -> runCatching {
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }.getOrNull() }
}
