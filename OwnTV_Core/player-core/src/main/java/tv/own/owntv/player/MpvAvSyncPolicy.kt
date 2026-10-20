package tv.own.owntv.player

/**
 * Timing options that keep mpv's picture tied to its audio clock.
 *
 * Some malformed live MPEG-TS feeds repeat or move video timestamps backwards. The first recovery is
 * demuxer-side `genpts`, which preserves normal A/V correction. Only a feed that keeps failing after
 * that retry reaches the nominal-FPS last resort; even there it keeps audio as master and never uses
 * mpv's `desync` free-running mode.
 */
internal data class MpvAvSyncProfile(
    val correctPts: String,
    val videoSync: String,
    val frameDrop: String,
)

internal object MpvAvSyncPolicy {
    /** mpv's documented smoothing window for noisy Android AudioTrack timestamp measurements. */
    const val AUTO_SYNC_SAMPLES = 30

    fun resolve(
        isLive: Boolean,
        brokenVideoTimestamps: Boolean,
        directRender: Boolean,
    ): MpvAvSyncProfile {
        val nominalFpsLastResort = isLive && brokenVideoTimestamps
        return MpvAvSyncProfile(
            correctPts = if (nominalFpsLastResort) "no" else "yes",
            // The direct MediaCodec surface follows audio. GPU/software output can additionally align
            // healthy frames to display vsync. A feed with unusable PTS cannot use that display clock,
            // so its nominal frame cadence stays attached to audio instead of free-running.
            videoSync = if (directRender || nominalFpsLastResort) "audio" else "display-resample",
            // Drop only genuinely late frames so video catches up instead of letting audio drift away.
            frameDrop = "decoder+vo",
        )
    }
}
