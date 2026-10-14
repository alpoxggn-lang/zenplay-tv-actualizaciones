package tv.own.owntv.features.profiles

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.profile.ProfileManagementAction
import tv.own.owntv.core.profile.ProfileManagementRequirement
import tv.own.owntv.core.profile.ProfilePinPurpose
import tv.own.owntv.core.profile.profileManagementRequirement

/** TV-only contract for the actions shown after selecting an avatar on the profile gate. */
class ProfileGateActionsTest {

    @Test
    fun `focus returns to the same profile when it still exists`() {
        assertEquals(20L, profileGateFocusTarget(listOf(10L, 20L, 30L), 20L, 1))
    }

    @Test
    fun `deleting a profile moves focus to the nearest surviving neighbour`() {
        assertEquals(30L, profileGateFocusTarget(listOf(10L, 30L), 20L, 1))
        assertEquals(10L, profileGateFocusTarget(listOf(10L), 20L, 1))
        assertNull(profileGateFocusTarget(emptyList(), 20L, 1))
    }

    @Test
    fun `gate administration challenges a locked target before opening its editor`() {
        val active = profile(id = 10L, name = "Active")
        val lockedTarget = profile(id = 20L, name = "Locked", pinHash = "stored-hash")

        val challenge = profileGateManagementChallenge(
            profiles = listOf(active, lockedTarget),
            activeProfileId = active.id,
            action = ProfileManagementAction.EDIT,
            target = lockedTarget,
        )

        assertEquals(lockedTarget.id, challenge?.profile?.id)
        assertEquals(ProfilePinPurpose.TARGET_PROFILE, challenge?.purpose)
    }

    @Test
    fun `gate administration challenges the protected adult for an unlocked target`() {
        val protectedAdult = profile(id = 10L, name = "Adult", pinHash = "stored-hash")
        val unlockedTarget = profile(id = 20L, name = "Guest")

        val challenge = profileGateManagementChallenge(
            profiles = listOf(protectedAdult, unlockedTarget),
            activeProfileId = protectedAdult.id,
            action = ProfileManagementAction.DELETE,
            target = unlockedTarget,
        )

        assertEquals(protectedAdult.id, challenge?.profile?.id)
        assertEquals(ProfilePinPurpose.ADULT_ADMIN, challenge?.purpose)
    }

    @Test
    fun `gate creation challenges the active protected adult before opening the editor`() {
        val protectedAdult = profile(id = 10L, name = "Adult", pinHash = "stored-hash")

        assertTrue(
            profileManagementRequirement(
                profiles = listOf(protectedAdult),
                activeProfileId = protectedAdult.id,
                action = ProfileManagementAction.CREATE,
            ) is ProfileManagementRequirement.Allowed,
        )

        val challenge = profileGateManagementChallenge(
            profiles = listOf(protectedAdult),
            activeProfileId = protectedAdult.id,
            action = ProfileManagementAction.CREATE,
            target = null,
        )

        assertEquals(protectedAdult.id, challenge?.profile?.id)
        assertEquals(ProfilePinPurpose.ADULT_ADMIN, challenge?.purpose)
    }

    @Test
    fun `gate creation adds no artificial challenge when no protected adult exists`() {
        val unlockedAdult = profile(id = 10L, name = "Adult")

        assertTrue(
            profileManagementRequirement(
                profiles = listOf(unlockedAdult),
                activeProfileId = unlockedAdult.id,
                action = ProfileManagementAction.CREATE,
            ) is ProfileManagementRequirement.Allowed,
        )

        assertNull(
            profileGateManagementChallenge(
                profiles = listOf(unlockedAdult),
                activeProfileId = unlockedAdult.id,
                action = ProfileManagementAction.CREATE,
                target = null,
            ),
        )
    }

    @Test
    fun `gate switching and unprotected administration add no artificial pin`() {
        val active = profile(id = 10L, name = "Adult")
        val target = profile(id = 20L, name = "Guest")

        assertNull(
            profileGateManagementChallenge(
                profiles = listOf(active, target),
                activeProfileId = active.id,
                action = ProfileManagementAction.SWITCH,
                target = target,
            ),
        )
        assertNull(
            profileGateManagementChallenge(
                profiles = listOf(active, target),
                activeProfileId = active.id,
                action = ProfileManagementAction.EDIT,
                target = target,
            ),
        )
    }

    @Test
    fun `selecting a tv profile opens enter edit and protected delete actions`() {
        val source = File("src/main/java/tv/own/owntv/features/profiles/ProfileGate.kt").readText()
        val actionDialog = source.substringAfter("private fun ProfileGateActionDialog(")

        assertTrue(source.contains("actionTarget = p"))
        assertTrue(source.contains("ProfileGateActionDialog("))
        assertTrue(actionDialog.contains("R.string.profiles_use_profile"))
        assertTrue(actionDialog.contains("R.string.profiles_edit"))
        assertTrue(actionDialog.contains("R.string.common_delete"))
        assertTrue(actionDialog.contains("enabled = canDelete"))
        assertTrue(source.contains("request(ProfileManagementAction.EDIT, profile)"))
        assertTrue(source.contains("request(ProfileManagementAction.DELETE, profile)"))
        assertTrue(source.contains("ProfileEditorDialog("))
        assertTrue(source.contains("ConfirmDialog("))
        assertTrue(source.contains("canDeleteProfile(profiles.size, profile.id, activeProfileId)"))
    }

    private fun profile(
        id: Long,
        name: String,
        pinHash: String? = null,
    ) = ProfileEntity(
        id = id,
        name = name,
        avatarColor = 0,
        pinHash = pinHash,
    )
}
