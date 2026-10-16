package tv.own.owntv.mobile.ui.player

import tv.own.owntv.mobile.ui.components.MobileIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.own.owntv.core.live.EpgNowNext
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.cast.CastRouteButton
import tv.own.owntv.mobile.ui.components.rememberClockTick
import tv.own.owntv.mobile.ui.theme.LocalAccentOnVideo
import tv.own.owntv.mobile.ui.theme.LocalMobileMotion
import tv.own.owntv.mobile.ui.theme.MobileDimens
import tv.own.owntv.mobile.ui.theme.SquircleShape
import tv.own.owntv.player.LiveProgramme
import tv.own.owntv.player.PlaybackEngine
import tv.own.owntv.player.OwnTVPlayer
import java.text.NumberFormat

/** The pickers the tool bar opens. Each one is a sheet; each one also has a gesture. */
enum class PlayerSheet {
    VOLUME, BRIGHTNESS, PLAYBACK_SETTINGS, SUBTITLES, SUBTITLE_SEARCH, AUDIO, ASPECT, QUALITY, SPEED, INFO,
    CHANNELS, CATCHUP, SLEEP_TIMER,
}

/**
 * Everything drawn over the picture: the title dock, the transport capsule, the instrument and the
 * tools.
 *
 * It is one block that fades in and out together, so the picture is never half-covered, and every
 * control here is the visible twin of a gesture — the rule from the design is that no function is
 * reachable *only* by swiping.
 */
