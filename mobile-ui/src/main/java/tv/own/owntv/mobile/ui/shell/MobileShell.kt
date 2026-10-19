package tv.own.owntv.mobile.ui.shell

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import tv.own.owntv.mobile.cast.CastController
import tv.own.owntv.mobile.ui.components.MobileIcons
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import tv.own.owntv.core.update.UpdateManager
import tv.own.owntv.mobile.ui.screens.settings.SettingsChoice
import tv.own.owntv.mobile.ui.screens.settings.SettingsChoiceSheet
import tv.own.owntv.mobile.ui.screens.settings.UpdateSheet
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.metadata.MetadataBudget
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.mobile.ui.nav.MobileDestination
import tv.own.owntv.mobile.ui.nav.MobileDestination.Companion.visible
import tv.own.owntv.mobile.ui.nav.MobileNavHost
import tv.own.owntv.mobile.playback.PipController
import tv.own.owntv.mobile.ui.nav.PLAYER_ROUTE
import tv.own.owntv.mobile.ui.nav.isChannelRoute
import tv.own.owntv.mobile.ui.nav.liveChannelRoute
import tv.own.owntv.mobile.ui.nav.SEARCH_ROUTE
import tv.own.owntv.mobile.ui.nav.SEARCH_ROUTE_PATTERN
import tv.own.owntv.mobile.ui.nav.SETUP_ROUTE
import tv.own.owntv.core.profile.profileGateRequired
import tv.own.owntv.core.profile.shellMayCompose
import tv.own.owntv.mobile.ui.profiles.ProfileGate
import tv.own.owntv.mobile.ui.profiles.ProfileGateSession
import tv.own.owntv.mobile.ui.profiles.ProfilesViewModel
import tv.own.owntv.mobile.ui.setup.ZenPlayLoginScreen
import tv.own.owntv.mobile.ui.setup.ZenPlayLoginViewModel
import tv.own.owntv.mobile.ui.setup.ZenPlayRefreshRequestResult
import tv.own.owntv.mobile.OwnTVMobileApp
import tv.own.owntv.mobile.R
import tv.own.owntv.core.sync.SyncActivityTracker
import kotlinx.coroutines.launch
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.mobile.ui.player.FloatingMiniPlayer
import tv.own.owntv.mobile.ui.player.FloatingWindowMenu
import tv.own.owntv.mobile.ui.player.MiniPlayer
import tv.own.owntv.mobile.ui.player.SleepTimerSheet
import tv.own.owntv.mobile.ui.screens.library.VodTuner
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.mobile.ui.screens.morePageTitleRes
import tv.own.owntv.mobile.ui.screens.settings.settingsPageTitleRes
import tv.own.owntv.mobile.ui.theme.GlassNest
import tv.own.owntv.mobile.ui.theme.MobileDimens
import tv.own.owntv.mobile.ui.theme.MobileNavShape
import tv.own.owntv.mobile.ui.theme.MobilePageShape
import tv.own.owntv.mobile.ui.theme.MobileTopBarShape
import tv.own.owntv.mobile.ui.theme.glassSurface

