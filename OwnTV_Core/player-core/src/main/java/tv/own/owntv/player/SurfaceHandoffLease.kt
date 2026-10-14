package tv.own.owntv.player

/**
 * Owns the output surface while one player view is being replaced by another.
 *
 * Compose/navigation can destroy the outgoing SurfaceView before the incoming one has produced its
 * Surface. Clearing the decoder output in that short gap is enough to make a few Android/Fire TV
 * codecs stop producing frames. A detach is therefore a small, cancellable lease: attaching a newer
 * surface invalidates the old ticket, while a view that truly went away is detached after the grace
 * period by its owner.
 *
 * Identity comparisons are intentional. Two [android.view.Surface] wrappers are not interchangeable
 * merely because they happen to describe the same native window.
 */
internal class SurfaceHandoffLease<T : Any> {
    internal data class DetachTicket<T : Any>(val value: T, val generation: Long)

    private var generation = 0L

    var current: T? = null
        private set

    fun attach(value: T) {
        generation++
        current = value
    }

    fun requestDetach(value: T): DetachTicket<T>? {
        if (current !== value) return null
        return DetachTicket(value, generation)
    }

    /** True only when no newer surface has taken over since [requestDetach]. */
    fun commit(ticket: DetachTicket<T>): Boolean {
        if (ticket.generation != generation || current !== ticket.value) return false
        generation++
        current = null
        return true
    }

    /** Immediate teardown for a real stop/release rather than a view-to-view transfer. */
    fun clear(): T? {
        val previous = current
        generation++
        current = null
        return previous
    }

    companion object {
        /** Long enough to span one navigation/layout handoff, short enough not to retain dead video. */
        const val DETACH_GRACE_MS = 500L
    }
}
