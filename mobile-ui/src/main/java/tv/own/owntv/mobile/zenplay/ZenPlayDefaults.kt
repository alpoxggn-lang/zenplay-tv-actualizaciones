package tv.own.owntv.mobile.zenplay

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
    const val METADATA_LANGUAGE = "es-MX"
    const val AUDIO_LANGUAGE = "spa"

    fun start(context: Context, scope: CoroutineScope, settings: SettingsRepository) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        scope.launch {
            runCatching {
                if (!prefs.getBoolean(KEY_VOD_EXO, false)) {
                    settings.setVodEnginePreference(EnginePreference.EXO_FIRST)
                    prefs.edit().putBoolean(KEY_VOD_EXO, true).apply()
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
                if (prefs.getBoolean(key, false)) return@collect
                runCatching {
                    // Only fill in a profile that has not chosen a language of its own.
                    if (settings.preferredAudioLang.first().isBlank()) {
                        settings.setPreferredAudioLang(AUDIO_LANGUAGE)
                    }
                    prefs.edit().putBoolean(key, true).apply()
                }
            }
        }
    }
}
