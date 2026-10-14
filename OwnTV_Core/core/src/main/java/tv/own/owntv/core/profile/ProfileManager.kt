package tv.own.owntv.core.profile

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.subtitles.OpenSubtitlesAccountManager
import tv.own.owntv.core.util.Pin
import tv.own.owntv.core.util.PinAttemptGuard

/**
 * Creating, editing, switching and deleting a profile — everything about a profile that is not
 * layout.
 *
 * Both apps run this. What a profile *is* spans more tables than the `profiles` row itself: its
 * sources, its OpenSubtitles login, its "start on this channel" target, and the app-wide active id
 * that every screen resolves its content against. Two independent implementations of that would
 * drift, and the two apps share one database.
 */
class ProfileManager(
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val launcherIntegration: LauncherIntegrationRepository,
    private val openSubtitlesAccounts: OpenSubtitlesAccountManager,
    private val avatars: ProfileAvatarStore,
) {

    /** Serializes identity-changing operations so switch/delete cannot invalidate each other's check. */
    private val mutationMutex = Mutex()

    /** Current Core challenge for an operation. UI uses this to present the right PIN prompt. */
    suspend fun managementRequirement(
        action: ProfileManagementAction,
        targetProfileId: Long? = null,
    ): ProfileManagementRequirement = currentRequirement(action, targetProfileId)

    /** Make [id] active only after Core independently validates the target/profile PIN policy. */
    suspend fun switchTo(id: Long, authorization: ProfileAuthorization? = null): Boolean =
        mutationMutex.withLock {
            if (!authorized(ProfileManagementAction.SWITCH, id, authorization)) return@withLock false
            settings.setActiveProfile(id)
            true
        }

    fun verifyPin(profile: ProfileEntity, pin: String): Boolean =
        PinAttemptGuard.verify("profile:${profile.id}") { Pin.verify(pin, profile.pinHash) }

    /**
     * Add a profile. It inherits every source that already exists — one account, several viewers —
     * so it has something to watch immediately; favorites and history stay its own.
     */
    suspend fun create(
        name: String,
        avatarId: Int,
        isKids: Boolean,
        pin: String?,
        fallbackName: String,
        authorization: ProfileAuthorization? = null,
    ): Long? = mutationMutex.withLock {
        if (!authorized(ProfileManagementAction.CREATE, null, authorization)) return@withLock null
        if (!pin.isNullOrEmpty() && !Pin.isValid(pin)) return@withLock null
        val profiles = profileDao.getAllOnce()
        // A kids profile with no protected adult would permanently strand profile administration.
        if (isKids && profiles.none { !it.isKids && !it.pinHash.isNullOrBlank() }) return@withLock null
        val id = profileDao.insert(
            ProfileEntity(
                name = name.ifBlank { fallbackName },
                avatarColor = 0,
                avatarId = avatarId,
                isKids = isKids,
                pinHash = pin?.takeIf { it.isNotBlank() }?.let { Pin.hash(it) },
            ),
        )
        sourceDao.allSourceIds().forEach { sourceDao.link(ProfileSourceCrossRef(profileId = id, sourceId = it)) }
        id
    }

    /** Apply the editor's changes. [pin]: null keeps the existing PIN, "" removes it. */
    suspend fun edit(
        profile: ProfileEntity,
        name: String,
        avatarId: Int,
        isKids: Boolean,
        pin: String?,
        authorization: ProfileAuthorization? = null,
    ): Boolean = mutationMutex.withLock {
        if (!authorized(ProfileManagementAction.EDIT, profile.id, authorization)) return@withLock false
        if (pin != null && pin.isNotEmpty() && !Pin.isValid(pin)) return@withLock false
        val stored = profileDao.getById(profile.id) ?: return@withLock false
        val pinHash = when {
            pin == null -> stored.pinHash
            pin.isEmpty() -> null
            else -> Pin.hash(pin)
        }
        val updated = stored.copy(
            name = name.ifBlank { stored.name },
            avatarId = avatarId,
            isKids = isKids,
            pinHash = pinHash,
        )
        val prospective = profileDao.getAllOnce().map { if (it.id == stored.id) updated else it }
        // Once a kids profile exists, at least one adult PIN must remain as its administration key.
        if (prospective.any { it.isKids } &&
            prospective.none { !it.isKids && !it.pinHash.isNullOrBlank() }
        ) return@withLock false
        profileDao.update(
            updated,
        )
        if (stored.isKids != isKids) {
            launcherIntegration.refreshProfile(stored.id, allowBrowsableRequest = false)
        }
        true
    }

    /**
     * Give [profile] a picture of its own, taken from [source] — a file the user picked on this
     * device, or one a phone sent over the local network. Returns false when the file turned out not
     * to be a readable image, in which case nothing changes and the drawn tile stays.
     *
     * The picture replaces the drawn tile but does not erase the choice of tile: clearing it later
     * brings back the one the profile already had rather than resetting to the first.
     */
    suspend fun setCustomAvatar(
        profile: ProfileEntity,
        source: java.io.File,
        authorization: ProfileAuthorization? = null,
    ): Boolean = mutationMutex.withLock {
        if (!authorized(ProfileManagementAction.EDIT, profile.id, authorization)) return@withLock false
        if (isLegacyPinRecovery(profile.id)) return@withLock false
        apply(profile.id, avatars.save(profile.id, source))
    }

    /**
     * As above, for a picture that is not a file on disk — the phone's photo picker hands over a
     * content stream, and copying it out to a temporary file first would only be so that this could
     * copy it in again.
     */
    suspend fun setCustomAvatar(
        profile: ProfileEntity,
        source: java.io.InputStream,
        authorization: ProfileAuthorization? = null,
    ): Boolean = mutationMutex.withLock {
        if (!authorized(ProfileManagementAction.EDIT, profile.id, authorization)) return@withLock false
        if (isLegacyPinRecovery(profile.id)) return@withLock false
        apply(profile.id, avatars.save(profile.id, source))
    }

    private suspend fun apply(profileId: Long, savedPath: String?): Boolean {
        val path = savedPath ?: return false
        val stored = profileDao.getById(profileId) ?: return false
        profileDao.update(stored.copy(avatarPath = path))
        return true
    }

    /** Drop [profile]'s own picture, returning it to the drawn tile named by its `avatarId`. */
    suspend fun clearCustomAvatar(
        profile: ProfileEntity,
        authorization: ProfileAuthorization? = null,
    ): Boolean = mutationMutex.withLock {
        if (!authorized(ProfileManagementAction.EDIT, profile.id, authorization)) return@withLock false
        if (isLegacyPinRecovery(profile.id)) return@withLock false
        val stored = profileDao.getById(profile.id) ?: return@withLock false
        avatars.clear(stored.id)
        profileDao.update(stored.copy(avatarPath = null))
        true
    }

    /**
     * Delete a non-active profile and everything keyed to it. The active and last profiles are
     * protected — a profile must be switched away from before it can be removed, and an app with
     * none has nobody to resolve any content for.
     */
    suspend fun delete(profile: ProfileEntity, authorization: ProfileAuthorization? = null): Boolean =
        mutationMutex.withLock {
        if (!authorized(ProfileManagementAction.DELETE, profile.id, authorization)) return@withLock false
        val stored = profileDao.getById(profile.id) ?: return@withLock false
        val survivors = profileDao.getAllOnce().filterNot { it.id == stored.id }
        if (survivors.any { it.isKids } &&
            survivors.none { !it.isKids && !it.pinHash.isNullOrBlank() }
        ) return@withLock false
        runCatching { launcherIntegration.clearProfile(stored.id) }
        // Deleting a profile permanently erases its stored OpenSubtitles login (subtitle plan §5.5).
        openSubtitlesAccounts.eraseFor(stored.id)
        // …and its picture, which nothing else would ever clean up.
        avatars.clear(stored.id)
        settings.setStartupChannel(stored.id, null)
        profileDao.delete(stored)
        true
    }

    private suspend fun currentRequirement(
        action: ProfileManagementAction,
        targetProfileId: Long?,
    ): ProfileManagementRequirement = profileManagementRequirement(
        profiles = profileDao.getAllOnce(),
        activeProfileId = settings.activeProfileId.first(),
        action = action,
        targetProfileId = targetProfileId,
    )

    private suspend fun authorized(
        action: ProfileManagementAction,
        targetProfileId: Long?,
        authorization: ProfileAuthorization?,
    ): Boolean = when (val requirement = currentRequirement(action, targetProfileId)) {
        ProfileManagementRequirement.Allowed -> true
        is ProfileManagementRequirement.Blocked -> false
        is ProfileManagementRequirement.PinRequired ->
            authorization?.profileId == requirement.profile.id &&
                PinAttemptGuard.verify("profile:${requirement.profile.id}") {
                    Pin.verify(authorization.pin, requirement.profile.pinHash)
                }
    }

    /** Legacy recovery permits the editor to open, but no side mutation before it establishes a PIN. */
    private suspend fun isLegacyPinRecovery(profileId: Long): Boolean {
        val profiles = profileDao.getAllOnce()
        val active = settings.activeProfileId.first()
        return active == profileId &&
            profiles.firstOrNull { it.id == profileId }?.isKids == false &&
            profiles.any { it.isKids } &&
            profiles.none { !it.isKids && !it.pinHash.isNullOrBlank() }
    }
}

/** How many avatars each app draws. Both shells number them the same, so a profile made on one
 *  device shows the same picture on the other. */
const val PROFILE_AVATAR_COUNT = 10

/** One unlocked profile enters the app immediately; every chooser and PIN case stays gated. */
fun profileGateRequired(profiles: List<ProfileEntity>): Boolean =
    profiles.size > 1 || profiles.singleOrNull()?.pinHash != null

/**
 * Whether the app proper may be shown yet, given what is known so far.
 *
 * Both launch decisions run through this, because getting it wrong shows somebody else's library.
 * [profiles] null is Room not having answered — deliberately different from an answered empty list,
 * which is a fresh install or a restore window and is not permission to enter either. The unlock is
 * bound to an id, so a session that unlocked one profile cannot admit another.
 */
fun shellMayCompose(
    profiles: List<ProfileEntity>?,
    activeProfileId: Long?,
    authenticatedProfileId: Long?,
    gateRequired: Boolean,
): Boolean {
    val loaded = profiles ?: return false
    val active = activeProfileId ?: return false
    if (active < 0L || loaded.none { it.id == active }) return false
    return !gateRequired || authenticatedProfileId == active
}