@Composable
fun PlayerControls(
    player: OwnTVPlayer,
    /**
     * The engine actually holding the stream, which on live is now either one (L2).
     *
     * Everything the bar READS comes from here; `player` stays only for the two things that are mpv's
     * alone. Without this the HUD read a stopped mpv while ExoPlayer played: the title line said MPV
     * beside a button saying EXO, and the play/pause glyph showed "paused" over a moving picture.
     */
    engine: PlaybackEngine,
    title: String,
    subtitle: String?,
    logoUrl: String?,
    visible: Boolean,
    isLive: Boolean,
    offsetSec: Int?,
    archiveWindowSec: Int,
    /** Now and Next for the channel playing, or null when its guide has nothing. */
    epg: EpgNowNext?,
    /** The wall-clock instant on screen while an archive plays; null at the live edge. */
    watchingWallMs: Long?,
    /** The channel's guide window, as the live timeline's boundary ticks. */
    timelineProgrammes: List<LiveProgramme>,
    /** The provider's own number for the channel, or null when the user has numbers turned off. */
    channelNumber: Int?,
    onBack: () -> Unit,
    onScrubLive: (deltaSec: Int) -> Unit,
    /** N4 — the saved copy's holes, for the live bar. */
    liveGaps: () -> List<LongRange> = { emptyList() },
    /** One Live-rewind-step back (false) or forward (true); null on a channel that cannot be rewound. */
    onSkipLive: ((forward: Boolean) -> Unit)? = null,
    onOpenSheet: (PlayerSheet) -> Unit,
    /** Whether what is playing is one of this profile's favourites. */
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    /** Live-only entry to the grid; null on hardware that cannot sustain two decoders. */
    onMultiview: (() -> Unit)? = null,
    /** Touch-only system floating window; null on TV, Fire TV and devices without platform PiP. */
    onPictureInPicture: (() -> Unit)? = null,
    /** Live-only glass channel browser. */
    onChannels: (() -> Unit)? = null,
    /** Live-only -1 zap within the category currently playing. */
    onPreviousChannel: (() -> Unit)? = null,
    /** Live-only +1 zap within the category currently playing. */
    onNextChannel: (() -> Unit)? = null,
    /** Episode-only route back to this series' season and episode list. */
    onEpisodes: (() -> Unit)? = null,
    /** The screen-wide scrub gesture's running total, in media milliseconds, while it is happening. */
    gestureScrubMs: Long?,
    modifier: Modifier = Modifier,
) {
    // The app's own effects spring, not Material's default: with animations off it snaps, so the
    // chrome is simply there or not there.
    val fade = LocalMobileMotion.current.fast<Float>()
    val audioTrackCount by engine.audioCount.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(fade),
        exit = fadeOut(fade),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(TopScrim)
                    .systemBarsPadding()
                    .padding(horizontal = MobileDimens.GapSmall, vertical = MobileDimens.GapTiny),
            ) {
                PlayerDock {
                    TopRow(
                        player = engine,
                        title = title,
                        subtitle = subtitle,
                        logoUrl = logoUrl,
                        channelNumber = channelNumber,
                        showClock = isLive || watchingWallMs != null,
                        watchingWallMs = watchingWallMs,
                        onBack = onBack,
                    )
                    // Under the identity rather than beside it: the guide line is the longest text on
                    // the dock, and a phone has no width to spare on the row the title is already in.
                    MobileNowNextCard(
                        epg = epg,
                        atMs = watchingWallMs,
                        modifier = Modifier.padding(top = MobileDimens.GapTiny),
                    )
                }
            }

            TransportRow(
                player = engine,
                isLive = isLive,
                onSkipLive = onSkipLive,
                // Forward only while behind live, as on the television: at the edge there is nothing ahead.
                behindLive = (offsetSec ?: 0) > 1,
                modifier = Modifier.align(Alignment.Center),
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(BottomScrim)
                    .systemBarsPadding()
                    .padding(horizontal = MobileDimens.GapSmall, vertical = MobileDimens.GapTiny),
            ) {
                PlayerDock {
                    if (isLive) {
                        LiveChannelActions(
                            onChannels = onChannels,
                            onPreviousChannel = onPreviousChannel,
                            onNextChannel = onNextChannel,
                        )
                        LiveBar(
                            offsetSec = offsetSec,
                            archiveWindowSec = archiveWindowSec,
                            programmes = timelineProgrammes,
                            onScrubLive = onScrubLive,
                            gaps = liveGaps,
                        )
                    } else {
                        SeekBar(player, gestureScrubMs)
                    }
                    ToolBar(
                        isLive = isLive,
                        onOpenSheet = onOpenSheet,
                        favorite = favorite,
                        onToggleFavorite = onToggleFavorite,
                        onMultiview = onMultiview,
                        onPictureInPicture = onPictureInPicture,
                        onAudio = if (audioTrackCount > 1) {
                            { onOpenSheet(PlayerSheet.AUDIO) }
                        } else {
                            null
                        },
                        onEpisodes = onEpisodes,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopRow(
    player: PlaybackEngine,
    title: String,
    subtitle: String?,
    logoUrl: String?,
    channelNumber: Int?,
    showClock: Boolean,
    watchingWallMs: Long?,
    onBack: () -> Unit,
) {
    val engine by player.engineChip.collectAsStateWithLifecycle()
    val resolution by player.videoRes.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(MobileIcons.ArrowBack, stringResource(R.string.common_back), tint = Color.White)
        }
        ChannelLogo(logoUrl = logoUrl, title = title, number = channelNumber)
        Column(Modifier.weight(1f).padding(start = MobileDimens.GapSmall)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(subtitle, engine, resolution)
                .joinToString(stringResource(R.string.player_metadata_separator))
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnVideo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showClock) {
            MobilePlayerClock(
                watchingMs = watchingWallMs,
                modifier = Modifier.padding(horizontal = MobileDimens.GapTiny),
            )
        }
        // Always light: this one sits over the picture, and the picture is never a light surface.
        // It shows itself only when a receiver is actually within reach — the SDK's own behaviour,
        // and the reason the bar does not carry a permanently dead button.
        CastRouteButton(light = true)
    }
}

@Composable
private fun TransportRow(
    player: PlaybackEngine,
    isLive: Boolean,
    onSkipLive: ((forward: Boolean) -> Unit)?,
    behindLive: Boolean,
    modifier: Modifier = Modifier,
) {
    val playing by player.isPlaying.collectAsStateWithLifecycle()
    val step by player.seekStepMs.collectAsStateWithLifecycle()
    TransportCapsule(modifier) {
        if (!isLive) {
            RoundControl(MobileIcons.FastRewind, R.string.player_skip_back) { player.seekBy(-step) }
        } else if (onSkipLive != null) {
            // Live, on a channel that can be rewound (catch-up, or its saved copy): the television's buttons.
            RoundControl(MobileIcons.FastRewind, R.string.player_skip_back) { onSkipLive(false) }
        }
        RoundControl(
            icon = if (playing) MobileIcons.Pause else MobileIcons.PlayArrow,
            labelRes = R.string.settings_remote_action_play_pause,
            size = 68.dp,
            onClick = { player.togglePlayPause() },
        )
        if (!isLive) {
            RoundControl(MobileIcons.FastForward, R.string.player_skip_forward) { player.seekBy(step) }
        } else if (onSkipLive != null && behindLive) {
            RoundControl(MobileIcons.FastForward, R.string.player_skip_forward) { onSkipLive(true) }
        }
    }
}

@Composable
private fun RoundControl(
    icon: ImageVector,
    labelRes: Int,
    // 48 dp, not the television's 44: this one is hit with a thumb.
    size: Dp = 48.dp,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size).clip(SquircleShape(size / 2)),
    ) {
        Icon(icon, stringResource(labelRes), tint = Color.White, modifier = Modifier.size(size / 2))
    }
}

