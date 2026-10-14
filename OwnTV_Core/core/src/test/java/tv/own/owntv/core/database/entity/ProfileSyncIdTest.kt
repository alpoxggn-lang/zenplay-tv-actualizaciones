package tv.own.owntv.core.database.entity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSyncIdTest {
    @Test
    fun `new ids are canonical and unique`() {
        val first = ProfileSyncId.newId()
        val second = ProfileSyncId.newId()

        assertTrue(ProfileSyncId.isValid(first))
        assertTrue(ProfileSyncId.isValid(second))
        assertNotEquals(first, second)
    }

    @Test
    fun `invalid and non canonical ids are rejected`() {
        assertFalse(ProfileSyncId.isValid(""))
        assertFalse(ProfileSyncId.isValid("profile-1"))
        assertFalse(ProfileSyncId.isValid("550E8400-E29B-41D4-A716-446655440000"))
    }
}