/**
 * The frame every screen sits in: a fixed top app bar, the navigation itself, and the content.
 *
 * The ZenPlay actions must remain reachable while a long catalogue scrolls. Content therefore moves
 * underneath the Scaffold's fixed chrome; only fullscreen playback is allowed to hide it.
 *
 * The navigation is a bottom bar in portrait and a rail in a medium-or-wider landscape window.
 * The choice follows the current window rather than a device label, so rotation, foldables and
 * split-screen all adapt without mounting a second shell. Which destinations appear is core's
 * `NavVisibility` rule, the same one the TV shell uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileShell(
    windowWidthDp: Int,
    windowHeightDp: Int,
    compactHandset: Boolean = false,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    hostManagesAccess: Boolean = false,
    onRefreshContent: (() -> Unit)? = null,
) {
    val shellViewModel: ShellViewModel = koinViewModel()
    val sections by shellViewModel.visibleSections.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val hostActivity = LocalContext.current.findActivity()

    // Browsing is portrait-first on a handset; only the full player may rotate to landscape.
    // Tablets remain sensor-adaptive, so portrait keeps the bottom bar and landscape gets the rail.
    // This is evaluated before the login/profile early returns, which also keeps first-run forms
    // upright and touch-friendly.
    LaunchedEffect(hostActivity, compactHandset, currentRoute) {
        when {
            !compactHandset -> hostActivity?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // PlayerScreen is the single owner while video is full screen. Keeping two owners here
            // caused portrait -> landscape -> unspecified -> portrait loops on phones held upright.
            currentRoute == PLAYER_ROUTE -> Unit
            else -> hostActivity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }
    DisposableEffect(hostActivity) {
        onDispose {
            hostActivity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // The universal host already owns onboarding, profile selection and the PIN gate. Re-running
    // that flow here would display two different access UIs one after the other. The legacy mobile
    // entry point can still own the flow by leaving [hostManagesAccess] false.
    val profilesViewModel: ProfilesViewModel? = if (hostManagesAccess) null else koinViewModel()
    val gateSession: ProfileGateSession? = if (hostManagesAccess) null else koinViewModel()
    val loginViewModel: ZenPlayLoginViewModel? = if (hostManagesAccess) null else koinViewModel()
    val loginCompletedProfileId by if (loginViewModel != null) {
        loginViewModel.completedProfileId.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<Long?>(null) }
    }
    val profiles by if (profilesViewModel != null) {
        profilesViewModel.profiles.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<List<tv.own.owntv.core.database.entity.ProfileEntity>?>(emptyList()) }
    }
    val activeProfileId by if (profilesViewModel != null) {
        profilesViewModel.activeProfileId.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<Long?>(0L) }
    }
    // Authentication is profile-bound: invalidate an old unlock before a changed active id can turn
    // into a shell destination, whether it changed by a deletion, a restore, or another writer.
    // Observe completion above every early return: activation can remove the login screen before
    // that screen could deliver a completion callback. Only the just-activated account is unlocked.
    LaunchedEffect(activeProfileId, loginCompletedProfileId) {
        val completed = loginCompletedProfileId
        if (completed != null && completed == activeProfileId) {
            gateSession?.unlock(completed)
            loginViewModel?.consumeCompletion()
        } else {
            gateSession?.invalidateIfNotProfile(activeProfileId)
        }
    }

    /*
     * What the app shows before the app itself — **the television's own `when`, branch for branch**
     * (see `MainActivity.kt`). The order is the whole of the rule, and getting it wrong is what put
     * "Who is watching?" on screen for a frame after the wizard:
     *
     *  - Nothing at all while either answer is still missing. A blank frame is the price of never
     *    showing the wrong person's library, and of never guessing from half the inputs.
     *  - **No active profile is the setup flow, not the chooser.** This is the branch mobile did not
     *    have. Mobile decided setup from "are there any playlists", so the moment the wizard imported
     *    one it handed over to the shell — while the active id was still unset — and the shell had
     *    nowhere to go but the gate. The television asks about the *profile*, so its onboarding stays
     *    up until one is active and the gate never gets a turn. It also means **Skip for now** works:
     *    finishing with no playlist at all is a legitimate end to the wizard, where the old rule
     *    would have dropped the user straight back into it.
     *  - A stale id — a list that does not contain the active profile — is recovery, so setup again.
     *  - Only then "Who is watching?", and only when the gate is genuinely required: more than one
     *    profile, or a single one with a PIN.
     */
    val loadedProfiles = profiles ?: return
    val activeId = activeProfileId ?: return
    if (!hostManagesAccess && (activeId < 0L || loadedProfiles.none { it.id == activeId })) {
        // The wizard reports the profile it activated, and that is the unlock — the same handover the
        // television does. Without it the shell would have a valid active profile and no session for
        // it, which is the one state that shows the chooser.
        ZenPlayLoginScreen(
            modifier = modifier,
            vm = requireNotNull(loginViewModel),
        )
        return
    }
    val gateRequired = !hostManagesAccess && profileGateRequired(loadedProfiles)
    if (!hostManagesAccess && !shellMayCompose(
            profiles = loadedProfiles,
            activeProfileId = activeId,
            authenticatedProfileId = loginCompletedProfileId?.takeIf { it == activeId }
                ?: gateSession?.unlockedProfileId,
            gateRequired = gateRequired,
        )
    ) {
        ProfileGate(
            profiles = loadedProfiles,
            onEntered = { gateSession?.unlock(it.id) },
            modifier = modifier,
            vm = requireNotNull(profilesViewModel),
        )
        return
    }

    // 600dp is Material's compact/medium boundary. ZenPlay keeps the tablet portrait design on the
    // bottom bar and moves the exact same five destinations to a rail after rotation.
    val useRail = !compactHandset && windowWidthDp >= 600 && windowWidthDp >= windowHeightDp
    // Compact phones get tighter shell chrome so content keeps usable space in portrait: the same
    // 48 dp touch targets and the same islands, with less air around them. Tablet/landscape keeps
    // the existing insets untouched.
    val compact = !useRail
    val shellInsetH = if (compact) CompactShellInset else MobileDimens.ShellInset
    val shellGapV = if (compact) CompactShellGap else MobileDimens.ShellGap
    // Keep the complete ZenPlay name readable without crowding the two useful actions. The narrow
    // variant is only for unusually small split-screen windows; phone and tablet layouts retain
    // their normal 48 dp touch targets.
    val headerWordmarkWidth = when {
        windowWidthDp < 360 -> 96.dp
        compactHandset -> 120.dp
        useRail -> 156.dp
        else -> 140.dp
    }
    val destinations =
        (if (useRail) MobileDestination.rail else MobileDestination.bottomBar).visible(sections)
    // A detail route ("live/42/false") keeps its tab selected and its tab's title: on a phone the
    // channel you opened is still Live TV, and the bottom bar must not go blank while you watch it.
    val current = MobileDestination.bottomBar.firstOrNull { it.route == currentRoute }
        ?: MobileDestination.bottomBar.firstOrNull { currentRoute?.startsWith("${it.route}/") == true }

    // The full screen player is the one destination that owns the whole display: no bars, no rail,
    // and no mini player, because the thing the mini player would be showing is already on screen.
    val fullscreen = currentRoute == PLAYER_ROUTE || currentRoute == SETUP_ROUTE
    val tuner: LiveTuner = koinInject()
    val vodTuner: VodTuner = koinInject()
    val channel by tuner.channel.collectAsStateWithLifecycle()
    val nowNext by tuner.nowNext.collectAsStateWithLifecycle()
    val film by vodTuner.playing.collectAsStateWithLifecycle()
    // Only ever ONE view of the picture at a time: the engine renders into a single surface, and a
    // second one attaching would take it away from the first. So no mini player on a screen that is
    // already showing the stream.
    // A channel screen sits under whichever tab it was opened from, so the tab it is under is not
    // what says the picture is on screen — the route being a channel's is. On a tablet the channel
    // is watched inside the Live TV route instead, in the pane beside the list, so the route cannot
    // answer it and the screen raises this flag itself.
    val streamOnScreen = remember { mutableStateOf(false) }
    val showingStream = fullscreen || isChannelRoute(currentRoute) || streamOnScreen.value
    // A mini player is something the user asks for in the full screen player, never something the
    // app decides for them — see [LocalMiniRequested].
    val miniRequested = remember { mutableStateOf(false) }
    val nothingPlaying = channel == null && film == null
    // The request dies with the stream, so the next one starts without a window the user never asked
    // for; without this, stopping and then previewing another channel would bring the old one back.
    LaunchedEffect(nothingPlaying) { if (nothingPlaying) miniRequested.value = false }
    val showMini = !nothingPlaying && !showingStream && miniRequested.value
    // Floating window, bar above the tabs, or neither — the user's choice, and the only thing that
    // changes is where the same stream is drawn.
    val settings: SettingsRepository = koinInject()
    val chosenMiniStyle by settings.miniPlayerStyle
        .collectAsStateWithLifecycle(SettingsRepository.MiniPlayerStyle.FLOATING)
    // Sound only has no picture, and a floating window with nothing in it is a smudge over the list the
    // user went back to. It docks instead, as a bar — which is also the shape that has room for a title
    // and the transport buttons, the only things left to show.
    // The engine that actually has the stream — live is on ExoPlayer by default, and mpv is stopped
    // there, so these two were permanently false for a live channel and a sound-only channel still
    // got the floating window it has no picture to fill.
    val playingEngine by tuner.activeEngine.collectAsStateWithLifecycle()
    val audioOnly by playingEngine.audioOnly.collectAsStateWithLifecycle()
    val audioOnlyMedia by playingEngine.audioOnlyMedia.collectAsStateWithLifecycle()
    // Casting has the same shape of problem as sound only: the picture is on the television, so a
    // floating window here would be an empty black square following the user around.
    val cast: CastController = koinInject()
    val castEngine by cast.engine.collectAsStateWithLifecycle()
    // OFF is not offered in Settings any more, but an older install may still have it stored.
    val miniStyle = if (castEngine != null || audioOnly || audioOnlyMedia ||
        chosenMiniStyle == SettingsRepository.MiniPlayerStyle.OFF
    ) {
        SettingsRepository.MiniPlayerStyle.DOCKED
    } else {
        chosenMiniStyle
    }

    // The playback notification was tapped, from a shade that may well outlive the activity that was
    // showing the player. Put it back.
    val pip: PipController = koinInject()
    val openPlayerRequested by pip.openPlayerRequested.collectAsStateWithLifecycle()
    LaunchedEffect(openPlayerRequested) {
        if (openPlayerRequested) {
            pip.openPlayerRequested.value = false
            if ((channel != null || film != null) && currentRoute != PLAYER_ROUTE) {
                navController.navigate(PLAYER_ROUTE) { launchSingleTop = true }
            }
        }
    }

    // "Start on" — Home, the last channel watched, the Favorites folder, or one chosen channel. Once
    // per launch, and only once the database has said there is something to open: on a first run the
    // setup flow is the app, and a channel resolved against an empty database is not a missing
    // channel. A channel opens its own screen rather than the full screen player, because a phone is
    // picked up in places where sound arriving unannounced is not welcome.
    val context = LocalContext.current
    // Leave size resolution to AsyncImage: it derives the request size from the bounded header layout
    // below, avoiding a full-size decode of the exact 2172 px brand asset. Chrome never crossfades.
    val headerWordmarkRequest = remember(context) {
        ImageRequest.Builder(context)
            .data(R.drawable.zenplay_wordmark_horizontal)
            .crossfade(false)
            .build()
    }
    val startupLive: StartupLiveSelection = koinInject()
    val startupUnavailable = stringResource(tv.own.owntv.mobile.R.string.settings_startup_channel_unavailable)
    var startupHandled by remember { mutableStateOf(false) }
    // Reaching this point already means the setup flow and the profile chooser are both behind us —
    // that is what the branches above guarantee — so there is nothing left to wait for.
    LaunchedEffect(Unit) {
        if (startupHandled) return@LaunchedEffect
        startupHandled = true
        when (val target = shellViewModel.resolveStartup()) {
            StartupTarget.Home -> Unit
            StartupTarget.Favorites -> {
                startupLive.requestFavorites()
                navController.navigateToTab(MobileDestination.LIVE)
            }
            is StartupTarget.Channel -> navController.navigate(liveChannelRoute(target.id))
            StartupTarget.ChannelUnavailable ->
                Toast.makeText(context, startupUnavailable, Toast.LENGTH_LONG).show()
        }
    }

    val favorite by tuner.isFavorite.collectAsStateWithLifecycle()
    var windowMenu by remember { mutableStateOf(false) }
    var sleepSheet by remember { mutableStateOf(false) }

    val playlists by shellViewModel.playlists.collectAsStateWithLifecycle()
    val activePlaylistId by shellViewModel.activePlaylistId.collectAsStateWithLifecycle()

    val catalogRefresh = if (onRefreshContent == null) {
        (context.applicationContext as? OwnTVMobileApp)?.catalogRefresh
    } else {
        null
    }
    val enqueuingRefresh by if (catalogRefresh != null) {
        catalogRefresh.isEnqueuing.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(false) }
    }
    val syncTracker: SyncActivityTracker = koinInject()
    val activeSyncs by syncTracker.active.collectAsStateWithLifecycle()
    val refreshing = enqueuingRefresh || playlists.any { source ->
        source.id in activeSyncs &&
            (activePlaylistId < 0 || playlists.none { it.id == activePlaylistId } || source.id == activePlaylistId)
    }
    val refreshScope = rememberCoroutineScope()
    val zenEpg: tv.own.owntv.mobile.zenplay.ZenPlayEpg? =
        if (onRefreshContent == null) koinInject() else null
    val requestRefresh: () -> Unit = {
        // ZenPlay "Actualizar": choose Tendencias again and refresh the TV guide too.
        tv.own.owntv.mobile.ui.screens.home.ZenPlayFeaturedCache.invalidate(context)
        if (onRefreshContent != null) {
            onRefreshContent()
        } else {
            refreshScope.launch { runCatching { zenEpg?.ensureAll(force = true) } }
            refreshScope.launch {
                val message = when (catalogRefresh?.refreshNow() ?: ZenPlayRefreshRequestResult.FAILED) {
                    ZenPlayRefreshRequestResult.QUEUED -> R.string.zenplay_refresh_requested
                    ZenPlayRefreshRequestResult.IN_PROGRESS -> R.string.zenplay_refresh_preparing
                    ZenPlayRefreshRequestResult.NO_SOURCES -> R.string.zenplay_refresh_no_sources
                    ZenPlayRefreshRequestResult.FAILED -> R.string.zenplay_refresh_failed
                    ZenPlayRefreshRequestResult.PARTIAL -> R.string.zenplay_refresh_partial
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val settingsTitle = settingsPageTitleRes(currentRoute) ?: morePageTitleRes(currentRoute)
    // Keep the ZenPlay cyan identity, but deepen it in the optional light theme so selected icons
    // continue to pass as controls rather than pale decoration. Text follows the theme's contrast.
    val chromeAccent = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        ZenPlayChromeCyan
    } else {
        ZenPlayChromeCyanDark
    }
    val chromeText = MaterialTheme.colorScheme.onSurface
    val chromeMuted = MaterialTheme.colorScheme.onSurfaceVariant

    // Downloads is a tab on a rail but a row in More on a phone, where no tab is selected to name it
    // — without this the bar would call it "Home", which is where it is not.
    val offBarTitle = MobileDestination.entries.firstOrNull { it.route == currentRoute }?.labelRes

    // Say once per launch that the day's share of the shared metadata service is gone, rather than
    // letting posters and plots quietly stop appearing. It can happen on any screen, so it belongs
    // here; `remember` (not rememberSaveable) is exactly the once-per-launch scope wanted.
    val metadataBudget: MetadataBudget = koinInject()
    val budgetRefusedAt by metadataBudget.refusedAt.collectAsStateWithLifecycle()
    var budgetNoticeShown by remember { mutableStateOf(false) }
    LaunchedEffect(budgetRefusedAt, fullscreen) {
        // Metadata never gates playback. Defer this once-per-launch notice until the full player (or
        // setup) closes, so it cannot cover video or its controls; keeping the flag false preserves it.
        if (budgetRefusedAt > 0L && !budgetNoticeShown && !fullscreen) {
            budgetNoticeShown = true
            Toast.makeText(
                context,
                tv.own.owntv.mobile.R.string.settings_metadata_limit_reached,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // The startup update check, the television's setting and the television's five-second delay —
    // long enough that the check never competes with the first screen for the network.
    //
    // Where the two apps differ: the television posts a corner toast for every outcome, because a
    // remote has somewhere to point at it. Here nothing is shown unless there is genuinely an update
    // — "checking…" and "you are up to date" are answers to a question nobody asked, and on a phone
    // they would land on top of what the user opened the app to do. A failure is silent for the same
    // reason; Settings → App → Check for updates says it out loud, on request.
    val updateManager: UpdateManager = koinInject()
    val updateCheckOnStart by settings.updateCheckOnStart.collectAsStateWithLifecycle(initialValue = false)
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    var updateChecked by remember { mutableStateOf(false) }
    var updateSheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(updateCheckOnStart) {
        if (tv.own.owntv.core.CoreBuildInfo.releaseRepo.isNotBlank() && updateCheckOnStart && !updateChecked) {
            updateChecked = true
            delay(5_000)
            updateManager.check()
        }
    }
    LaunchedEffect(updateState) {
        if (updateState is UpdateManager.State.Available) updateSheetOpen = true
    }
    // Not over the player, and not over setup: both own the whole screen for a reason.
    if (updateSheetOpen && !fullscreen) {
        UpdateSheet(
            onDismiss = {
                updateSheetOpen = false
                // Back to Idle, or the next recomposition reopens the sheet on the same release.
                updateManager.reset()
            },
        )
    }

    Scaffold(
        // The wallpaper is drawn by the backdrop root underneath; a Scaffold that painted its own
        // background would cover it and leave the glass with nothing to be transparent to.
        containerColor = Color.Transparent,
        // The player owns every pixel, camera strip included: keeping the bars' and the cutout's room
        // free there would leave a band of wallpaper down the side of the picture.
        contentWindowInsets = if (fullscreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        modifier = modifier.fillMaxSize(),
        topBar = {
            // The bar is a pane standing off the screen, not a lid on top of it: the status bar's
            // room is held by the wrapper so the wallpaper runs above the bar and stays clear when
            // the bar scrolls away, and the bar itself is inset and rounded.
            if (!fullscreen) Box(
                Modifier
                    .statusBarsPadding()
                    // The Scaffold hands the sides' room to the content slot only, so sideways —
                    // where the camera strip and the gesture bar are down the edges — the bar started
                    // further left than the rail and the page beneath it. It takes **the Scaffold's
                    // own** horizontal insets, not the wider safe-drawing ones, so the three left
                    // edges land on the same pixel rather than merely near each other.
                    .windowInsetsPadding(
                        ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal),
                    )
                    .padding(
                        start = shellInsetH,
                        end = shellInsetH,
                        bottom = shellGapV,
                    ),
            ) {
                TopAppBar(
                    // Transparent container plus the glass modifier, rather than a colour: the bar has
                    // to let the wallpaper through it, and a container colour cannot.
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        navigationIconContentColor = chromeText,
                        titleContentColor = chromeText,
                        actionIconContentColor = chromeText,
                    ),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier
                        .glassSurface(GlassSurface.TOPBAR, MobileTopBarShape)
                        // A very light brand wash keeps the chrome recognisably ZenPlay without
                        // adding another sampled/blurred layer on budget phones.
                        .background(ZenPlayChromeWash, MobileTopBarShape)
                        .clip(MobileTopBarShape),
                    title = {
                        // Primary destinations use the requested icon-only ZenPlay header. Detail
                        // pages retain their title beside Back so navigation never becomes ambiguous.
                        if (MobileDestination.bottomBar.none { it.route == currentRoute }) {
                            Text(
                                text = stringResource(
                                    settingsTitle
                                        ?: if (currentRoute == SEARCH_ROUTE_PATTERN) tv.own.owntv.mobile.R.string.search_title
                                        else current?.labelRes ?: offBarTitle ?: MobileDestination.HOME.labelRes,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        // ZenPlay identity on the five main destinations; details use Back. The
                        // wordmark remains a Home shortcut without looking like another menu item.
                        if (MobileDestination.bottomBar.any { it.route == currentRoute }) {
                            val homeDescription = stringResource(MobileDestination.HOME.labelRes)
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier
                                    .width(headerWordmarkWidth)
                                    .height(48.dp)
                                    .clickable { navController.navigateToTab(MobileDestination.HOME) }
                                    .semantics { contentDescription = homeDescription },
                            ) {
                                AsyncImage(
                                    model = headerWordmarkRequest,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .width(headerWordmarkWidth)
                                        .height(40.dp)
                                        .padding(start = 8.dp, end = 4.dp),
                                )
                            }
                        } else {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    imageVector = MobileIcons.ArrowBack,
                                    contentDescription = stringResource(tv.own.owntv.mobile.R.string.common_back),
                                )
                            }
                        }
                    },
                    actions = {
                        // ZenPlay: "Actualizar" lives in the top bar on every touch layout.
                        val refreshDescription = stringResource(R.string.zenplay_refresh_action)
                        IconButton(
                            onClick = requestRefresh,
                            enabled = !refreshing,
                            modifier = Modifier.semantics { contentDescription = refreshDescription },
                        ) {
                            RefreshNavIcon(refreshing)
                        }
                        IconButton(
                            onClick = {
                                navController.navigate(SEARCH_ROUTE) { launchSingleTop = true }
                            },
                        ) {
                            Icon(
                                imageVector = MobileIcons.Search,
                                contentDescription = stringResource(tv.own.owntv.mobile.R.string.common_nav_search),
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!fullscreen) {
                // The mini player shares the bottom bar slot, above the tabs, so it is docked in both
                // layouts — a rail screen has no bottom bar of its own and would otherwise lose it.
                // Both are islands: inset from the sides, clear of the gesture bar, with the page
                // ending above them rather than running underneath.
                Column(
                    // The gap above the island is the page's own bottom inset, so it is not counted
                    // twice; this only holds the island clear of the gesture bar.
                    Modifier.navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(shellGapV),
                ) {
                    if (showMini && miniStyle == SettingsRepository.MiniPlayerStyle.DOCKED) {
                        // A channel wins when there is one, because the two tuners cannot both be
                        // playing and the live one is what the other stops before it starts.
                        val live = channel
                        MiniPlayer(
                            player = tuner.player,
                            engine = playingEngine,
                            title = live?.name ?: film?.title.orEmpty(),
                            subtitle = if (live != null) nowNext?.now?.title else film?.subtitle,
                            artworkUrl = live?.displayLogoUrl ?: film?.posterUrl,
                            onExpand = { navController.navigate(PLAYER_ROUTE) },
                            onStop = { if (live != null) tuner.stop() else vodTuner.stop() },
                            remote = castEngine,
                            modifier = Modifier.padding(horizontal = shellInsetH),
                        )
                    }
                    if (!useRail) {
                        NavigationBar(
                            containerColor = Color.Transparent,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            modifier = Modifier
                                .padding(horizontal = shellInsetH)
                                .height(MobileDimens.NavIslandHeight)
                                .glassSurface(GlassSurface.SIDEBAR, MobileNavShape)
                                .background(ZenPlayNavigationWash, MobileNavShape)
                                .clip(MobileNavShape),
                        ) {
                            destinations.forEach { destination ->
                                NavigationBarItem(
                                    alwaysShowLabel = true,
                                    selected = destination == current,
                                    onClick = { navController.onNavClick(destination, current, shellViewModel) },
                                    icon = { NavIcon(destination) },
                                    label = { NavLabel(destination) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = chromeAccent,
                                        selectedTextColor = chromeText,
                                        indicatorColor = ZenPlayChromePurple.copy(alpha = 0.24f),
                                        unselectedIconColor = chromeMuted,
                                        unselectedTextColor = chromeMuted,
                                    ),
                                    modifier = Modifier.longPressResetsScroll(destination, shellViewModel),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { insets ->
        /*
         * How much of the shell's own inset is still owed along the bottom edge.
         *
         * `insets` already carries whatever is down there, and what that is differs by layout:
         *
         *  - **Bottom bar or docked mini player** (a phone, or any width with the bar showing): the
         *    island's own height. The shell inset is a real gap between the page and the island, so
         *    it is added in full.
         *  - **Rail and nothing else** (a tablet, a phone sideways): only the system's gesture
         *    inset — empty room the page must keep clear but is not standing next to anything in.
         *    Adding 11 dp on top of it left a dead band under both the rail and the page, four
         *    times the gap down the sides, which is what the owner saw on the tablet. Only the
         *    shortfall is added here, so the bottom ends up `max(gesture inset, 11 dp)` rather than
         *    their sum.
         */
        val bottomIsland = !useRail || (showMini && miniStyle == SettingsRepository.MiniPlayerStyle.DOCKED)
        val shellBottom = when {
            fullscreen -> 0.dp
            bottomIsland -> shellInsetH
            else -> (MobileDimens.ShellInset - insets.calculateBottomPadding()).coerceAtLeast(0.dp)
        }

        Row(Modifier.padding(insets)) {
            if (useRail && !fullscreen) {
                NavigationRail(
                    containerColor = Color.Transparent,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier
                        .padding(start = MobileDimens.ShellInset, bottom = shellBottom)
                        .glassSurface(GlassSurface.SIDEBAR, MobileNavShape)
                        .background(ZenPlayNavigationWash, MobileNavShape)
                        .clip(MobileNavShape),
                ) {
                    // Eight destinations do not fit down the short side of a phone held sideways, and
                    // an unscrollable rail simply loses the last of them — which is where Settings is.
                    //
                    // Centred and scrollable at once, which a plain scrolling Column cannot do: inside
                    // `verticalScroll` a Column is measured against an unbounded height, so it wraps
                    // its children and `Arrangement.Center` has nothing to centre within. Giving it a
                    // minimum of the rail's own height makes it fill the rail when the items fit — so
                    // they sit in the middle — and grow past it when they do not, which is when a
                    // large display size or a big font turns the rail into a list that must scroll.
                    BoxWithConstraints(Modifier.weight(1f)) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .heightIn(min = maxHeight),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            destinations.forEach { destination ->
                                NavigationRailItem(
                                    selected = destination == current,
                                    onClick = { navController.onNavClick(destination, current, shellViewModel) },
                                    icon = { NavIcon(destination) },
                                    label = { NavLabel(destination) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = chromeAccent,
                                        selectedTextColor = chromeText,
                                        indicatorColor = ZenPlayChromePurple.copy(alpha = 0.24f),
                                        unselectedIconColor = chromeMuted,
                                        unselectedTextColor = chromeMuted,
                                    ),
                                    modifier = Modifier.longPressResetsScroll(destination, shellViewModel),
                                )
                            }
                        }
                    }
                }
            }
            // Fullscreen playback receives a plain, un-clipped host: even a transparent panel glass
            // modifier adds an off-screen/draw layer across every video pixel. Browsing pages keep the
            // inset rounded pane and its nesting contract.
            val pageModifier = if (fullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxSize()
                    .padding(
                        // Against a rail the page keeps the smaller gap; against the screen edge it
                        // keeps the full inset, so the wallpaper frames the whole shell evenly.
                        // Compact phones use the tighter inset to keep one-handed content width.
                        start = if (useRail) MobileDimens.ShellGap else shellInsetH,
                        end = shellInsetH,
                        bottom = shellBottom,
                    )
                    .glassSurface(GlassSurface.PANELS, MobilePageShape)
                    .clip(MobilePageShape)
            }
            Box(pageModifier) {
                val navigationHost: @Composable () -> Unit = {
                    CompositionLocalProvider(
                        LocalStreamOnScreen provides streamOnScreen,
                        LocalMiniRequested provides miniRequested,
                    ) {
                        MobileNavHost(
                            navController = navController,
                            scrollToTop = shellViewModel.scrollToTop,
                        )
                    }
                }
                if (fullscreen) {
                    navigationHost()
                } else {
                    // Every browsing screen is standing on this page panel, so a panel of its own
                    // draws as the layer behind one instead of frosting what is already frosted.
                    GlassNest(GlassSurface.PANELS) { navigationHost() }
                }
                // Low over the page, under the mini player: a sync running while the user browses is
                // news, but it is never what they came to the screen for.
                if (fullscreen) {
                    // Top, clear of the player's own controls at the bottom, and recordings only.
                    SyncStatusPill(
                        Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding(),
                        recordingsOnly = true,
                    )
                } else {
                    SyncStatusPill(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding(),
                        onOpenDownloads = { navController.navigate(MobileDestination.DOWNLOADS.route) },
                    )
                }
                // Over the content rather than beside it, because that is what a floating window is.
                if (showMini && miniStyle == SettingsRepository.MiniPlayerStyle.FLOATING) {
                    val live = channel
                    FloatingMiniPlayer(
                        player = tuner.player,
                        engine = playingEngine,
                        title = live?.name ?: film?.title.orEmpty(),
                        isLive = live != null,
                        artworkUrl = live?.displayLogoUrl ?: film?.posterUrl,
                        onExpand = { navController.navigate(PLAYER_ROUTE) },
                        onStop = { if (live != null) tuner.stop() else vodTuner.stop() },
                        onMenu = { windowMenu = true },
                    )
                }
            }
        }
    }

    if (windowMenu) {
        FloatingWindowMenu(
            isFavorite = favorite,
            onToggleFavorite = tuner::toggleFavorite,
            onAudioOnly = { tuner.setAudioOnly(true) },
            onSleepTimer = { sleepSheet = true },
            onExpand = { navController.navigate(PLAYER_ROUTE) },
            onStop = { if (channel != null) tuner.stop() else vodTuner.stop() },
            onDismiss = { windowMenu = false },
        )
    }
    if (sleepSheet) {
        SleepTimerSheet(
            programmeEndMs = nowNext?.now?.stopMs,
            onDismiss = { sleepSheet = false },
        )
    }
    // ZenPlay: the animated welcome screen each time the app opens, over the first screen while it loads.
    var zenSplash by remember { mutableStateOf(!tv.own.owntv.mobile.zenplay.ZenSplashState.shown) }
    LaunchedEffect(Unit) {
        if (!zenSplash) return@LaunchedEffect
        delay(2_200)
        tv.own.owntv.mobile.zenplay.ZenSplashState.shown = true
        zenSplash = false
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = zenSplash,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(450)),
    ) {
        tv.own.owntv.mobile.zenplay.ZenPlaySplash()
    }
}

/** Tighter shell chrome for compact phones in portrait. Touch targets stay 48 dp; only air is cut. */
private val CompactShellInset = 8.dp

/** Vertical gap between shell islands on compact phones. */
private val CompactShellGap = 6.dp

/** Silver-grey chrome with the two ZenPlay accents, kept translucent over the existing glass. */
private val ZenPlayChromeSilver = Color(0xFFE8EDF7)
private val ZenPlayChromeCyan = Color(0xFF53DFFF)
private val ZenPlayChromeCyanDark = Color(0xFF00677D)
private val ZenPlayChromePurple = Color(0xFFA774FF)
private val ZenPlayChromeWash = Brush.horizontalGradient(
    0f to ZenPlayChromeCyan.copy(alpha = 0.055f),
    0.52f to ZenPlayChromeSilver.copy(alpha = 0.035f),
    1f to ZenPlayChromePurple.copy(alpha = 0.055f),
)
private val ZenPlayNavigationWash = Brush.horizontalGradient(
    0f to ZenPlayChromeSilver.copy(alpha = 0.075f),
    0.50f to Color(0xFF77839A).copy(alpha = 0.055f),
    1f to ZenPlayChromePurple.copy(alpha = 0.075f),
)

@Composable
private fun NavIcon(destination: MobileDestination) {
    Icon(imageVector = destination.icon, contentDescription = null)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun NavLabel(destination: MobileDestination) {
    Text(
        text = stringResource(destination.labelRes),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RefreshNavIcon(refreshing: Boolean) {
    if (refreshing) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    } else {
        Icon(MobileIcons.Refresh, contentDescription = null)
    }
}

@Composable
private fun RefreshNavLabel() {
    Text(
        text = stringResource(R.string.zenplay_refresh_action),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Tapping the tab you are already on sends its list back to the top, as does a long press. */
private fun NavHostController.onNavClick(
    destination: MobileDestination,
    current: MobileDestination?,
    shell: ShellViewModel,
) {
    if (destination == current && currentDestination?.route == destination.route) {
        shell.requestScrollToTop(destination.route)
    } else {
        navigateToTab(destination)
    }
}

/**
 * A long press on a nav item scrolls that tab back to the top.
 *
 * It watches the pointer on the *initial* pass and never consumes, because the item's own click
 * handling sits below this modifier and would otherwise swallow the gesture — a plain
 * `combinedClickable` wrapped around a `NavigationBarItem` does nothing at all.
 */
private fun Modifier.longPressResetsScroll(
    destination: MobileDestination,
    shell: ShellViewModel,
): Modifier = pointerInput(destination) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val heldDown = try {
            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation(PointerEventPass.Initial)
            }
            false
        } catch (_: PointerEventTimeoutCancellationException) {
            true
        }
        if (heldDown) shell.requestScrollToTop(destination.route)
    }
}

/**
 * Switch tabs the way a bottom bar is supposed to: one entry per tab on the back stack, each tab's
 * own scroll position and state kept, and back from any tab landing on the start destination rather
 * than walking every tab you visited.
 */
fun NavHostController.navigateToTab(destination: MobileDestination) {
    // More is now pushed above the current content. Remove that temporary menu (and its children)
    // before saving a tab's stack; otherwise restoring Movies could reopen More instead of Movies.
    val menuOnStack = try {
        getBackStackEntry(MobileDestination.MORE.route)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
    if (menuOnStack) popBackStack(MobileDestination.MORE.route, inclusive = true)
    if (currentDestination?.route == destination.route) return
    if (currentDestination?.route?.startsWith("${destination.route}/") == true &&
        popBackStack(destination.route, inclusive = false)
    ) return
    navigate(destination.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
