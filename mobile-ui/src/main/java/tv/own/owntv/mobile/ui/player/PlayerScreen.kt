package tv.own.owntv.mobile.ui.player

import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.mobile.ui.components.CategoryPickerSheet
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.mobile.MainActivity
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.cast.CastController
import tv.own.owntv.mobile.playback.PipController
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.subtitles.SubtitleController
import tv.own.owntv.mobile.ui.screens.ContentActions
import tv.own.owntv.mobile.ui.setup.copyPickedFile
import tv.own.owntv.mobile.ui.theme.LocalAccentOnVideo
import tv.own.owntv.mobile.ui.theme.glassDialogWindow
import tv.own.owntv.mobile.ui.screens.library.VodTuner
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.player.ErrorInfo
import tv.own.owntv.player.PlaybackFailure
import tv.own.owntv.player.ZoomMode
import tv.own.owntv.player.describe
import tv.own.owntv.player.displayText
import tv.own.owntv.player.titleRes

private const val CONTROLS_TIMEOUT_MS = 3_000L
private const val HUD_TIMEOUT_MS = 900L

/** How long a confirmation line stays over the picture. */
private const val TOAST_MS = 1_800L
private const val SPEED_HOLD = 2.0

/** How many notches the brightness slide is divided into for its haptic tick. */
private const val NOTCHES = 20

/**
 * The picture, full screen, with everything on top of it.
 *
 * It is a route rather than its own activity, so the stream it shows is the one [LiveTuner] already
 * has running — going full screen and coming back out is a change of view, never a restart. Back and
 * the swipe down both leave it playing, which is what puts it into the mini player.
 *
 * The screen owns landscape for its complete lifetime and releases it only when the route leaves.
 * Releasing as soon as the first landscape frame arrived made a portrait-held phone immediately
 * rotate back, remount the surface and repeat that cycle.
 */