/**
 * Position, duration and the scrubber, for anything with an end: a film, an episode, a replay.
 *
 * The bar is dragged either directly or by the screen-wide horizontal gesture, and both show the same
 * bubble in the same place — [gestureDeltaMs] is the gesture's running total, still unapplied.
 */
@Composable
private fun SeekBar(player: OwnTVPlayer, gestureDeltaMs: Long?) {
    val position by player.position.collectAsStateWithLifecycle()
    val duration by player.duration.collectAsStateWithLifecycle()
    val buffered by player.bufferedMs.collectAsStateWithLifecycle()
    if (duration <= 0) return
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val playedFraction = (position.toFloat() / duration).coerceIn(0f, 1f)
    val gestureFraction = gestureDeltaMs?.let {
        ((position + it).toFloat() / duration).coerceIn(0f, 1f)
    }
    val fraction = dragValue.takeIf { dragging } ?: gestureFraction ?: playedFraction
    val bufferedFraction = (buffered.toFloat() / duration).coerceIn(fraction, 1f)
    val targetMs = (fraction * duration).toLong()
    // Never the theme accent: over a picture the light theme's accent is a dark tone on a dark scene.
    val accent = LocalAccentOnVideo.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        TimeCap(formatTimestamp(targetMs), Alignment.Start)
        MobileSeekBar(
            fraction = fraction,
            bufferedFraction = bufferedFraction,
            dragging = dragging || gestureDeltaMs != null,
            accent = accent,
            // While the value is moving, say where it will land and by how much.
            bubbleTargetMs = targetMs.takeIf { dragging || gestureDeltaMs != null },
            bubbleDeltaMs = gestureDeltaMs ?: (targetMs - position).takeIf { dragging },
            // A direct tap/drag owns an absolute destination. Re-deriving a delta from the live
            // position flow races playback and used to issue the wrong Range request on release.
            onSeekTo = { player.seekTo((it * duration).toLong()) },
            onDrag = { dragging = true; dragValue = it },
            onDragEnd = {
                dragging = false
                player.seekTo((dragValue * duration).toLong())
            },
            modifier = Modifier.weight(1f).padding(horizontal = MobileDimens.GapSmall),
        )
        TimeCap(stringResource(R.string.player_time_remaining, formatTimestamp(duration - targetMs)), Alignment.End)
    }
}

/**
 * The live edge, and how far back from it the picture is.
 *
 * The timeline is programme-aware: it spans the last two hours up to now, marks where each programme
 * began, and names the one under the finger. A channel whose provider keeps no archive has nothing
 * to drag into, so it gets the badge alone rather than a bar that refuses to move.
 */
