package tv.own.owntv.player

import tv.own.owntv.core.player.PlayerBudget

/** Resolve both mpv live-cache values so one does not silently override the requested latency. */
internal object MpvLiveBuffer {

    data class Secs(val readahead: String, val cache: String)

    /** [latencySecs] is the Live latency choice (null = Balanced); pre-roll is a required floor. */
    fun resolve(latencySecs: Int?, prerollSecs: Int, budget: PlayerBudget): Secs {
        val secs = latencySecs?.let { maxOf(it, prerollSecs).toString() }
            ?: return Secs(budget.readaheadSecs, budget.cacheSecs)
        return Secs(secs, secs)
    }
}
