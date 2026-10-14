package tv.own.owntv.mobile.ui.screens.library

/**
 * Makes an asynchronous VOD exit belong to the playback session that requested it.
 *
 * Saving progress may wait on Room. During that wait the user can open another title, so the old
 * coroutine must not clear the new title or stop the shared player when the write eventually
 * returns. Starting or clearing playback advances the generation; only a ticket for the still
 * current generation may perform the destructive half of an exit.
 */
internal class VodPlaybackExitGate {
    internal class ExitTicket internal constructor(val generation: Long)

    private var generation = 0L
    private var active = false

    @Synchronized
    fun playbackStarted() {
        generation++
        active = true
    }

    @Synchronized
    fun requestExit(): ExitTicket? =
        if (active) ExitTicket(generation) else null

    @Synchronized
    fun commitExit(ticket: ExitTicket): Boolean {
        if (!active || ticket.generation != generation) return false
        generation++
        active = false
        return true
    }

    @Synchronized
    fun playbackCleared() {
        generation++
        active = false
    }
}
