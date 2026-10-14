package tv.own.owntv.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class PinTest {
    @Test
    fun `profile PIN is salted and never stored as plaintext`() {
        val first = Pin.hash("2468")
        val second = Pin.hash("2468")

        assertNotEquals("2468", first)
        assertNotEquals(first, second)
        assertTrue(first.startsWith("pbkdf2-sha256$"))
        assertTrue(Pin.verify("2468", first))
        assertFalse(Pin.verify("1357", first))
    }

    @Test
    fun `legacy salted sha256 profile PIN remains valid after upgrade`() {
        val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(salt + "2468".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val legacy = salt.joinToString("") { "%02x".format(it) } + ":" + digest

        assertTrue(Pin.verify("2468", legacy))
        assertFalse(Pin.verify("1357", legacy))
    }

    @Test
    fun `only four to six numeric digits are accepted`() {
        assertTrue(Pin.isValid("1234"))
        assertTrue(Pin.isValid("123456"))
        assertFalse(Pin.isValid("123"))
        assertFalse(Pin.isValid("1234567"))
        assertFalse(Pin.isValid("12a4"))
    }

    @Test
    fun `invalid stored hashes fail closed`() {
        assertFalse(Pin.verify("2468", "2468"))
        assertFalse(Pin.verify("2468", "not:a:hash"))
    }
}
