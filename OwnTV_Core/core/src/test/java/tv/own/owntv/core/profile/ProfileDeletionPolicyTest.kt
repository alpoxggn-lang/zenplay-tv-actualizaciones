package tv.own.owntv.core.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.util.Pin

class ProfileDeletionPolicyTest {
    @Test
    fun `the last profile is protected`() {
        assertFalse(canDeleteProfile(profileCount = 1, profileId = 7L, activeProfileId = 9L))
    }

    @Test
    fun `the active profile is protected`() {
        assertFalse(canDeleteProfile(profileCount = 3, profileId = 7L, activeProfileId = 7L))
    }

    @Test
    fun `a non-active profile can be deleted when a survivor exists`() {
        assertTrue(canDeleteProfile(profileCount = 2, profileId = 7L, activeProfileId = 9L))
    }

    @Test
    fun `deletion waits until the active identity is known`() {
        assertFalse(canDeleteProfile(profileCount = 2, profileId = 7L, activeProfileId = null))
        assertFalse(canDeleteProfile(profileCount = 2, profileId = 7L, activeProfileId = -1L))
    }

    @Test
    fun `a different locked profile requires its PIN for management`() {
        assertTrue(profileManagementRequiresPin("salt:digest", profileId = 7L, activeProfileId = 9L))
        assertFalse(profileManagementRequiresPin(null, profileId = 7L, activeProfileId = 9L))
        assertFalse(profileManagementRequiresPin("salt:digest", profileId = 7L, activeProfileId = 7L))
    }

    @Test
    fun `kids profile needs a protected adult for every administration action`() {
        val adult = profile(1L, kids = false, pin = "2468")
        val child = profile(2L, kids = true)
        val otherAdult = profile(3L, kids = false, pin = "1357")

        listOf(
            ProfileManagementAction.CREATE to null,
            ProfileManagementAction.EDIT to child.id,
            ProfileManagementAction.DELETE to otherAdult.id,
        ).forEach { (action, target) ->
            val requirement = profileManagementRequirement(listOf(adult, child, otherAdult), child.id, action, target)
            assertTrue(requirement is ProfileManagementRequirement.PinRequired)
            requirement as ProfileManagementRequirement.PinRequired
            assertTrue(requirement.profile.id == adult.id)
            assertTrue(requirement.purpose == ProfilePinPurpose.ADULT_ADMIN)
        }
    }

    @Test
    fun `kids administration fails closed without a protected adult`() {
        val unlockedAdult = profile(1L, kids = false)
        val child = profile(2L, kids = true)
        val requirement = profileManagementRequirement(
            listOf(unlockedAdult, child),
            child.id,
            ProfileManagementAction.CREATE,
        )
        assertTrue(requirement is ProfileManagementRequirement.Blocked)
        assertTrue(
            (requirement as ProfileManagementRequirement.Blocked).reason ==
                ProfileManagementBlockReason.NO_PROTECTED_ADULT,
        )
    }

    @Test
    fun `kids profile can only switch to a protected adult with that adult PIN`() {
        val adult = profile(1L, kids = false, pin = "2468")
        val child = profile(2L, kids = true)
        val requirement = profileManagementRequirement(
            listOf(adult, child),
            child.id,
            ProfileManagementAction.SWITCH,
            adult.id,
        )
        assertTrue(requirement is ProfileManagementRequirement.PinRequired)
        assertFalse(profileAuthorizationSatisfies(requirement, ProfileAuthorization(adult.id, "0000")))
        assertTrue(profileAuthorizationSatisfies(requirement, ProfileAuthorization(adult.id, "2468")))
    }

    @Test
    fun `switching into the active protected profile still requires its PIN`() {
        val protectedAdult = profile(1L, kids = false, pin = "2468")
        val requirement = profileManagementRequirement(
            listOf(protectedAdult),
            protectedAdult.id,
            ProfileManagementAction.SWITCH,
            protectedAdult.id,
        )

        assertTrue(requirement is ProfileManagementRequirement.PinRequired)
        requirement as ProfileManagementRequirement.PinRequired
        assertTrue(requirement.profile.id == protectedAdult.id)
        assertTrue(requirement.purpose == ProfilePinPurpose.TARGET_PROFILE)
        assertFalse(profileAuthorizationSatisfies(requirement, ProfileAuthorization(protectedAdult.id, "0000")))
        assertTrue(profileAuthorizationSatisfies(requirement, ProfileAuthorization(protectedAdult.id, "2468")))
    }

    @Test
    fun `legacy kids profile can recover through unlocked adult but cannot administer yet`() {
        val adult = profile(1L, kids = false)
        val child = profile(2L, kids = true)
        val switchRequirement = profileManagementRequirement(
            listOf(adult, child),
            child.id,
            ProfileManagementAction.SWITCH,
            adult.id,
        )
        assertTrue(switchRequirement is ProfileManagementRequirement.Allowed)

        val recoverOwnPin = profileManagementRequirement(
            listOf(adult, child),
            adult.id,
            ProfileManagementAction.EDIT,
            adult.id,
        )
        assertTrue(recoverOwnPin is ProfileManagementRequirement.Allowed)

        val createBeforePin = profileManagementRequirement(
            listOf(adult, child),
            adult.id,
            ProfileManagementAction.CREATE,
        )
        assertTrue(createBeforePin is ProfileManagementRequirement.Blocked)
    }

    @Test
    fun `unlocked adult stays blocked when another protected adult exists`() {
        val unlockedAdult = profile(1L, kids = false)
        val child = profile(2L, kids = true)
        val protectedAdult = profile(3L, kids = false, pin = "2468")
        val requirement = profileManagementRequirement(
            listOf(unlockedAdult, child, protectedAdult),
            child.id,
            ProfileManagementAction.SWITCH,
            unlockedAdult.id,
        )
        assertTrue(requirement is ProfileManagementRequirement.Blocked)
    }

    @Test
    fun `last protected adult cannot be deleted while a kids profile remains`() {
        val adult = profile(1L, kids = false, pin = "2468")
        val child = profile(2L, kids = true)
        val requirement = profileManagementRequirement(
            listOf(adult, child),
            child.id,
            ProfileManagementAction.DELETE,
            adult.id,
        )
        assertTrue(requirement is ProfileManagementRequirement.Blocked)
        assertTrue(
            (requirement as ProfileManagementRequirement.Blocked).reason ==
                ProfileManagementBlockReason.NO_PROTECTED_ADULT,
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
