package tv.own.owntv.mobile.ui.screens.guide

import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * Fail-closed bridge between a possibly stale Guide row and a recording mutation.
 *
 * Only the database row is returned to the caller. This prevents a stale or forged UI copy from
 * supplying a different source, category, URL or headers after authorization has completed.
 */
internal suspend fun authorizePersistedGuideChannel(
    profileId: Long?,
    requested: ChannelEntity,
    load: suspend (Long) -> ChannelEntity?,
    allows: suspend (Long, ChannelEntity) -> Boolean,
): ChannelEntity? {
    val pid = profileId?.takeIf { it >= 0L } ?: return null
    val requestedId = requested.id.takeIf { it > 0L } ?: return null
    val stored = load(requestedId)
        ?.takeIf { it.id == requestedId && it.sourceId == requested.sourceId }
        ?: return null
    return stored.takeIf { allows(pid, it) }
}
