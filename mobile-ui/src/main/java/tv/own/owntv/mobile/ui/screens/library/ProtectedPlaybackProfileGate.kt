package tv.own.owntv.mobile.ui.screens.library

/**
 * Serializes the final profile check with the active-profile collector for protected files.
 *
 * The expensive database, entitlement and container checks happen before this gate. Only the final
 * non-suspending player hand-off belongs here: either the freshly-read active profile still owns the
 * file and [start] records that ownership atomically, or no player call is made. The collector uses
 * [activeProfileChanged] through the same monitor, so it cannot observe the old `false` flag between
 * `player.play()` and the protected-playback state update.
 */
internal class ProtectedPlaybackProfileGate {
    private var observedActiveProfileId: Long? = null
    private var protectedOwnerProfileId: Long? = null

    @Synchronized
    fun activeProfileChanged(profileId: Long): Boolean {
        observedActiveProfileId = profileId
        return protectedOwnerProfileId?.let { it != profileId } == true
    }

    @Synchronized
    fun startIfCurrent(
        expectedProfileId: Long,
        freshlyReadProfileId: Long,
        start: () -> Unit,
    ): Boolean {
        if (freshlyReadProfileId != expectedProfileId) return false
        if (observedActiveProfileId?.let { it != expectedProfileId } == true) return false
        protectedOwnerProfileId = expectedProfileId
        return try {
            start()
            true
        } catch (failure: Throwable) {
            protectedOwnerProfileId = null
            throw failure
        }
    }

    @Synchronized
    fun owns(profileId: Long): Boolean = protectedOwnerProfileId == profileId

    @Synchronized
    fun clear() {
        protectedOwnerProfileId = null
    }
}
