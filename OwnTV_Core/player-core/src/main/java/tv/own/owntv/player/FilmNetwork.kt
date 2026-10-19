package tv.own.owntv.player

import tv.own.owntv.core.player.PlayerBudget

/**
 * N18 — a film's buffer, network timeout and mid-play reconnects, resolved against the device.
 *
 * Films only: live has its own latency, pre-buffer and "Give up after" settings and its own reconnect
 * rules. Auto keeps the device-scaled memory budget and quick startup; its established Exo media
 * response gets a longer read wait so a congested server can recover without changing stream quality.
 *
 * **The buffer is seconds, never memory.** Each engine's byte ceiling stays the device tier's
 * ([PlayerBudget]), so a longer buffer cannot cost more memory than today — on a 2 GB television
 * (48 MiB) a 4K film fills the ceiling long before the seconds run out. That is why the setting reads
 * "up to", and why nothing here needs a separate low-memory clamp.
 *
 * This policy absorbs jitter and short outages; it deliberately never selects a lower representation.
 * If sustained bandwidth stays below the stream's bitrate, no finite buffer can provide uninterrupted
 * playback without either waiting periodically or choosing a lower-bitrate source.
 */
internal object FilmNetwork {

    /** mpv's own wait on a silent server, which Auto keeps. */
    const val MPV_AUTO_TIMEOUT_SECS = 60

    /**
     * ExoPlayer's shared client is intentionally impatient for catalogue/API work, but a film is a
     * long-lived response: on a congested connection a server can legitimately pause longer than the
     * shared 20 s read timeout and then continue. Auto therefore gives the *media read* the same 60 s
     * tolerance mpv already had. The TCP connect timeout remains the shared 15 s, so a dead host still
     * fails promptly; only an established stream gets this extra room.
     */
    const val EXO_AUTO_READ_TIMEOUT_SECS = 60

    /** Initial playback stays quick. These deeper thresholds are used only after an actual underrun. */
    const val EXO_START_BUFFER_MS = 2_500
    const val EXO_REBUFFER_MS = 8_000
    const val MPV_REBUFFER_SECS = 5

    /** Playback since the last reopen that gives the reconnect budget back — live's rule too. */
    const val HEALTHY_MS = 60_000L

    /** Seconds of film to buffer ahead: the user's choice, or the tier's. */
    fun bufferSecs(userSecs: Int, budget: PlayerBudget): Int =
        if (userSecs > 0) userSecs else budget.cacheSecs.toIntOrNull() ?: 30

    /** mpv's `demuxer-readahead-secs`: the user's choice, or the tier's own (which differs from its cache). */
    fun readaheadSecs(userSecs: Int, budget: PlayerBudget): String =
        if (userSecs > 0) userSecs.toString() else budget.readaheadSecs

    fun mpvTimeoutSecs(userSecs: Int): Int = if (userSecs > 0) userSecs else MPV_AUTO_TIMEOUT_SECS

    /** Read timeout for ExoPlayer; an explicit user choice remains exact. */
    fun exoReadTimeoutSecs(userSecs: Int): Int =
        if (userSecs > 0) userSecs else EXO_AUTO_READ_TIMEOUT_SECS

    /**
     * How much VOD mpv should accumulate after its cache has genuinely run dry. This never delays the
     * first frame (`cache-pause-initial=no`) and never asks for more than this device/user buffer can
     * hold. Five seconds avoids the one-second play/buffer/play loop seen on jittery slow links without
     * changing the selected stream or its quality.
     */
    fun mpvRebufferSecs(userSecs: Int, budget: PlayerBudget): Int =
        bufferSecs(userSecs, budget).coerceAtMost(MPV_REBUFFER_SECS).coerceAtLeast(1)

    /** Same policy for ExoPlayer, bounded by the configured/device buffer. */
    fun exoRebufferMs(userSecs: Int, budget: PlayerBudget): Int =
        (bufferSecs(userSecs, budget) * 1_000).coerceAtMost(EXO_REBUFFER_MS).coerceAtLeast(EXO_START_BUFFER_MS)

    /**
     * Whether a film that dropped at [posMs] may be reopened, as the new count of attempts used — or
     * null when [budget] is spent. A minute of playback since the last reopen (at [lastAtPosMs]) starts
     * the count again, so a two-hour film on a line that drops once an hour never runs out.
     */
    fun nextReconnect(used: Int, lastAtPosMs: Long, posMs: Long, budget: Int): Int? {
        val counted = if (used > 0 && posMs - lastAtPosMs >= HEALTHY_MS) 0 else used
        return if (counted < budget) counted + 1 else null
    }
}
