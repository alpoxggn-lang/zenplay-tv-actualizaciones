package tv.own.owntv.core.profile

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.util.Pin
import tv.own.owntv.core.util.PinAttemptGuard

/**
 * Shared TV/mobile policy for Settings -> Customize.
 *
 * Customization can reveal content that was deliberately hidden, so a kids profile is not allowed
 * to enter it merely because the optional, older "Customize PIN" was never configured. A protected
 * adult must authorize the page first. The legacy state (kids profiles but no protected adult) is
 * deliberately blocked here; its only recovery remains switching to an adult and adding a PIN in
 * profile management.
 */
fun profileCustomizationRequirement(
    profiles: List<ProfileEntity>,
    activeProfileId: Long?,
    requestedProfileId: Long,
): ProfileManagementRequirement {
    val active = activeProfileId?.let { id -> profiles.firstOrNull { it.id == id } }
        ?: return ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.NO_ACTIVE_PROFILE)
    if (requestedProfileId < 0L || active.id != requestedProfileId) {
        return ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.INVALID_TARGET)
    }

    val protectedAdult = profiles.firstOrNull { !it.isKids && !it.pinHash.isNullOrBlank() }
    if (active.isKids) {
        return protectedAdult?.let {
            ProfileManagementRequirement.PinRequired(it, ProfilePinPurpose.ADULT_ADMIN)
        } ?: ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.NO_PROTECTED_ADULT)
    }

    // Preserve the legacy recovery path: an unlocked adult may enter profile management solely to
    // establish the missing PIN, but cannot reveal or rewrite hidden content first.
    if (profiles.any { it.isKids } && protectedAdult == null) {
        return ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.NO_PROTECTED_ADULT)
    }
    return ProfileManagementRequirement.Allowed
}

/**
 * Process-local capability for one open Customize page.
 *
 * The entered PIN is verified once and is never retained. The grant stores only the already-stored
 * PIN hash and is rechecked against a freshly computed requirement immediately before every write;
 * a profile switch, PIN change, deletion, or legacy-state change therefore revokes it automatically.
 */
class ProfileCustomizationAccess(
    private val profileDao: ProfileDao,
    private val settings: SettingsRepository,
) {
    private data class Grant(
        val requestedProfileId: Long,
        val adultProfileId: Long,
        val adultPinHash: String,
    )

    @Volatile
    private var grant: Grant? = null

    suspend fun entryRequirement(profileId: Long): ProfileManagementRequirement {
        val requirement = currentRequirement(profileId)
        return if (grantSatisfies(profileId, requirement)) {
            ProfileManagementRequirement.Allowed
        } else {
            requirement
        }
    }

    suspend fun unlock(profileId: Long, pin: String): Boolean {
        val requirement = currentRequirement(profileId)
        if (requirement is ProfileManagementRequirement.Allowed) return true
        if (requirement !is ProfileManagementRequirement.PinRequired) return false
        val hash = requirement.profile.pinHash?.takeIf { it.isNotBlank() } ?: return false
        if (!PinAttemptGuard.verify("customize:$profileId") {
                Pin.verify(pin, hash)
            }
        ) return false
        grant = Grant(profileId, requirement.profile.id, hash)
        return true
    }

    /** Recompute Core policy at the mutation boundary; UI state is never treated as authority. */
    suspend fun authorizeWrite(profileId: Long): Boolean {
        val requirement = currentRequirement(profileId)
        return requirement is ProfileManagementRequirement.Allowed ||
            grantSatisfies(profileId, requirement)
    }

    /** Leaving Customize revokes the page capability even if the active profile changed meanwhile. */
    fun clear() {
        grant = null
    }

    private suspend fun currentRequirement(profileId: Long): ProfileManagementRequirement =
        profileCustomizationRequirement(
            profiles = profileDao.getAllOnce(),
            activeProfileId = settings.activeProfileId.first(),
            requestedProfileId = profileId,
        )

    private fun grantSatisfies(
        profileId: Long,
        requirement: ProfileManagementRequirement,
    ): Boolean {
        val current = grant ?: return false
        val pinRequired = requirement as? ProfileManagementRequirement.PinRequired ?: return false
        return current.requestedProfileId == profileId &&
            current.adultProfileId == pinRequired.profile.id &&
            current.adultPinHash == pinRequired.profile.pinHash
    }
}
