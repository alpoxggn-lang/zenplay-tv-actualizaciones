package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibleVariantRecoveryFailureTest {
    @Test
    fun `offers replacement for unsupported VOD video`() {
        val failure = PlaybackFailure.BothEnginesExoFirst(
            exoError = PlaybackFailure.ExoDecode("FORMAT_UNSUPPORTED", DecodeTrack.VIDEO),
            mpvError = PlaybackFailure.MpvOpenDecode,
        )
        assertTrue(
            isCompatibleVariantRecoveryFailure(
                failure,
                ErrorInfo(
                    PlayerFailureReason.UNSUPPORTED_VIDEO,
                    null,
                    "MediaCodec configure failed",
                    failure,
                ),
                isLive = false,
            ),
        )
    }

    @Test
    fun `hardware format failure is enough to offer replacement`() {
        assertTrue(
            isCompatibleVariantRecoveryFailure(
                PlaybackFailure.HardwareFormat("3840x1608", "HEVC"),
                info = null,
                isLive = false,
            ),
        )
    }

    @Test
    fun `never offers replacement for live network drm or busy failures`() {
        val hardware = PlaybackFailure.HardwareFormat("4K", "HEVC")
        val unsupported = ErrorInfo(PlayerFailureReason.UNSUPPORTED_VIDEO, null, null, hardware)
        assertFalse(isCompatibleVariantRecoveryFailure(PlaybackFailure.HardwareFormat("4K", "HEVC"), unsupported, isLive = true))
        val noInternet = PlaybackFailure.NoInternet
        val drm = PlaybackFailure.ExoPlay("drm")
        val busy = PlaybackFailure.DecoderBusy
        assertFalse(isCompatibleVariantRecoveryFailure(noInternet, ErrorInfo(PlayerFailureReason.NETWORK, null, null, noInternet), isLive = false))
        assertFalse(isCompatibleVariantRecoveryFailure(drm, ErrorInfo(PlayerFailureReason.DRM, null, null, drm), isLive = false))
        assertFalse(isCompatibleVariantRecoveryFailure(busy, ErrorInfo(PlayerFailureReason.DECODER_BUSY, null, null, busy), isLive = false))
        assertFalse(isCompatibleVariantRecoveryFailure(busy, ErrorInfo(PlayerFailureReason.DECODER_MEMORY, null, null, busy), isLive = false))
    }

    @Test
    fun `typed video decoder failures work without logcat but audio does not`() {
        assertTrue(
            isCompatibleVariantRecoveryFailure(
                PlaybackFailure.ExoDecode("DECODER_INIT_FAILED", DecodeTrack.VIDEO),
                info = null,
                isLive = false,
            ),
        )
        assertTrue(
            isCompatibleVariantRecoveryFailure(
                PlaybackFailure.HardwareFallback("2160p"),
                info = null,
                isLive = false,
            ),
        )
        assertFalse(
            isCompatibleVariantRecoveryFailure(
                PlaybackFailure.ExoDecode("FORMAT_UNSUPPORTED", DecodeTrack.AUDIO),
                info = null,
                isLive = false,
            ),
        )
    }

    @Test
    fun `stale or conflicting diagnostics cannot authorize a replacement`() {
        val current = PlaybackFailure.BothEnginesExoFirst(
            exoError = PlaybackFailure.ExoPlay("SOURCE"),
            mpvError = PlaybackFailure.StreamLink,
        )
        val prior = PlaybackFailure.BothEnginesExoFirst(
            exoError = PlaybackFailure.ExoDecode("FORMAT_UNSUPPORTED", DecodeTrack.VIDEO),
            mpvError = PlaybackFailure.MpvOpenDecode,
        )
        assertFalse(
            isCompatibleVariantRecoveryFailure(
                current,
                ErrorInfo(PlayerFailureReason.UNSUPPORTED_VIDEO, null, "old", prior),
                isLive = false,
            ),
        )
        // The typed hardware failure is stronger than best-effort process logcat, which can contain
        // a simultaneous preview/multiview network error from the shared diagnostics reader.
        val hardware = PlaybackFailure.HardwareFormat("4K", "HEVC")
        assertTrue(
            isCompatibleVariantRecoveryFailure(
                hardware,
                ErrorInfo(PlayerFailureReason.NETWORK, null, "timeout", hardware),
                isLive = false,
            ),
        )
    }
}
