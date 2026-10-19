package tv.own.owntv.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import tv.own.owntv.player.ffmpeg.FfmpegLibrary

/**
 * ExoPlayer's FFmpeg audio decoder ([tv.own.owntv.player.ffmpeg]), running on the FFmpeg the mpv engine
 * already ships (`libowntvffmpeg.so` in OwnTV_libmpv). It sits **behind** the device's MediaCodec
 * decoders in [OwnTVRenderersFactory], so the TV's chip and passthrough always win; FFmpeg only decodes
 * what the chip cannot (DTS, TrueHD, MP2 on many boxes). On an engine without the library it reports
 * unavailable and everything behaves as before.
 */
@UnstableApi
object FfmpegAudio {
    const val TAG = "OwnTV-FfmpegAudio"

    /** Loads the library on first use and logs the outcome once, so a broken engine contract shows. */
    val available: Boolean by lazy {
        val ok = FfmpegLibrary.isAvailable()
        android.util.Log.i(TAG, "available=$ok lavc=${FfmpegLibrary.getVersion()}")
        ok
    }

    private val supported = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** FFmpeg can decode this audio MIME type here. */
    fun supports(mime: String): Boolean =
        available && supported.getOrPut(mime) { FfmpegLibrary.supportsFormat(mime) }
}

/** Which audio ExoPlayer can play — the film engine's hand-off gate and the "no playable audio" check. */
object ExoAudioGate {

    /** Why the film gate let a codec through, or [UNSUPPORTED]. */
    enum class Verdict(val safe: Boolean) {
        ALLOWLIST(true), DEVICE_DECODER(true), FFMPEG(true), PASSTHROUGH(true), UNSUPPORTED(false)
    }

    /**
     * mpv codec names every device decodes (MediaCodec or Media3's own), matched by prefix. MP2 is not
     * one of them: Media3 has no MP2→MP3 substitution and many decoders declare only MP3, so it goes
     * through the checks below like DTS and TrueHD.
     */
    private val ALWAYS_DECODABLE = setOf("aac", "ac3", "eac3", "mp3", "opus", "vorbis", "flac", "pcm", "alac")

    /** The Android MIME type for an mpv/FFmpeg audio codec name, or null when Media3 has none (MLP). */
    fun mimeFor(codec: String): String? = when {
        codec.startsWith("eac3") || codec.startsWith("e-ac-3") -> MimeTypes.AUDIO_E_AC3
        codec.startsWith("ac3") || codec.startsWith("ac-3") -> MimeTypes.AUDIO_AC3
        codec.startsWith("dts") || codec.startsWith("dca") -> MimeTypes.AUDIO_DTS
        codec.startsWith("truehd") -> MimeTypes.AUDIO_TRUEHD
        codec.startsWith("mp2") -> MimeTypes.AUDIO_MPEG_L2
        codec.startsWith("mp3") -> MimeTypes.AUDIO_MPEG
        codec.startsWith("aac") -> MimeTypes.AUDIO_AAC
        codec.startsWith("opus") -> MimeTypes.AUDIO_OPUS
        codec.startsWith("vorbis") -> MimeTypes.AUDIO_VORBIS
        codec.startsWith("flac") -> MimeTypes.AUDIO_FLAC
        codec.startsWith("alac") -> MimeTypes.AUDIO_ALAC
        else -> null
    }

    /**
     * Can ExoPlayer play audio mpv reports as [codec] (lower case)? Through a device decoder, FFmpeg, or
     * bitstreamed to a receiver that takes it — the last only when the caller allows passthrough at all.
     */
    fun verdict(
        codec: String,
        deviceDecoder: (String) -> Boolean,
        ffmpeg: (String) -> Boolean,
        passthrough: (String) -> Boolean,
    ): Verdict {
        if (ALWAYS_DECODABLE.any { codec.startsWith(it) }) return Verdict.ALLOWLIST
        val mime = mimeFor(codec) ?: return Verdict.UNSUPPORTED
        return when {
            deviceDecoder(mime) -> Verdict.DEVICE_DECODER
            ffmpeg(mime) -> Verdict.FFMPEG
            passthrough(mime) -> Verdict.PASSTHROUGH
            else -> Verdict.UNSUPPORTED
        }
    }

    /**
     * Some audio track ExoPlayer can play. Exceeding the decoder's *advertised* channels, rate or profile
     * counts: Media3's selector picks and plays such a track anyway, so treating it as unsupported only
     * handed working channels to mpv.
     */
    fun anyPlayableAudio(tracks: Tracks): Boolean = tracks.groups.any { g ->
        g.type == C.TRACK_TYPE_AUDIO && (0 until g.length).any { g.isTrackSupported(it, true) }
    }

    /**
     * A manually overridden audio track is selected even though this ExoPlayer cannot render it, while
     * another advertised audio track is playable.
     *
     * This is deliberately narrower than [anyPlayableAudio]: the latter answers whether the item can be
     * played at all, while this catches the silent remembered-language/manual-override case. Media3's
     * automatic selector avoids an unsupported track by itself; an explicit override is the path that can
     * pin one anyway. The owning engine clears only that audio override and lets Media3 choose the safe
     * sibling, preserving video quality, subtitles and every other preference.
     */
    fun selectedAudioUnsupportedWithPlayableAlternative(tracks: Tracks): Boolean {
        var selectedUnsupported = false
        var playable = false
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (index in 0 until group.length) {
                val supported = group.isTrackSupported(index, true)
                if (supported) playable = true
                if (group.isTrackSelected(index) && !supported) selectedUnsupported = true
            }
        }
        return selectedUnsupported && playable
    }
}
