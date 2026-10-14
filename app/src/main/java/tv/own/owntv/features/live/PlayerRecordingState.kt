package tv.own.owntv.features.live

import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingStatus

/**
 * Reconciles the recordings started from the fullscreen player's Record button with Room.
 *
 * The channel id is part of the identity on purpose: a recording started on channel A must never
 * make channel B look as if it is recording, nor let B's Record button stop A. Room remains the
 * authority for terminal state; the in-memory row only bridges the tiny interval between schedule()
 * returning and Room emitting its updated list.
 */
internal fun reconcilePlayerRecordings(
    tracked: Map<Long, RecordingEntity>,
    stored: List<RecordingEntity>,
    nowMs: Long,
): Map<Long, RecordingEntity> {
    val storedById = stored.associateBy(RecordingEntity::id)
    return tracked.mapNotNull { (channelId, remembered) ->
        val current = storedById[remembered.id] ?: remembered
        val active = current.channelId == channelId &&
            current.stopMs > nowMs &&
            (current.status == RecordingStatus.SCHEDULED || current.status == RecordingStatus.RECORDING)
        if (active) channelId to current else null
    }.toMap()
}

