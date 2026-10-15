package tv.own.owntv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build

/**
 * mpv's multichannel `audio-channels` list, taken from what the audio output itself says it plays.
 *
 * `auto-safe` cannot do this on Android: mpv's AudioTrack output ignores the "safe" part and always
 * offers up to 7.1, so the output's PCM channel masks are used as the real limit instead.
 */
internal object MpvAudioChannels {

    /** What mpv got before: used whenever the output does not say what it takes. */
    const val UNKNOWN = "auto-safe"

    /** mpv's list for an output whose PCM takes [counts] channels; [UNKNOWN] when it reported none. */
    fun listFor(counts: Set<Int>): String {
        if (counts.isEmpty()) return UNKNOWN
        return listOfNotNull("stereo", "5.1".takeIf { 6 in counts }, "7.1".takeIf { 8 in counts }).joinToString(",")
    }

    /** The PCM channel counts of the active media output; empty when Android cannot report them. */
    fun outputPcmChannelCounts(context: Context): Set<Int> = runCatching {
        val am = context.getSystemService(AudioManager::class.java)
        val device = if (Build.VERSION.SDK_INT >= 33) {
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            am.getAudioDevicesForAttributes(media).firstOrNull()
        } else {
            // No routing query before Android 13: only an HDMI output is a known destination.
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in HDMI_TYPES }
        } ?: return@runCatching emptySet()
        if (Build.VERSION.SDK_INT >= 31) {
            device.audioProfiles.filter { it.format == AudioFormat.ENCODING_PCM_16BIT }
                .flatMap { profile -> profile.channelMasks.map(Integer::bitCount) }.toSet()
        } else {
            device.channelCounts.toSet()
        }
    }.getOrDefault(emptySet())

    @android.annotation.SuppressLint("InlinedApi")
    private val HDMI_TYPES = setOf(
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
    )
}