@Composable
private fun LiveBar(
    offsetSec: Int?,
    archiveWindowSec: Int,
    programmes: List<LiveProgramme>,
    onScrubLive: (Int) -> Unit,
    gaps: () -> List<LongRange>,
) {
    val liveEdgeMs by rememberClockTick()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (archiveWindowSec > 0) {
            MobileLiveTimeline(
                offsetSec = offsetSec ?: 0,
                programmes = programmes,
                // Now, and it keeps being now. Read once per composition this stood still, so a
                // player left open drew its programme ticks against the moment the controls last
                // appeared rather than against the live edge.
                liveEdgeMs = liveEdgeMs,
                accent = LocalAccentOnVideo.current,
                onScrub = onScrubLive,
                modifier = Modifier.weight(1f).padding(end = MobileDimens.GapSmall),
                gaps = gaps,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        LiveStateBadge(offsetSec)
    }
}

/**
 * The two live-TV actions users need while the picture stays visible.
 *
 * They intentionally sit above the timeline instead of joining the icon strip: on a phone the
 * strip used to contain fourteen indistinguishable glyphs. The short labels fit a handset and the
 * full labels use the extra width of a tablet. The translucent plates sit inside the player's glass
 * dock, so opening either action never replaces the stream behind it.
 */
@Composable
private fun LiveChannelActions(
    onChannels: (() -> Unit)?,
    onPreviousChannel: (() -> Unit)?,
    onNextChannel: (() -> Unit)?,
) {
    if (onChannels == null && onPreviousChannel == null && onNextChannel == null) return
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    Row(
        Modifier.fillMaxWidth().padding(bottom = MobileDimens.GapTiny),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveActionButton(
            icon = MobileIcons.SkipPrevious,
            text = stringResource(
                if (tablet) R.string.zenplay_player_previous_channel else R.string.zenplay_player_previous_short,
            ),
            onClick = onPreviousChannel,
        )
        LiveActionButton(
            icon = MobileIcons.FormatListBulleted,
            text = stringResource(
                if (tablet) R.string.zenplay_player_channel_list else R.string.zenplay_player_channels_short,
            ),
            onClick = onChannels,
        )
        LiveActionButton(
            icon = MobileIcons.SkipNext,
            text = stringResource(
                if (tablet) R.string.zenplay_player_next_channel else R.string.zenplay_player_next_short,
            ),
            onClick = onNextChannel,
        )
    }
}

@Composable
private fun LiveActionButton(icon: ImageVector, text: String, onClick: (() -> Unit)?) {
    TextButton(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = Modifier
            .clip(SquircleShape(18.dp))
            .background(Color.Black.copy(alpha = 0.30f)),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = text, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * The tools: one button per picker, scrollable because a phone in portrait is narrow.
 *
 * Each is a 48 dp square showing **its glyph and nothing else**. No control draws its name: the bar
 * sits over the picture on a phone-width screen and there is very little room, so a button that grew
 * sideways into a word pushed its neighbours off the end of the row. Every name is still published
 * to accessibility services — see `CtrlButton`.
 *
 * The order is core's `PlayerControl`, shared with the television (Feature H).
 */
@Composable
private fun ToolBar(
    isLive: Boolean,
    onOpenSheet: (PlayerSheet) -> Unit,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onMultiview: (() -> Unit)? = null,
    onPictureInPicture: (() -> Unit)? = null,
    onAudio: (() -> Unit)? = null,
    onEpisodes: (() -> Unit)? = null,
) {
    // Only direct watching actions live here. Brightness remains a gesture and all track/display
    // choices live under one Settings sheet; recording, sleep, sound-only, engine switching and
    // diagnostics do not belong on the touch player's primary bar. System PiP is kept distinct from
    // the old in-app mini-player: this button really leaves ZenPlay in Android's floating window.
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CtrlButton(
            MobileIcons.VolumeUp,
            stringResource(R.string.player_tool_volume),
            { onOpenSheet(PlayerSheet.VOLUME) },
        )
        CtrlButton(
            MobileIcons.Tune,
            stringResource(R.string.zenplay_player_playback_settings),
            { onOpenSheet(PlayerSheet.PLAYBACK_SETTINGS) },
        )
        if (onAudio != null) {
            CtrlButton(
                MobileIcons.Audiotrack,
                stringResource(R.string.player_tool_audio),
                onAudio,
            )
        }
        if (onEpisodes != null) {
            CtrlButton(
                MobileIcons.FormatListBulleted,
                stringResource(R.string.content_episodes),
                onEpisodes,
            )
        }
        if (onPictureInPicture != null) {
            CtrlButton(
                MobileIcons.PictureInPictureAlt,
                stringResource(R.string.player_enter_picture_in_picture),
                onPictureInPicture,
            )
        }
        CtrlButton(
            icon = if (favorite) MobileIcons.Favorite else MobileIcons.FavoriteBorder,
            label = stringResource(if (favorite) R.string.content_favorited else R.string.content_favorite),
            onClick = onToggleFavorite,
            active = favorite,
        )
        if (isLive && onMultiview != null) {
            CtrlButton(MobileIcons.GridView, stringResource(R.string.multiview_button), onMultiview)
        }
    }
}

/** "Normal" at 1x, "1.5x" otherwise — the same wording the television uses. */
@Composable
internal fun formatSpeed(speed: Double): String {
    if (speed == 1.0) return stringResource(R.string.player_speed_normal_short)
    val locale = LocalConfiguration.current.locales[0]
    val number = remember(speed, locale) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 2
        }.format(speed)
    }
    return stringResource(R.string.player_speed, number)
}

/** 0:42 / 23:45 / 1:23:45 — the app's one duration format, the same one the television uses. */
@Composable
fun formatTimestamp(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        stringResource(R.string.common_timestamp_hours, h, m, s)
    } else {
        stringResource(R.string.common_timestamp_minutes, m, s)
    }
}