@Composable
fun PlayerScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    tuner: LiveTuner = koinInject(),
    vodTuner: VodTuner = koinInject(),
    pip: PipController = koinInject(),
    settings: SettingsRepository = koinInject(),
    actions: ContentActions = koinInject(),
    subtitles: SubtitleController = koinInject(),
    cast: CastController = koinInject(),
) {
    val player = tuner.player

    // The engine actually holding the stream. For VOD and for live-on-mpv this is a thin delegate
    // over `player`, so nothing changes there; on live-on-ExoPlayer it is the other engine.
    val activeEngine by tuner.activeEngine.collectAsStateWithLifecycle()
    val liveOnExo by tuner.liveOnExo.collectAsStateWithLifecycle()
    val vodOnExo by player.exoActiveState.collectAsStateWithLifecycle()
    val canToggleVodEngine by player.canToggleVodEngine.collectAsStateWithLifecycle()
    val castEngine by cast.engine.collectAsStateWithLifecycle()
    val castDevice by cast.deviceName.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val inPip by pip.inPip.collectAsStateWithLifecycle()
    val manualPipAvailable by pip.manualEntryAvailable.collectAsStateWithLifecycle()
    val channel by tuner.channel.collectAsStateWithLifecycle()
    val autoFrameRate by settings.autoFrameRate.collectAsStateWithLifecycle(initialValue = false)
    val film by vodTuner.playing.collectAsStateWithLifecycle()
    val nowNext by tuner.nowNext.collectAsStateWithLifecycle()
    val siblings by tuner.siblings.collectAsStateWithLifecycle()
    val offsetSec by tuner.offsetSec.collectAsStateWithLifecycle()
    val watchingWallMs by tuner.watchingWallMs.collectAsStateWithLifecycle()
    val timelineProgrammes by tuner.timelineProgrammes.collectAsStateWithLifecycle()
    // The same switch the Live TV list reads: off hides every number in the app, this one included.
    val showChannelNumbers by settings.directTune.collectAsStateWithLifecycle(true)
    // All four describe the stream that is playing, so they come from the engine that HAS it. Read
    // from `player` they described a stopped mpv while ExoPlayer held a live channel: `isPlaying`
    // was false for ever, which left the spinner turning over a moving picture and stopped the
    // controls from ever auto-hiding, because both are gated on it.
    val duration by activeEngine.duration.collectAsStateWithLifecycle()
    val error by activeEngine.error.collectAsStateWithLifecycle()
    val errorInfo by activeEngine.errorInfo.collectAsStateWithLifecycle()
    val isPlaying by activeEngine.isPlaying.collectAsStateWithLifecycle()
    // Waiting for the stream is NOT the same as not playing: a user who pressed pause is also "not
    // playing", and the spinner over a deliberately paused picture reads as a stall. The television
    // has always drawn this from `buffering`; so does this now.
    val buffering by activeEngine.buffering.collectAsStateWithLifecycle()
    val audioOnly by activeEngine.audioOnly.collectAsStateWithLifecycle()
    // Only the stream that never had a picture — a radio channel — reaches this screen without one.
    // The user's own sound-only choice cannot, see below.
    val audioOnlyMedia by activeEngine.audioOnlyMedia.collectAsStateWithLifecycle()
    // Held as the State, not its value: only the countdown below reads it, so the tick every second
    // does not recompose the whole screen.
    val positionState = activeEngine.position.collectAsStateWithLifecycle()
    val nav by player.nav.collectAsStateWithLifecycle()
    val nextUpTitle by player.nextUpTitle.collectAsStateWithLifecycle()
    // The sleep timer's "End of episode" stops there, so there is no next to count down to.
    val stopsAtItemEnd by player.stopsAtItemEnd.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Multiview is a first-class live control. The previous settings gate left it invisible on a
    // fresh install, which made the implemented feature look absent. The requested tile count is
    // still only a preference: the compatibility edition and low-memory devices impose a lower,
    // safe decoder ceiling here before any engines are opened.
    val multiviewTiles by settings.multiviewTiles.collectAsStateWithLifecycle(
        tv.own.owntv.core.live.DEFAULT_MULTIVIEW_TILES,
    )
    val lowRam = remember(context) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    }
    val legacyBuild = CoreBuildInfo.releaseAssetName.contains("legacy", ignoreCase = true)
    val multiviewTileLimit = when {
        lowRam -> 1
        legacyBuild -> 2
        else -> 4
    }
    val effectiveMultiviewTiles = multiviewTiles.coerceIn(1, multiviewTileLimit)
    val enginePool = koinInject<tv.own.owntv.player.LiveEnginePool>()
    val streamRegistry = koinInject<tv.own.owntv.core.live.OpenStreamRegistry>()
    var multiview by remember {
        mutableStateOf<tv.own.owntv.mobile.ui.screens.multiview.MobileMultiviewState?>(null)
    }
    var multiviewPickFor by remember { mutableStateOf<Int?>(null) }
    // Which category the Multiview picker is inside, and what it holds. Null = showing categories.
    var multiviewCategory by remember { mutableStateOf<Long?>(null) }
    var multiviewChannels by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    var multiviewCategories by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    // The player's channel browser opens in the current category. Its explicit "← Categories"
    // row moves up one level without closing playback; physical Back follows the same two-step rule.
    var playerCategory by remember { mutableStateOf<Long?>(null) }
    var playerChannels by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    var playerCategories by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var playerCategoriesLoaded by remember { mutableStateOf(false) }
    var playerShowingCategories by remember { mutableStateOf(false) }
    // Loaded when the picker opens, not when the player does: it is a database read nobody watching
    // a channel has asked for.
    LaunchedEffect(multiviewPickFor) {
        if (multiviewPickFor != null && multiviewCategories.isEmpty()) {
            multiviewCategories = tuner.liveCategoriesForPicker()
        }
        if (multiviewPickFor == null) multiviewCategory = null
    }
    // Channels kept from the Live list (B5's second entry point). Playing any channel is what says
    // "now": the grid opens with them already in it, and the selection is spent.
    val multiviewSelection by tuner.multiviewSelection.collectAsStateWithLifecycle()
    LaunchedEffect(channel?.id, multiviewSelection.size, effectiveMultiviewTiles) {
        val current = channel
        if (effectiveMultiviewTiles > 1 && multiview == null && multiviewSelection.isNotEmpty() && current != null) {
            val kept = multiviewSelection
            tuner.clearMultiviewSelection()
            // BOTH engines, exactly as the HUD button does. This is the *other* way into the grid —
            // the one the owner actually uses — and it stopped only mpv, so a channel playing on the
            // ExoPlayer engine carried on underneath: a second sound, and a connection spent on a
            // stream nobody could see.
            player.stop()
            tuner.exoEngine.stop()
            val grid = tv.own.owntv.mobile.ui.screens.multiview.MobileMultiviewState(
                pool = enginePool,
                registry = streamRegistry,
                tuner = tuner,
                scope = scope,
                maxTiles = effectiveMultiviewTiles,
            )
            var tile = 0
            grid.fill(tile++, current)
            // Channels kept by name size the grid themselves, up to the ceiling: the user already
            // said how many they wanted.
            kept.filter { it.id != current.id }.forEach { keptChannel ->
                if (tile >= grid.tiles.size) grid.addTile()
                if (tile < grid.tiles.size) grid.fill(tile++, keptChannel)
            }
            multiview = grid
        }
    }

    // What Favourite would act on: the channel, the film, or — for an episode — the show it is from,
    // because an episode is never favourited on its own.
    val favoriteType = channel?.let { MediaType.LIVE }
        ?: film?.let { if (it.mediaType == MediaType.EPISODE) MediaType.SERIES else it.mediaType }
    val favoriteId = channel?.id ?: film?.let { it.seriesId ?: it.itemId }
    val favoriteIds by remember(favoriteType) {
        favoriteType?.let { actions.favoriteIds(it) } ?: flowOf(emptySet())
    }.collectAsStateWithLifecycle(emptySet())

    // A line over the picture: the engine swap, the report, a subtitle file that would not load.
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(TOAST_MS); toast = null } }
    val subtitleFailed = stringResource(R.string.content_subtitle_load_failed)
    val switchedToExo = stringResource(R.string.player_switch_exo)
    val switchedToMpv = stringResource(R.string.player_switch_mpv)

    // A subtitle file already on the phone. Copied into the cache first: the player opens it by path,
    // and a document-provider Uri is not one.
    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = copyPickedFile(context, uri, java.io.File(context.cacheDir, "subtitles"))
            if (file == null || runCatching { subtitles.applyLocal(file) }.isFailure) toast = subtitleFailed
        }
    }

    // **There is no full-screen sound-only mode.** Full screen is the one place in the app that
    // exists to show the picture, so arriving here turns it back on, however the session came to be
    // without it. Closing the floating window switches it off deliberately, and tapping the
    // quick-panel controls afterwards used to land here on a black rectangle with sound; expanding a
    // sound-only mini player did the same. Sound only lives in the docked bar, which is where its
    // own button sends it.
    LaunchedEffect(activeEngine) { activeEngine.exitAudioOnly() }
    // How far a value moves per centimetre of finger. 100 is the untouched behaviour.
    val gestureSensitivity by settings.gestureSensitivityPct.collectAsStateWithLifecycle(100)

    // A replay has an end and therefore a seek bar; the live edge and a rewind into the archive have
    // neither, and get the live bar instead.
    //
    // Asked of the tuner, never of the duration: a live stream reports whatever its rolling window
    // is, and providers that report a plausible twenty-odd hours had their channels classed as
    // recordings — a scrub bar offering 25 hours of live television, and no clock, Now/Next, live
    // badge, Go Live pill or programme timeline anywhere.
    val replaying by tuner.replaying.collectAsStateWithLifecycle()
    val isLive = channel != null && !replaying

    // "Go back to…", for a live channel whose provider keeps an archive — or its own saved copy (N4),
    // which exists only once the tune has started saving. Recomputed per channel, since the depth of
    // the archive is the channel's, not the playlist's.
    val hasLocalCopy by tuner.hasLocalCopy.collectAsStateWithLifecycle()
    val catchup = remember(channel?.id, hasLocalCopy) {
        tuner.jumpOptions().takeIf { it.isNotEmpty() }?.let { offsets ->
            CatchupOptions(offsets, tuner.archiveWindowSec(), tuner::jumpBackTo)
        }
    }

    // N4 — back on a channel whose saved copy was kept: continue from there, or stay live.
    val timeshiftResumeAt by tuner.timeshiftResumeAt.collectAsStateWithLifecycle()
    if (timeshiftResumeAt != null) {
        AlertDialog(
            modifier = Modifier.glassDialogWindow(),
            onDismissRequest = tuner::dismissTimeshiftResume,
            title = { Text(stringResource(R.string.player_timeshift_resume_title)) },
            text = { Text(stringResource(R.string.player_timeshift_resume_message)) },
            confirmButton = {
                TextButton(onClick = tuner::resumeTimeshift) { Text(stringResource(R.string.common_resume)) }
            },
            dismissButton = {
                TextButton(onClick = tuner::dismissTimeshiftResume) { Text(stringResource(R.string.player_go_live)) }
            },
        )
    }

    // The automatic advance, made visible. The engine starts the next episode eight seconds before the
    // end, so the card counts down to that, not to the duration.
    var autoNextDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(nextUpTitle, nav.hasNext) { autoNextDismissed = false }
    // Derived, so the screen recomposes only when the countdown's second changes, not on every tick.
    val secondsToAdvance by remember(isLive, duration, positionState) {
        derivedStateOf {
            val msToAdvance = if (!isLive && duration > 0L) (duration - 8_000L) - positionState.value else Long.MAX_VALUE
            if (msToAdvance in 0L..30_000L) ((msToAdvance + 999L) / 1000L).toInt().coerceIn(0, 30) else null
        }
    }
    val showNextCard = !isLive && error == null && nav.hasNext && nextUpTitle != null && !stopsAtItemEnd &&
        secondsToAdvance != null && !autoNextDismissed

    var controlsVisible by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    // Enter at the channel's current category, not at a flat cross-provider category index. A
    // channel without a category is the one case that begins at Categories.
    LaunchedEffect(sheet, channel?.categoryId) {
        if (sheet == PlayerSheet.CHANNELS) {
            val currentCategory = channel?.categoryId
            playerCategory = currentCategory
            playerShowingCategories = currentCategory == null
            if (currentCategory != null) {
                playerChannels = tuner.channelsInCategoryForPicker(currentCategory)
            } else if (!playerCategoriesLoaded) {
                playerCategories = tuner.liveCategoriesForPicker()
                playerCategoriesLoaded = true
            }
        } else {
            playerCategory = null
            playerShowingCategories = false
        }
    }
    var brightness by remember { mutableFloatStateOf(0.5f) }
    var hud by remember { mutableStateOf<GestureFeedback?>(null) }
    // Which *showing* this is, counted up on every one. Two forward double taps at the same seek step
    // produce an equal `GestureFeedback.Skip`, and Compose compares state by value: the second tap was
    // therefore not a change at all, so the hide timer below — keyed on the value — never restarted.
    // Worse, when a tap landed in the same frame as the timer's own `null`, the composition only ever
    // saw the value it started with, the key never moved, and the finished timer was never relaunched:
    // the badge then sat on the picture until some *different* gesture replaced it. Counting makes
    // every showing distinct, so every one of them arms its own timer.
    var hudShowing by remember { mutableIntStateOf(0) }
    fun showHud(feedback: GestureFeedback?) {
        hud = feedback
        hudShowing++
    }
    val haptics = LocalHapticFeedback.current
    // How far the screen-wide scrub gesture has moved so far, as a fraction of the whole, or null
    // when no such gesture is in progress. The seek bar draws it; nothing has been seeked yet.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    // Fractional carry: one flick of a thumb is many tiny deltas, and rounding each one to a whole
    // percent on its own would throw most of the movement away.
    val carry = remember { Carry() }

    // Full screen means full screen: no status bar, no navigation bar. The display is kept awake by
    // VideoStage, which knows whether there is a picture to stay awake for.
    //
    // This is also where the activity learns that the picture is on screen, which is the difference
    // between "home was pressed while watching" (Picture-in-Picture) and "home was pressed" (leave).
    DisposableEffect(activity) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        // Transient, not permanent. Left at the default, a swipe from the edge brought the clock and
        // the navigation bar back over the picture and *left them there* for the rest of the film —
        // the top bar in the middle of a scene. This way the swipe still shows them, and they leave
        // again on their own.
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        // Hiding the bars is not enough: the strip the camera sits in stays outside the window
        // unless the window is told to lay out into it, and the wallpaper shows through there.
        // The field itself only exists from Android 9, so it is read and restored inside the guard —
        // touching it on an older phone is a NoSuchFieldError, not a no-op.
        val cutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window?.attributes?.layoutInDisplayCutoutMode
        } else {
            null
        }
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    // Before Android 11 only the short edges can be drawn into, which is the phone's
                    // top in portrait — the one that matters on a notched device.
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        // Hiding once is not enough either: the keyboard in the channel-number field pulls the bars
        // back up with it, and they do not always leave again when it goes. Anything that puts them
        // back while the picture is on screen puts them away again. The insets are returned exactly
        // as they arrived — this watches, it does not consume.
        val decor = window?.decorView
        if (decor != null) {
            ViewCompat.setOnApplyWindowInsetsListener(decor) { _, applied ->
                if (applied.isVisible(WindowInsetsCompat.Type.systemBars())) {
                    insets?.hide(WindowInsetsCompat.Type.systemBars())
                }
                applied
            }
        }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        pip.playerOnScreen.value = true
        onDispose {
            pip.playerOnScreen.value = false
            if (decor != null) ViewCompat.setOnApplyWindowInsetsListener(decor, null)
            insets?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    if (cutoutMode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = cutoutMode
                    }
                }
            }
        }
    }
    fun setBrightness(value: Float) {
        brightness = value.coerceIn(0.01f, 1f)
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = brightness }
    }

    /** A short tick when a gesture crosses a step: a volume notch, a skip firing, fast play engaging. */
    fun tick() {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // Every readout is a burst except fast play, which lasts exactly as long as the finger is down.
    LaunchedEffect(hudShowing) {
        if (hud != null && hud !is GestureFeedback.Speed) {
            delay(HUD_TIMEOUT_MS)
            showHud(null)
        }
    }
    // The controls go away on their own, but never while a picker is open over them and never in the
    // middle of a scrub — the bar is the only thing saying where letting go would land.
    val scrubbing = scrubFraction != null
    LaunchedEffect(controlsVisible, sheet, isPlaying, scrubbing) {
        if (controlsVisible && sheet == null && isPlaying && !scrubbing) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    // Back means "I am finished watching", and it ends the stream. Leaving the player *playing* is
    // what the mini-player button and the swipe down are for — both of them say so, and Back does not.
    // A stream that outlived a Back press was the app quietly holding one of the provider's allowed
    // connections open with nothing on screen to say so.
    val stopAndExit = {
        // Both tuners share OwnTVPlayer. Stopping Live first used to zero/tear down the VOD engine
        // before VodTuner's asynchronous save read its position, so Continue watching was overwritten
        // or left at the previous value. Stop exactly the owner visible on this route; VodTuner then
        // persists movie/episode progress before releasing the player.
        if (film != null) vodTuner.stop() else tuner.stop()
        onExit()
    }
    BackHandler(onBack = stopAndExit)

    // Casting replaces the screen rather than decorating it. Everything below this point drives the
    // engine on this phone — the surface, the gestures, the track pickers — and while the television
    // has the stream there is no such engine to drive.
    val remote = castEngine
    if (remote != null) {
        CastStage(
            engine = remote,
            deviceName = castDevice,
            title = channel?.name ?: film?.title.orEmpty(),
            subtitle = if (channel != null) nowNext?.now?.title else film?.subtitle,
            artworkUrl = channel?.displayLogoUrl ?: film?.posterUrl,
            onBack = stopAndExit,
            modifier = modifier,
        )
        return
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .playerGestures(
                onTap = { controlsVisible = !controlsVisible },
                onDoubleTapLeft = {
                    skip(tuner, isLive, forward = false)
                    tick()
                    showHud(GestureFeedback.Skip(forward = false, deltaMs = player.seekStepMs.value))
                },
                onDoubleTapRight = {
                    skip(tuner, isLive, forward = true)
                    tick()
                    showHud(GestureFeedback.Skip(forward = true, deltaMs = player.seekStepMs.value))
                },
                onScrub = {
                    carry.scrub += it
                    // The chrome is what shows the target, so a scrub that started on a bare picture
                    // brings the bar back rather than moving the film blind.
                    controlsVisible = true
                    scrubFraction = carry.scrub
                },
                onScrubEnd = {
                    scrubFraction = null
                    if (isLive) {
                        val window = tuner.archiveWindowSec()
                        if (window > 0) tuner.scrubLive((-carry.scrub * window).toInt())
                    } else if (duration > 0) {
                        val target = (activeEngine.position.value + (carry.scrub * duration).toLong())
                            .coerceIn(0L, duration)
                        activeEngine.seekTo(target)
                    }
                    carry.scrub = 0f
                },
                onBrightness = { delta ->
                    val before = (brightness * NOTCHES).toInt()
                    setBrightness(brightness + delta)
                    if ((brightness * NOTCHES).toInt() != before) tick()
                    showHud(GestureFeedback.Level(volume = false, percent = (brightness * 100).toInt()))
                },
                onVolume = { delta ->
                    carry.volume += delta * 150f
                    val whole = carry.volume.toInt()
                    if (whole != 0) {
                        carry.volume -= whole
                        activeEngine.adjustVolumeByUser(whole)
                        tick()
                    }
                    showHud(GestureFeedback.Level(volume = true, percent = activeEngine.volume.value))
                },
                onPinch = { zoomIn ->
                    val mode = if (zoomIn) ZoomMode.FILL else ZoomMode.FIT
                    activeEngine.setZoomModeByUser(mode)
                    showHud(GestureFeedback.Zoom(mode))
                },
                onSwipeDown = onExit,
                onSwipeUp = { if (isLive) sheet = PlayerSheet.CHANNELS },
                onSpeedHold = { held ->
                    if (isLive) return@playerGestures
                    if (held) {
                        carry.speedBefore = player.speed.value
                        player.setSpeed(SPEED_HOLD)
                        tick()
                        showHud(GestureFeedback.Speed(SPEED_HOLD))
                    } else {
                        player.setSpeed(carry.speedBefore)
                        showHud(null)
                    }
                },
                onTwoFingerTap = {
                    activeEngine.toggleMute()
                    // Silence has no level to show; coming back out of it, the level is the answer.
                    showHud(
                        if (activeEngine.volume.value == 0) {
                            GestureFeedback.Muted
                        } else {
                            GestureFeedback.Level(volume = true, percent = activeEngine.volume.value)
                        },
                    )
                },
                sensitivity = gestureSensitivity / 100f,
            ),
    ) {
        VideoStage(
            player = player,
            autoFrameRate = autoFrameRate,
            // Where the picture actually is, so entering the little window is the picture shrinking
            // into the corner rather than the whole screen fading into it. Cleared on the way out,
            // or the system would be handed a rectangle from a screen that is no longer there.
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    val box = coords.boundsInWindow()
                    pip.videoBounds = android.graphics.Rect(
                        box.left.toInt(),
                        box.top.toInt(),
                        box.right.toInt(),
                        box.bottom.toInt(),
                    )
                },
        )
        DisposableEffect(Unit) { onDispose { pip.videoBounds = null } }

        // A radio channel has no picture to show and never will, so the rectangle it would fill shows
        // what is playing instead. Not a screen of its own — the controls and the gestures are still
        // these. This is the *only* way full screen ends up without a picture.
        val noPicture = audioOnlyMedia && !inPip
        if (noPicture) {
            AudioOnlyBackdrop(
                title = channel?.name ?: film?.title.orEmpty(),
                playing = isPlaying,
                compact = controlsVisible,
                subtitle = if (channel != null) nowNext?.now?.title else film?.subtitle,
                artworkUrl = channel?.displayLogoUrl ?: film?.posterUrl,
                programmeEndMs = nowNext?.now?.stopMs,
            )
        }

        val failure = error
        if (failure != null) {
            ErrorPanel(failure = failure, info = errorInfo, onRetry = activeEngine::retry)
        } else if (buffering && !noPicture) {
            // On the same material as every other message over the picture, rather than a bare ring.
            PlayerToast(Modifier.align(Alignment.Center)) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
            }
        }

        PlayerControls(
            player = player,
            engine = activeEngine,
            // Whichever tuner has the surface. Only one of them ever does.
            title = channel?.name ?: film?.title.orEmpty(),
            // On a channel with a guide the Now/Next card carries the programme, so repeating it on
            // the title line would say the same thing twice on the narrowest row of the dock.
            subtitle = if (channel != null) null else film?.subtitle,
            logoUrl = channel?.displayLogoUrl ?: film?.posterUrl,
            // Nothing is drawn over the picture in the little window: it is a thumbnail, and the
            // system draws its own buttons on top of it.
            visible = controlsVisible && !inPip,
            isLive = isLive,
            offsetSec = offsetSec,
            archiveWindowSec = tuner.archiveWindowSec(),
            epg = nowNext,
            watchingWallMs = watchingWallMs,
            timelineProgrammes = timelineProgrammes,
            channelNumber = channel?.number?.takeIf { showChannelNumbers },
            onBack = stopAndExit,
            onScrubLive = tuner::scrubLive,
            onSkipLive = tuner::skipLive.takeIf { tuner.archiveWindowSec() > 0 },
            liveGaps = tuner::timeshiftGaps,
            onOpenSheet = { sheet = it },
            favorite = favoriteId != null && favoriteId in favoriteIds,
            onToggleFavorite = {
                val type = favoriteType ?: return@PlayerControls
                val id = favoriteId ?: return@PlayerControls
                scope.launch { actions.toggleFavorite(type, id) }
            },
            // The activity enables this only for the touch shell when the OS and this activity both
            // support system PiP. Radio has no picture to float; live TV, films and episodes all use
            // this same player route and therefore the same button and uninterrupted engine.
            onPictureInPicture = if (manualPipAvailable && !audioOnly && !audioOnlyMedia) {
                { pip.requestManualEntry() }
            } else {
                null
            },
            // Live channels only, whenever this device can sustain at least two decoders. The
            // channel on screen becomes tile 1 and the grid takes over from this player.
            onMultiview = if (effectiveMultiviewTiles > 1 && isLive && channel != null) {
                {
                    // The fullscreen stream goes first: tile 1 opens its own engine, and a playlist
                    // that allows two streams would otherwise be asked for three.
                    val current = channel
                    // BOTH engines. Live plays on the ExoPlayer engine (L2) whenever that is the
                    // chosen engine, and stopping only mpv left that stream running behind the grid —
                    // audible, and holding one of the provider's connections, while a tile played the
                    // same or another channel over the top of it. Two sounds at once.
                    player.stop()
                    tuner.exoEngine.stop()
                    val grid = tv.own.owntv.mobile.ui.screens.multiview.MobileMultiviewState(
                        pool = enginePool,
                        registry = streamRegistry,
                        tuner = tuner,
                        scope = scope,
                        maxTiles = effectiveMultiviewTiles,
                    )
                    current?.let { grid.fill(0, it) }
                    multiview = grid
                }
            } else {
                null
            },
            onChannels = if (isLive && channel != null) ({ sheet = PlayerSheet.CHANNELS }) else null,
            onNextChannel = if (isLive && siblings.size > 1) ({ tuner.step(1) }) else null,
            gestureScrubMs = scrubFraction?.takeIf { duration > 0 }?.let { (it * duration).toLong() },
        )

        // Independent of the controls, so it still appears after they have faded out on their own.
        if (showNextCard) {
            NextEpisodeCard(
                seconds = secondsToAdvance ?: 0,
                title = nextUpTitle.orEmpty(),
                onPlayNow = { autoNextDismissed = true; player.next() },
                onCancel = { autoNextDismissed = true; player.cancelAutoNext() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .systemBarsPadding()
                    .padding(end = 16.dp, bottom = 96.dp),
            )
        }

        toast?.let { message ->
            PlayerToast(
                Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(bottom = 96.dp),
            ) {
                Text(message, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }

        GestureHud(hud.takeIf { !inPip })
    }

    // Multiview draws over the whole player, including the picture it grew out of, and owns its own
    // engines. The stream behind it was stopped when the grid opened.
    multiview?.let { grid ->
        tv.own.owntv.mobile.ui.screens.multiview.MultiviewScreen(
            state = grid,
            onPickChannel = { tile -> multiviewPickFor = tile },
            onFullscreen = { picked ->
                grid.releaseAll()
                multiview = null
                multiviewPickFor = null
                tuner.switchTo(picked)
            },
            onExit = {
                grid.releaseAll()
                multiview = null
                multiviewPickFor = null
                // Leaving the grid leaves *playback*, by the owner's decision (2026-09-13).
                //
                // It used to promote whichever tile had the sound to the single stream. In use that
                // is wrong twice over: a grid is put away by someone who has finished watching, and
                // being handed one of the tiles means a stream is still running and still costing a
                // connection when the user believes they closed everything.
                stopAndExit()
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Filling a tile: categories first, then that category's channels. The player's own picker
        // starts at channels because it already has one playing; an empty tile has no such context,
        // and a flat list of every channel is tens of thousands of rows on a real playlist.
        multiviewPickFor?.let { tile ->
            if (multiviewCategory == null && multiviewCategories.isNotEmpty()) {
                // The Live screen's own category sheet, not a second one: it already has the search
                // field a list of hundreds of categories needs on a phone.
                CategoryPickerSheet(
                    labels = multiviewCategories.map { it.second },
                    selectedIndex = -1,
                    onSelect = { index ->
                        multiviewCategories.getOrNull(index)?.first?.let { catId ->
                            multiviewCategory = catId
                            scope.launch { multiviewChannels = tuner.channelsInCategoryForPicker(catId) }
                        }
                    },
                    // Backing out of the categories leaves the picker entirely.
                    onDismiss = { multiviewPickFor = null },
                    // Two steps: choosing a category opens its channels, it does not close the sheet.
                    dismissOnSelect = false,
                )
            } else {
                PlayerSheetHost(
                    sheet = PlayerSheet.CHANNELS,
                    player = activeEngine,
                    channels = multiviewChannels,
                brightness = brightness,
                onBrightness = { setBrightness(it) },
                    onPickChannel = { picked ->
                        grid.fill(tile, picked)
                        multiviewPickFor = null
                        multiviewCategory = null
                    },
                    onOpenSheet = {},
                    canAddSubtitles = false,
                    onPickLocalSubtitle = {},
                    onTuneToNumber = null,
                    catchup = null,
                    // Back goes UP to the categories, not out of the picker.
                    onDismiss = { multiviewCategory = null },
                )
            }
        }
    }

    // "← Categories" is an explicit level in the glass overlay, not a separate screen. The live
    // picture remains composed underneath while its rows are read.
    if (sheet == PlayerSheet.CHANNELS && !inPip && multiview == null && playerShowingCategories) {
        LaunchedEffect(Unit) {
            if (!playerCategoriesLoaded) {
                playerCategories = tuner.liveCategoriesForPicker()
                playerCategoriesLoaded = true
            }
        }
    }
    // Composed only once the list exists. A sheet is handed to the host as a lambda and drawn there,
    // and the host went on drawing the lambda it was first given: opened while the categories were
    // still being read, it showed its search field and an empty list for ever — until a rotation
    // rebuilt everything. Waiting for the data costs one database read and cannot go stale.
    if (sheet == PlayerSheet.CHANNELS && !inPip && multiview == null && playerShowingCategories &&
        playerCategoriesLoaded
    ) {
        CategoryPickerSheet(
            labels = playerCategories.map { it.second },
            selectedIndex = playerCategories.indexOfFirst { it.first == channel?.categoryId },
            onSelect = { index ->
                playerCategories.getOrNull(index)?.first?.let { catId ->
                    playerCategory = catId
                    playerShowingCategories = false
                    scope.launch { playerChannels = tuner.channelsInCategoryForPicker(catId) }
                }
            },
            onDismiss = { sheet = null },
            dismissOnSelect = false,
        )
    }

    sheet.takeIf { !inPip && multiview == null && (it != PlayerSheet.CHANNELS || !playerShowingCategories) }?.let { open ->
        PlayerSheetHost(
            sheet = open,
            player = activeEngine,
            isLive = isLive,
            onToggleEngine = when {
                isLive && channel?.drmConfig == null && (offsetSec == null || hasLocalCopy) -> {
                    {
                        toast = if (liveOnExo) switchedToMpv else switchedToExo
                        tuner.toggleLiveEngine()
                    }
                }
                film != null && canToggleVodEngine -> {
                    {
                        toast = if (vodOnExo) switchedToMpv else switchedToExo
                        player.toggleVodEngine()
                    }
                }
                else -> null
            },
            channels = if (open == PlayerSheet.CHANNELS) playerChannels else siblings,
            brightness = brightness,
            onBrightness = { setBrightness(it) },
            onPickChannel = {
                tuner.switchTo(it)
                sheet = null
            },
            onOpenSheet = { sheet = it },
            onShowChannelCategories = if (open == PlayerSheet.CHANNELS) {
                {
                    playerShowingCategories = true
                    if (!playerCategoriesLoaded) {
                        scope.launch {
                            playerCategories = tuner.liveCategoriesForPicker()
                            playerCategoriesLoaded = true
                        }
                    }
                }
            } else {
                null
            },
            // A live channel has nothing to match a subtitle against, and no file to hash.
            canAddSubtitles = film != null,
            onPickLocalSubtitle = { pickSubtitle.launch(arrayOf("*/*")) },
            // A phone's answer to the remote's number keys — offered on the same setting that shows
            // the numbers at all.
            onTuneToNumber = if (showChannelNumbers) tuner::tuneByNumber else null,
            catchup = catchup,
            programmeEndMs = if (channel != null) nowNext?.now?.stopMs else null,
            // Back out of channels returns to Categories; Back once more closes the overlay.
            onDismiss = {
                if (open == PlayerSheet.CHANNELS) {
                    playerShowingCategories = true
                    if (!playerCategoriesLoaded) {
                        scope.launch {
                            playerCategories = tuner.liveCategoriesForPicker()
                            playerCategoriesLoaded = true
                        }
                    }
                } else {
                    sheet = null
                }
            },
        )
    }
}

/** Whatever a double tap means here: the user's own skip step, or the live rewind step of archive. */
private fun skip(tuner: LiveTuner, isLive: Boolean, forward: Boolean) {
    if (isLive) {
        tuner.skipLive(forward)
    } else {
        val step = tuner.player.seekStepMs.value
        tuner.player.seekBy(if (forward) step else -step)
    }
}

@Composable
private fun ErrorPanel(failure: PlaybackFailure, info: ErrorInfo?, onRetry: () -> Unit) {
    val res = LocalResources.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PlayerToast {
            Text(
                // Core owns the wording, so the phone and the television explain a failure alike.
                text = failure.describe { id, args -> res.getString(id, *args.toTypedArray()) },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            // Plain reason, then the media it choked on, then the engine's own line. The last two are
            // what turns "it did not play" into a report somebody can act on without a cable and adb.
            info?.reason?.let {
                Text(
                    text = stringResource(it.messageRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            info?.spec?.let {
                Text(
                    text = it.displayText(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            info?.raw?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.player_raw_error, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
        }
    }
}

/**
 * "Next episode in 8" — the automatic advance, with a way to take it now and a way to stop it.
 *
 * Bottom right, clear of the transport in the middle and of the tool bar along the bottom, so the two
 * buttons are reachable without the thumb crossing anything else that would react to it.
 */
@Composable
private fun NextEpisodeCard(
    seconds: Int,
    title: String,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerToast(modifier) {
        Text(
            text = stringResource(R.string.player_next_episode, seconds),
            style = MaterialTheme.typography.labelLarge,
            color = LocalAccentOnVideo.current,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row {
            TextButton(onClick = onPlayNow) { Text(stringResource(R.string.player_play_now)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

/** The leftovers of a gesture that has not yet added up to a whole step. */
private class Carry {
    var scrub = 0f
    var volume = 0f
    var speedBefore = 1.0
}
