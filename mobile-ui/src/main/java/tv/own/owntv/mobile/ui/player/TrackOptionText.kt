package tv.own.owntv.mobile.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import tv.own.owntv.mobile.R
import tv.own.owntv.player.TrackOption
import tv.own.owntv.player.TrackSemanticRole
import tv.own.owntv.player.localizedDisplayLabel
import java.util.Locale

/**
 * The final wording of a track row. The engine hands out raw labels, language codes and ordinals and
 * nothing else — a translated word stored in engine state would be the wrong word after a language
 * change — so a nameless track becomes "English" or "Subtitle 2" here and only here.
 */
@Composable
fun TrackOption.displayLabel(): String {
    val resources = LocalResources.current
    val configuration = resources.configuration
    val locale = if (configuration.locales.isEmpty) Locale.ENGLISH else configuration.locales[0]
    val fallback = when (labelKind) {
        tv.own.owntv.player.TrackLabelKind.AUDIO -> stringResource(R.string.player_audio_track_number, displayNumber())
        tv.own.owntv.player.TrackLabelKind.SUBTITLE -> stringResource(R.string.player_subtitle_track_number, displayNumber())
    }
    val roleLabels = mapOf(
        TrackSemanticRole.COMMENTARY to stringResource(R.string.player_track_role_commentary),
        TrackSemanticRole.AUDIO_DESCRIPTION to stringResource(R.string.player_track_role_audio_description),
        TrackSemanticRole.CLOSED_CAPTIONS to stringResource(R.string.player_track_role_closed_captions),
        TrackSemanticRole.SDH to stringResource(R.string.player_subtitles_sdh),
        TrackSemanticRole.DUBBED to stringResource(R.string.player_track_role_dubbed),
        TrackSemanticRole.SIGN_LANGUAGE to stringResource(R.string.player_track_role_sign_language),
        TrackSemanticRole.FORCED to stringResource(R.string.player_track_role_forced),
    )
    return localizedDisplayLabel(
        locale = locale,
        fallback = fallback,
        separator = stringResource(R.string.player_metadata_separator),
        roleLabel = roleLabels::getValue,
        channelCountLabel = { count -> resources.getString(R.string.player_track_channels, count) },
    )
}

private fun TrackOption.displayNumber(): Int = (typeIndex.takeIf { it >= 0 } ?: mpvId) + 1
