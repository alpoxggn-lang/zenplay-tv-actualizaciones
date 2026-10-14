package tv.own.owntv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.util.Pin

class ProfileCustomizationAccessTest {
    @Test
    fun `kids customization requires the protected adult PIN`() {
        val adult = profile(1L, kids = false, pin = "2468")
        val child = profile(2L, kids = true)

        val requirement = profileCustomizationRequirement(listOf(adult, child), child.id, child.id)

        assertTrue(requirement is ProfileManagementRequirement.PinRequired)
        requirement as ProfileManagementRequirement.PinRequired
        assertEquals(adult.id, requirement.profile.id)
        assertEquals(ProfilePinPurpose.ADULT_ADMIN, requirement.purpose)
    }

    @Test
    fun `kids customization fails closed without a protected adult`() {
        val adult = profile(1L, kids = false)
        val child = profile(2L, kids = true)

        val requirement = profileCustomizationRequirement(listOf(adult, child), child.id, child.id)

        assertEquals(
            ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.NO_PROTECTED_ADULT),
            requirement,
        )
    }

    @Test
    fun `legacy adult may repair its PIN but cannot customize before doing so`() {
        val adult = profile(1L, kids = false)
        val child = profile(2L, kids = true)

        val requirement = profileCustomizationRequirement(listOf(adult, child), adult.id, adult.id)

        assertEquals(
            ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.NO_PROTECTED_ADULT),
            requirement,
        )
    }

    @Test
    fun `ordinary adult customization remains allowed`() {
        val adult = profile(1L, kids = false)

        assertEquals(
            ProfileManagementRequirement.Allowed,
            profileCustomizationRequirement(listOf(adult), adult.id, adult.id),
        )
    }

    @Test
    fun `stale page for a different active profile fails closed`() {
        val adult = profile(1L, kids = false, pin = "2468")
        val child = profile(2L, kids = true)

        assertEquals(
            ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.INVALID_TARGET),
            profileCustomizationRequirement(listOf(adult, child), adult.id, child.id),
        )
    }

    private fun profile(id: Long, kids: Boolean, pin: String? = null) = ProfileEntity(
        id = id,
        name = "profile-$id",
        avatarColor = 0,
        isKids = kids,
        pinHash = pin?.let(Pin::hash),
    )
}
