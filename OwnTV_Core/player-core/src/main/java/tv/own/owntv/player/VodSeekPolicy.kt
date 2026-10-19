package tv.own.owntv.player

/**
 * Rules shared by the mpv and ExoPlayer VOD paths when the user scrubs or skips.
 *
 * A seek is user intent, not an end-of-file request. Keeping a small guard before a known duration
 * prevents a forward step or a slider rounded to 100% from turning into a natural EOF. The playback
 * watchdog helpers deliberately require two things: the engine must first land near the requested
 * point, then its clock must advance again. Merely publishing the new timestamp while the decoder is
 * stuck is not a successful seek.
 */
internal object VodSeekPolicy {
    const val END_GUARD_MS = 2_000L
    const val LANDING_TOLERANCE_MS = 5_000L
    const val RESUMED_PROGRESS_MS = 500L
    const val RECOVERY_TIMEOUT_MS = 15_000L
    const val RECOVERY_POLL_MS = 1_000L

    data class Plan(
        val startMs: Long,
        val targetMs: Long,
        val resumeAfterSeek: Boolean,
    ) {
        val moves: Boolean get() = targetMs != startMs
    }

    fun plan(currentMs: Long, deltaMs: Long, durationMs: Long, wasPlaying: Boolean): Plan {
        val start = currentMs.coerceAtLeast(0L)
        return planTo(
            currentMs = start,
            targetMs = saturatingAdd(start, deltaMs),
            durationMs = durationMs,
            wasPlaying = wasPlaying,
        )
    }

    /**
     * Plan an absolute seek (timeline tap/drag, system media scrubber, or a saved resume point).
     *
     * Keeping this separate from [plan] is important: turning an absolute destination into a delta at
     * the UI boundary races the engine clock. By the time the command reaches mpv/ExoPlayer the observed
     * position may already have advanced, which shifts the destination and can turn repeated drag events
     * into a chain of ranged requests instead of one stable request.
     */
    fun planTo(currentMs: Long, targetMs: Long, durationMs: Long, wasPlaying: Boolean): Plan {
        val start = currentMs.coerceAtLeast(0L)
        val unclamped = targetMs.coerceAtLeast(0L)
        val lastPlayable = when {
            durationMs <= 0L -> Long.MAX_VALUE
            durationMs <= END_GUARD_MS -> 0L
            else -> durationMs - END_GUARD_MS
        }
        return Plan(
            startMs = start,
            targetMs = unclamped.coerceAtMost(lastPlayable),
            resumeAfterSeek = wasPlaying,
        )
    }

    /** True once the engine reports a position close to the requested destination. */
    fun landed(plan: Plan, positionMs: Long): Boolean {
        if (!plan.moves) return true
        val position = positionMs.coerceAtLeast(0L)
        val distance = absoluteDifference(plan.targetMs, plan.startMs)
        val requiredMovement = minOf(RESUMED_PROGRESS_MS, distance)
        return when {
            plan.targetMs > plan.startMs ->
                position >= plan.targetMs - LANDING_TOLERANCE_MS && position >= plan.startMs + requiredMovement
            else ->
                position <= plan.targetMs + LANDING_TOLERANCE_MS && position <= plan.startMs - requiredMovement
        }
    }

    /** A landed seek is healthy only after the media clock starts moving forward again. */
    fun resumed(landedAtMs: Long, positionMs: Long): Boolean =
        positionMs >= saturatingAdd(landedAtMs.coerceAtLeast(0L), RESUMED_PROGRESS_MS)

    /**
     * A seek is not recovered merely because the timestamp changed. Xtream servers can answer the
     * ranged request, publish one timestamp and then leave the decoder permanently buffering. A playing
     * seek is complete only once the engine is out of buffering and its clock advances again. A seek
     * made while paused only has to land, while still requiring the engine to finish buffering.
     */
    fun recovered(
        plan: Plan,
        landedAtMs: Long,
        positionMs: Long,
        isPlaying: Boolean,
        isBuffering: Boolean,
    ): Boolean {
        if (isBuffering) return false
        return if (plan.resumeAfterSeek) {
            isPlaying && resumed(landedAtMs, positionMs)
        } else {
            true
        }
    }

    /**
     * Position carried into an engine recovery. An explicit recovery destination wins, followed by an
     * unconfirmed seek/resume target; the engine clock is only trusted after that target is confirmed.
     */
    fun recoveryPosition(
        explicitMs: Long?,
        pendingTargetMs: Long,
        currentPositionMs: Long,
        fileOpened: Boolean,
    ): Long = when {
        explicitMs != null -> explicitMs.coerceAtLeast(0L)
        pendingTargetMs > 0L -> pendingTargetMs
        fileOpened && currentPositionMs > 0L -> currentPositionMs
        else -> 0L
    }

    /** Carry a logical resume point into another rendition without seeking beyond its EOF. */
    fun replacementPosition(positionMs: Long, replacementDurationMs: Long?): Long {
        val position = positionMs.coerceAtLeast(0L)
        val duration = replacementDurationMs?.takeIf { it > 0L } ?: return position
        val lastPlayable = (duration - END_GUARD_MS).coerceAtLeast(0L)
        return position.coerceAtMost(lastPlayable)
    }

    private fun saturatingAdd(left: Long, right: Long): Long = when {
        right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
        right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
        else -> left + right
    }

    private fun absoluteDifference(left: Long, right: Long): Long = when {
        left >= right -> left - right
        else -> right - left
    }
}

/**
 * Generation guard for the one VOD seek whose landing is currently being verified.
 *
 * A slider can issue a second destination while the first watchdog is asleep, and a pause/play press
 * can change the completion rule without changing that destination.  A ticket makes those replacements
 * atomic: an outgoing coroutine can neither clear the newer target nor run its recovery after it has
 * been superseded.
 */
internal class VodSeekRecoveryState {
    internal data class Ticket(val generation: Long, val plan: VodSeekPolicy.Plan)

    private var generation = 0L
    private var active: Ticket? = null

    @Synchronized
    fun begin(plan: VodSeekPolicy.Plan): Ticket? {
        generation++
        return if (plan.moves) {
            Ticket(generation, plan).also { active = it }
        } else {
            active = null
            null
        }
    }

    /** Replace only the play/pause intent of the active destination. */
    @Synchronized
    fun updatePlayIntent(resumeAfterSeek: Boolean): Ticket? {
        val current = active ?: return null
        generation++
        return Ticket(
            generation = generation,
            plan = current.plan.copy(resumeAfterSeek = resumeAfterSeek),
        ).also { active = it }
    }

    @Synchronized
    fun isCurrent(ticket: Ticket): Boolean = active == ticket

    /** Complete only the still-current request; stale watchdogs are harmless. */
    @Synchronized
    fun complete(ticket: Ticket): Boolean {
        if (active != ticket) return false
        generation++
        active = null
        return true
    }

    @Synchronized
    fun invalidate() {
        generation++
        active = null
    }
}
