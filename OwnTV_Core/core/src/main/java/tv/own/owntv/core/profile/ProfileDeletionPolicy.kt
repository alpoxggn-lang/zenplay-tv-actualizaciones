package tv.own.owntv.core.profile

import tv.own.owntv.core.database.entity.ProfileEntity

/**
 * A profile may only be removed when another, already-active identity remains in charge.
 *
 * Keeping this rule in core makes the television and touch shells agree even if a stale screen
 * tries to submit a delete after the profile list or active id changed underneath it.
 */
fun canDeleteProfile(profileCount: Int, profileId: Long, activeProfileId: Long?): Boolean =
    profileCount > 1 && activeProfileId != null && activeProfileId >= 0L && profileId != activeProfileId

/** A different locked profile must prove its PIN before it can be switched, edited or deleted. */
fun profileManagementRequiresPin(pinHash: String?, profileId: Long, activeProfileId: Long?): Boolean =
    !pinHash.isNullOrBlank() && profileId != activeProfileId

/** Every operation that can change who may see unrestricted content. */
enum class ProfileManagementAction { CREATE, EDIT, DELETE, SWITCH }

/** Why profile administration cannot continue without a different authenticated identity. */
enum class ProfileManagementBlockReason {
    NO_ACTIVE_PROFILE,
    NO_PROTECTED_ADULT,
    INVALID_TARGET,
    ACTIVE_OR_LAST_PROFILE,
}

enum class ProfilePinPurpose { TARGET_PROFILE, ADULT_ADMIN }

/**
 * Core's answer to both shells before they open an editor or mutate a profile.
 *
 * The UI may present the challenge, but it does not decide who is allowed: [ProfileManager]
 * recomputes this requirement immediately before every write and verifies [ProfileAuthorization]
 * against the current stored hash.
 */
sealed interface ProfileManagementRequirement {
    data object Allowed : ProfileManagementRequirement
    data class PinRequired(
        val profile: ProfileEntity,
        val purpose: ProfilePinPurpose,
    ) : ProfileManagementRequirement
    data class Blocked(val reason: ProfileManagementBlockReason) : ProfileManagementRequirement
}

/** A short-lived, in-memory proof supplied to Core; the PIN is never persisted. */
data class ProfileAuthorization(val profileId: Long, val pin: String)

/**
 * Shared TV/mobile policy for switching and administrative profile actions.
 *
 * A kids profile is never an administrator. It must present the PIN of a protected adult profile;
 * if no such profile exists the operation fails closed. The sole compatibility exception is a
 * legacy database that already has kids profiles but no protected adult: it may switch to an
 * unlocked adult, where only editing that same adult to establish a PIN can succeed. No profile
 * mutation is otherwise authorized until that recovery is complete.
 */
fun profileManagementRequirement(
    profiles: List<ProfileEntity>,
    activeProfileId: Long?,
    action: ProfileManagementAction,
    targetProfileId: Long? = null,
): ProfileManagementRequirement {
    if (action == ProfileManagementAction.CREATE && profiles.isEmpty()) {
        return ProfileManagementRequirement.Allowed
    }

    val target = targetProfileId?.let { id -> profiles.firstOrNull { it.id == id } }
    if (action != ProfileManagementAction.CREATE && target == null) {
        return ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.INVALID_TARGET)
    }
    if (action == ProfileManagementAction.DELETE &&
        !canDeleteProfile(profiles.size, targetProfileId ?: -1L, activeProfileId)
    ) {
        return ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.ACTIVE_OR_LAST_PROFILE)
    }
    if (action == ProfileManagementAction.DELETE) {
        val survivors = profiles.filterNot { it.id == targetProfileId }
        if (survivors.any { it.isKids } && survivors.none { !it.isKids && !it.pinHash.isNullOrBlank() }) {
            return ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.NO_PROTECTED_ADULT)
        }
    }

    val active = activeProfileId?.let { id -> profiles.firstOrNull { it.id == id } }
    val hasKids = profiles.any { it.isKids }
    val protectedAdultExists = profiles.any { !it.isKids && !it.pinHash.isNullOrBlank() }
    if (action == ProfileManagementAction.SWITCH) {
        // Opening an already-selected profile is still an identity gate: launchers and stale UI
        // state may request SWITCH for the active profile after the app has been backgrounded.
        // Never let that fast path bypass the profile's own PIN.
        if (target != null && !target.pinHash.isNullOrBlank()) {
            return ProfileManagementRequirement.PinRequired(target, ProfilePinPurpose.TARGET_PROFILE)
        }
        if (target?.id == active?.id) return ProfileManagementRequirement.Allowed
        if (active?.isKids == true && target?.isKids == false) {
            // Compatibility recovery for databases created before the protected-adult invariant:
            // switching identity is allowed, but Core still blocks every mutation except setting
            // this adult's PIN (see the recovery branch below and ProfileManager.edit).
            if (hasKids && !protectedAdultExists) return ProfileManagementRequirement.Allowed
            return ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.NO_PROTECTED_ADULT)
        }
        // Recovery after restore is allowed when no active id resolves. It does not authorize any
        // profile mutation; it only establishes the identity subsequent checks will use.
        return ProfileManagementRequirement.Allowed
    }

    if (active == null) {
        return ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.NO_ACTIVE_PROFILE)
    }
    if (hasKids && !protectedAdultExists && !active.isKids) {
        return if (action == ProfileManagementAction.EDIT && target?.id == active.id) {
            ProfileManagementRequirement.Allowed
        } else {
            ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.NO_PROTECTED_ADULT)
        }
    }
    if (active.isKids) {
        val protectedAdult = profiles.firstOrNull { !it.isKids && !it.pinHash.isNullOrBlank() }
            ?: return ProfileManagementRequirement.Blocked(ProfileManagementBlockReason.NO_PROTECTED_ADULT)
        return ProfileManagementRequirement.PinRequired(protectedAdult, ProfilePinPurpose.ADULT_ADMIN)
    }

    // An authenticated adult may manage itself and unlocked profiles. A different locked profile
    // still owns its PIN unless an adult-admin challenge above already superseded it.
    if (target != null && target.id != active.id && !target.pinHash.isNullOrBlank()) {
        return ProfileManagementRequirement.PinRequired(target, ProfilePinPurpose.TARGET_PROFILE)
    }
    return ProfileManagementRequirement.Allowed
}

/** Validate a supplied proof against the exact challenge Core just recomputed. */
fun profileAuthorizationSatisfies(
    requirement: ProfileManagementRequirement,
    authorization: ProfileAuthorization?,
): Boolean = when (requirement) {
    ProfileManagementRequirement.Allowed -> true
    is ProfileManagementRequirement.Blocked -> false
    is ProfileManagementRequirement.PinRequired -> authorization?.profileId == requirement.profile.id &&
        tv.own.owntv.core.util.Pin.verify(authorization.pin, requirement.profile.pinHash)
}
