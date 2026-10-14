package tv.own.owntv.zenplay

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.settings.SettingsRepository

/**
 * ZenPlay's own defaults, each applied once so a later change in Settings is respected:
 *
 * - Films and episodes start on ExoPlayer (the engine live TV already plays through), with mpv as the
 *   automatic fallback — an mpv-first start sat on an endless spinner on some devices.
 * - Spanish first: titles, synopses and artwork text in Spanish (Mexico), and the Spanish audio track
 *   chosen when a film or episode carries several. The audio language is per profile, so it is set
 *   for every profile the first time it is used.
 */
object ZenPlayDefaults {
    private const val PREFS = "zenplay_defaults"
    private const val KEY_VOD_EXO = "vod_exo_first_v1"
    private const val KEY_METADATA_ES = "metadata_es_v1"
    private const val KEY_AUDIO_ES_PREFIX = "audio_es_v1_"
    private const val KEY_SUBTITLE_ES_PREFIX = "subtitle_es_v1_"
    const val METADATA_LANGUAGE = "es-MX"
    const val AUDIO_LANGUAGE = "spa"
    const val SUBTITLE_LANGUAGE = "spa"

    fun start(context: Context, scope: CoroutineScope, settings: SettingsRepository) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        scope.launch {
            runCatching {
                if (!prefs.getBoolean(KEY_VOD_EXO, false)) {
                    settings.setVodEnginePreference(EnginePreference.EXO_FIRST)
                    prefs.edit().putBoolean(KEY_VOD_EXO, true).apply()
                }
                // The player's "Grabar" button is on from the start.
                if (!prefs.getBoolean("record_watching_v1", false)) {
                    settings.setRecordWhatImWatching(true)
                    prefs.edit().putBoolean("record_watching_v1", true).apply()
                }
                if (!prefs.getBoolean(KEY_METADATA_ES, false)) {
                    settings.setMetadataLanguage(METADATA_LANGUAGE)
                    prefs.edit().putBoolean(KEY_METADATA_ES, true).apply()
                }
            }
        }
        scope.launch {
            settings.activeProfileId.distinctUntilChanged().collect { pid ->
                if (pid < 0) return@collect
                val key = KEY_AUDIO_ES_PREFIX + pid
                runCatching {
                    // Only fill in a profile that has not chosen a language of its own.
                    if (!prefs.getBoolean(key, false) && settings.preferredAudioLang.first().isBlank()) {
                        settings.setPreferredAudioLang(AUDIO_LANGUAGE)
                    }
                    prefs.edit().putBoolean(key, true).apply()

                    // Prefer Spanish subtitles when the stream provides them. A remembered manual
                    // choice (including subtitles off) still takes precedence during playback.
                    val subtitleKey = KEY_SUBTITLE_ES_PREFIX + pid
                    if (!prefs.getBoolean(subtitleKey, false) && settings.preferredSubLang.first().isBlank()) {
                        settings.setPreferredSubLang(SUBTITLE_LANGUAGE)
                    }
                    prefs.edit().putBoolean(subtitleKey, true).apply()
                }
            }
        }
    }
}
