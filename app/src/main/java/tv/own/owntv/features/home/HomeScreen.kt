package tv.own.owntv.features.home

import android.content.Context
import tv.own.owntv.core.home.GuideSliceState
import tv.own.owntv.core.home.HeroItem
import tv.own.owntv.core.home.TrendingHomeItem
import tv.own.owntv.core.home.shouldAutoAdvanceTrending
import tv.own.owntv.core.home.wrappedTrendingIndex
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.BlurMaskFilter
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.dao.TrendingDao
import tv.own.owntv.core.launcher.LauncherContinuationItem
import tv.own.owntv.core.launcher.LauncherWatchNextType
import tv.own.owntv.core.model.HomeLiveRowMode
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.HomeTrendingStyle
import tv.own.owntv.player.HeroPreviewEngine
import tv.own.owntv.ui.components.BrandLockup
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.InAppToast
import tv.own.owntv.ui.components.rememberInAppToast
import tv.own.owntv.ui.components.PosterCard
import tv.own.owntv.ui.components.ContentPanelFill
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.format.formatSystemTime
import tv.own.owntv.ui.theme.Dimens
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.format.localizedInteger
import tv.own.owntv.features.shell.components.MediaDetailsScreen
import tv.own.owntv.features.shell.components.MediaDetailsUi
import tv.own.owntv.core.metadata.MetadataImages
import tv.own.owntv.core.trending.ProviderVariantParser
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.widthIn
import java.util.Calendar
import tv.own.owntv.ui.theme.gradientWash

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onPlayMovie: (movieId: Long, positionMs: Long) -> Unit,
    onPlayEpisode: (seriesId: Long, episodeId: Long, positionMs: Long) -> Unit,
    onPlayChannel: (channelId: Long, zapChannels: List<ChannelEntity>) -> Unit,
    onOpenGuide: () -> Unit,
    onActivateTrending: (TrendingHomeItem, onUnavailable: () -> Unit) -> Unit,
    /** ZenPlay: open a film's page (a series opens its episodes). Defaults to [onActivateTrending]. */
    onOpenMovieDetails: (TrendingHomeItem, onUnavailable: () -> Unit) -> Unit = onActivateTrending,
    onOpenTrendingSearch: (String) -> Unit,
    onChildFocused: () -> Unit,
    restoreFocus: Boolean = false,
    restoreTrendingSearchFocus: Boolean = false,
    onRestored: () -> Unit = {},
    previewEnabled: Boolean = true,
    firstRowFocusRequester: FocusRequester? = null,
    onContentScrolled: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val trendingUnavailableMessage = stringResource(R.string.home_trending_unavailable)
    val heroPreviewEngine = koinInject<HeroPreviewEngine>()
    val engineState by heroPreviewEngine.state.collectAsStateWithLifecycle()
    val isPreviewActive by vm.isPreviewActive.collectAsStateWithLifecycle()
    val lastInteractionMs by vm.lastHeroInteractionMs.collectAsStateWithLifecycle()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val chromeScrollThresholdPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val contentScrolled by remember(listState, chromeScrollThresholdPx) {
        androidx.compose.runtime.derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > chromeScrollThresholdPx
        }
    }
    LaunchedEffect(contentScrolled) { onContentScrolled(contentScrolled) }
    val homeScope = rememberCoroutineScope()
    val heroFocus = remember { FocusRequester() }
    val fallbackFocus = remember { FocusRequester() }
    val trendingPrimaryFocus = remember { FocusRequester() }
    val trendingDetailsFocus = remember { FocusRequester() }
    val trendingToast = rememberInAppToast()
    var detailsItem by remember { mutableStateOf<TrendingHomeItem?>(null) }
    var detailsMetadata by remember { mutableStateOf<MetadataCacheEntity?>(null) }
    var detailsTmdbWins by remember { mutableStateOf(false) }
    val trendingRowFocused = remember { mutableStateOf(false) }
    val rowFirstFocusRequesters = remember {
        HomeRow.entries.associateWith { FocusRequester() }
    }
    // Nothing starts expanded: a hero card earns its wide (16:9) state only after 3s of continuous focus
    // (see the dwell effect below). Moving focus — to another card or out of the row — collapses it again.
    var expandedHeroIndex by remember { mutableStateOf(-1) }
    var focusedHeroIndex by remember { mutableStateOf(-1) }
    val orderedRows = state.config.visibleOrder
    val heroVisible = HomeRow.HERO in orderedRows
    val hasNonHeroContent = orderedRows.any { it != HomeRow.HERO && rowHasData(it, state) }
    val showHeroFallback = heroVisible && state.heroItems.isEmpty() && !hasNonHeroContent
    val renderRows = orderedRows.filter { rowCanRender(it, state, showHeroFallback) }
    val firstDataRow = renderRows.firstOrNull { it != HomeRow.HERO && rowHasData(it, state) }
    val showAllHiddenState = orderedRows.isEmpty()
    val showEmptyState = orderedRows.isNotEmpty() && renderRows.isEmpty()
    val rowFocusRequester: (HomeRow) -> FocusRequester? = { row ->
        if (row == renderRows.firstOrNull() && firstRowFocusRequester != null) {
            firstRowFocusRequester
        } else when (row) {
            HomeRow.TRENDING -> trendingPrimaryFocus
            HomeRow.HERO -> when {
                state.heroItems.isNotEmpty() -> heroFocus
                showHeroFallback -> fallbackFocus
                else -> null
            }
            else -> rowFirstFocusRequesters[row]
        }
    }

    val onNonHeroFocused = remember(vm, heroPreviewEngine) {
        {
            vm.setHeroFocused(false)
            heroPreviewEngine.stop()
            expandedHeroIndex = -1
            onChildFocused()
        }
    }

    LaunchedEffect(focusedHeroIndex) {
        if (focusedHeroIndex != -1) return@LaunchedEffect
        // Focus moves between hero cards very quickly (old loses focus before new gains). Debounce the
        // "left hero row" signal so we don't flap preview state while navigating within the row.
        kotlinx.coroutines.delay(40L)
        if (focusedHeroIndex != -1) return@LaunchedEffect
        vm.setHeroFocused(false)
        heroPreviewEngine.stop()
        expandedHeroIndex = -1
    }

    // Dwell-to-expand: a card widens only after 3s of uninterrupted focus. Navigating away cancels the
    // timer (this effect restarts), so quick D-pad sweeps never expand anything.
    LaunchedEffect(focusedHeroIndex) {
        val index = focusedHeroIndex
        if (index < 0) return@LaunchedEffect
        kotlinx.coroutines.delay(3_000L)
        if (focusedHeroIndex == index) expandedHeroIndex = index
    }

    LaunchedEffect(previewEnabled) {
        vm.setPreviewEnabled(previewEnabled)
        if (!previewEnabled) {
            vm.stopPreview() // keep the hero expanded (poster); just stop the video
        }
    }

    // The engine is an app-scoped singleton; make sure the preview can't outlive the Home screen.
    DisposableEffect(heroPreviewEngine) {
        onDispose { heroPreviewEngine.stop() }
    }

    LaunchedEffect(orderedRows, state.trendingItems, state.heroItems, state.recentLive, state.favoriteLive, state.recentGuide, state.favoriteGuide, state.continueMovies, state.continueSeries, restoreFocus, restoreTrendingSearchFocus) {
        if (orderedRows.isEmpty()) {
            if (restoreFocus) onRestored()
            return@LaunchedEffect
        }

        val targetRow = when {
            restoreTrendingSearchFocus && state.trendingItems.isNotEmpty() -> HomeRow.TRENDING
            restoreFocus && heroVisible && state.heroItems.isNotEmpty() -> HomeRow.HERO
            restoreFocus && showHeroFallback -> HomeRow.HERO
            restoreFocus -> firstDataRow
            else -> null
        }
        val targetIndex = targetRow?.let { renderRows.indexOf(it) } ?: 0
        runCatching { listState.scrollToItem(targetIndex.coerceAtLeast(0)) }

        // Only pull focus INTO the Home content when returning from the player (restoreFocus). On a cold
        // start or a tab switch, leave focus on the sidebar's Home item so the nav is immediately navigable.
        if (restoreFocus || restoreTrendingSearchFocus) {
            kotlinx.coroutines.delay(60)
            val focusTarget = when {
                restoreTrendingSearchFocus && state.trendingItems.isNotEmpty() -> trendingDetailsFocus
                heroVisible && state.heroItems.isNotEmpty() -> rowFocusRequester(HomeRow.HERO)
                showHeroFallback -> rowFocusRequester(HomeRow.HERO)
                firstDataRow != null -> rowFocusRequester(firstDataRow)
                else -> null
            }
            if (focusTarget != null) runCatching { focusTarget.requestFocus() }
            onRestored()
        }
    }

    // Cold-start "structure first": Home's queries are indexed and profile-scoped, but their first reads
    // still come off cold eMMC before the OS page cache warms — so there's a brief gap between the shell
    // painting and `home-data` arriving. During it we render the skeleton (instant) rather than flashing the
    // empty state, which would look wrong (and momentarily disappear) for a user who actually has history.
    // isLoading is true only for the initial state; it flips false on the first load and never goes back.
    if (state.isLoading) {
        HomeSkeleton(modifier = modifier.fillMaxSize())
        return
    }
    if (showAllHiddenState) {
        AllRowsHiddenState(modifier = modifier.fillMaxSize())
        return
    }
    if (showEmptyState) {
        EmptyHomeState(modifier = modifier.fillMaxSize())
        return
    }

    val hero = state.heroItems.getOrNull(state.activeHeroIndex)
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val homeBringIntoViewSpec = remember(defaultBringIntoViewSpec) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (trendingRowFocused.value) 0f
                else defaultBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
        }
    }

    // The poster row is an ordinary row, so the hero's "do not scroll me" rule must not survive a
    // switch to it.
    LaunchedEffect(state.config.trendingStyle) {
        if (state.config.trendingStyle == HomeTrendingStyle.POSTERS) trendingRowFocused.value = false
    }

    LaunchedEffect(trendingRowFocused.value) {
        if (trendingRowFocused.value && renderRows.firstOrNull() == HomeRow.TRENDING) {
            listState.animateScrollToItem(0, 0)
        }
    }

    // ZenPlay catalogue rows all open the same local detail destination. Keeping this outside the
    // row switch lets the hero and both recent rows stay separate, ordered Home sections.
    val zenActivate: (TrendingHomeItem) -> Unit = { item ->
        onOpenMovieDetails(item) {
            trendingToast.show(trendingUnavailableMessage)
            vm.refresh()
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides homeBringIntoViewSpec) {
      LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel(fillColor = ContentPanelFill)
            .onFocusChanged { if (it.hasFocus) onChildFocused() }
            .focusGroup(),
        state = listState,
        contentPadding = PaddingValues(vertical = Dimens.ScreenPaddingV),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapLarge),
    ) {
        itemsIndexed(renderRows, key = { _, row -> row.name }) { index, row ->
            val firstItemFocusRequester = rowFocusRequester(row)
            val nextRowIndex = renderRows
                .drop(index + 1)
                .indexOfFirst { rowFocusRequester(it) != null }
                .takeIf { it >= 0 }
                ?.let { index + 1 + it }
            val onMoveToNextRow: (() -> Unit)? = nextRowIndex?.let { targetIndex ->
                val targetFocusRequester = rowFocusRequester(renderRows[targetIndex]) ?: return@let null
                {
                    homeScope.launch {
                        val targetIsVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
                        if (!targetIsVisible) {
                            listState.scrollToItem(targetIndex)
                            kotlinx.coroutines.delay(50)
                        }
                        runCatching { targetFocusRequester.requestFocus() }
                    }
                }
            }
            when (row) {
                // Two shapes, the user's choice: the full hero, or the plain strip of posters the
                // rest of Home is made of. Same items, same order, either way.
                HomeRow.TRENDING -> {
                // ZenPlay: the cinematic hero is its own first section. Recent films and series are
                // separate rows later, after Continue watching, rather than being nested here.
                val zenHasTrending = state.trendingItems.size >= TrendingDao.MIN_ELIGIBLE_ITEMS
                if (!zenHasTrending) {
                    // Too few titles to be a "trending" row at all, so the row is not drawn. An empty
                    // block rather than a bare `Unit`: the compiler reads that one as an expression
                    // whose value is thrown away, and says so on every build.
                } else if (state.config.trendingStyle == HomeTrendingStyle.POSTERS) {
                    TrendingPosterRow(
                        title = row.displayTitle(),
                        items = state.trendingItems,
                        onItemClick = zenActivate,
                        onFocus = onNonHeroFocused,
                        firstItemFocusRequester = firstItemFocusRequester ?: trendingPrimaryFocus,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TrendingHeroSection(
                        items = state.trendingItems,
                        activeIndex = state.activeTrendingIndex,
                        seasonCounts = state.trendingSeasonCounts,
                        primaryFocusRequester = firstItemFocusRequester ?: trendingPrimaryFocus,
                        detailsFocusRequester = trendingDetailsFocus,
                        onNavigate = vm::navigateTrending,
                        onActivate = { item ->
                            onActivateTrending(item) {
                                trendingToast.show(trendingUnavailableMessage)
                                vm.refresh()
                            }
                        },
                        onDetails = { item ->
                            vm.stopPreview()
                            // ZenPlay: a film's "Más detalles" is the same film page as everywhere else.
                            if (item is TrendingHomeItem.Movie) {
                                zenActivate(item)
                            } else {
                                homeScope.launch {
                                    val current = vm.revalidateTrendingItem(item) ?: return@launch
                                    val resolved = vm.resolveTrendingDetails(current)
                                    detailsMetadata = resolved.cache
                                    detailsTmdbWins = resolved.tmdbWins
                                    detailsItem = current
                                }
                            }
                        },
                        onFocus = onNonHeroFocused,
                        onSectionFocusChanged = { trendingRowFocused.value = it },
                        onContainerDown = onMoveToNextRow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                }

                HomeRow.HERO -> {
                    if (state.heroItems.isNotEmpty()) {
                        HeroRowSection(
                            items = state.heroItems,
                            activeHeroIndex = state.activeHeroIndex,
                            expandedIndex = expandedHeroIndex,
                            heroPreviewEngine = heroPreviewEngine,
                            engineState = engineState,
                            heroFocusRequester = firstItemFocusRequester ?: heroFocus,
                            heroMetadata = state.heroMetadata,
                            onHeroFocusChanged = { index, hasFocus ->
                                if (hasFocus) {
                                    if (expandedHeroIndex != index) {
                                        heroPreviewEngine.stop() // stop the previous hero's video before switching
                                        expandedHeroIndex = -1 // collapse immediately; the dwell timer re-expands after 3s
                                    }
                                    focusedHeroIndex = index
                                    vm.onHeroUserNavigate(index)
                                    vm.setHeroFocused(true)
                                    onChildFocused()
                                } else if (focusedHeroIndex == index) {
                                    focusedHeroIndex = -1
                                }
                            },
                            onPlay = { item ->
                                when (item) {
                                    is HeroItem.MovieHero -> onPlayMovie(item.movie.id, item.positionMs)
                                    is HeroItem.SeriesHero -> onPlayEpisode(item.series.id, item.episode.id, item.positionMs)
                                    is HeroItem.LiveHero -> onPlayChannel(item.channel.id, state.recentLive)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        HeroFallbackPane(
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = firstItemFocusRequester ?: fallbackFocus,
                            onChildFocused = onNonHeroFocused,
                        )
                    }
                }

                HomeRow.RECENT_MOVIES -> if (state.recentMovies.isNotEmpty()) {
                    TrendingPosterRow(
                        title = stringResource(R.string.zenplay_home_recent_movies),
                        items = state.recentMovies,
                        onItemClick = zenActivate,
                        onFocus = onNonHeroFocused,
                        firstItemFocusRequester = firstItemFocusRequester,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HomeRow.RECENT_SERIES -> if (state.recentSeries.isNotEmpty()) {
                    TrendingPosterRow(
                        title = stringResource(R.string.zenplay_home_recent_series),
                        items = state.recentSeries,
                        onItemClick = zenActivate,
                        onFocus = onNonHeroFocused,
                        firstItemFocusRequester = firstItemFocusRequester,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HomeRow.RECENT_CHANNELS -> if (state.recentLive.isNotEmpty()) {
                    HomeLiveRow(
                        title = row.displayTitle(),
                        mode = state.config.recentLiveMode,
                        channels = state.recentLive,
                        guide = state.recentGuide,
                        onChannelClick = onPlayChannel,
                        onFocus = onNonHeroFocused,
                        firstItemFocusRequester = firstItemFocusRequester,
                        onContainerDown = onMoveToNextRow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HomeRow.FAVORITE_CHANNELS -> if (state.favoriteLive.isNotEmpty()) {
                    HomeLiveRow(
                        title = row.displayTitle(),
                        mode = state.config.favoriteLiveMode,
                        channels = state.favoriteLive,
                        guide = state.favoriteGuide,
                        onChannelClick = onPlayChannel,
                        onFocus = onNonHeroFocused,
                        firstItemFocusRequester = firstItemFocusRequester,
                        onContainerDown = onMoveToNextRow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HomeRow.CONTINUE_MOVIES -> if (state.continueMovies.isNotEmpty()) {
                    ContinueWatchingRow(
                        title = row.displayTitle(),
                        items = state.continueMovies,
                        onItemClick = { onPlayMovie(it.sourceItemId, it.positionMs) },
                        onFocus = onNonHeroFocused,
                        firstItemFocusRequester = firstItemFocusRequester,
                    )
                }

                HomeRow.CONTINUE_SERIES -> if (state.continueSeries.isNotEmpty()) {
                    ContinueWatchingRow(
                        title = row.displayTitle(),
                        items = state.continueSeries,
                        posterOverrides = state.continuationArtwork,
                        landscapeTiles = true,
                        onItemClick = { onPlayEpisode(0L, it.targetItemId, it.positionMs) },
                        onFocus = onNonHeroFocused,
                        onItemFocus = {
                            onNonHeroFocused()
                            vm.resolveSeriesContinuationArtwork(it)
                        },
                        firstItemFocusRequester = firstItemFocusRequester,
                    )
                }
            }
        }
      }
    }

    // Video preview starts after the expanded card has settled, so 4K decoder setup does not compete with
    // the width animation. Until then the expanded card stays on the poster.
    LaunchedEffect(isPreviewActive, hero, expandedHeroIndex, focusedHeroIndex, lastInteractionMs) {
        if (!isPreviewActive || hero == null || expandedHeroIndex < 0 ||
            focusedHeroIndex != expandedHeroIndex
        ) {
            heroPreviewEngine.stop()
            return@LaunchedEffect
        }

        val scheduledIndex = expandedHeroIndex
        val scheduledHero = hero
        val interactionStamp = lastInteractionMs

        heroPreviewEngine.stop()
        kotlinx.coroutines.delay(520L)
        if (!isPreviewActive || interactionStamp != lastInteractionMs) return@LaunchedEffect
        if (focusedHeroIndex != scheduledIndex || expandedHeroIndex != scheduledIndex) return@LaunchedEffect
        if (scheduledHero != state.heroItems.getOrNull(state.activeHeroIndex)) return@LaunchedEffect

        vm.startPreview(scheduledHero)
    }

    detailsItem?.let { item ->
        MediaDetailsScreen(details = item.toDetailsUi(detailsMetadata, detailsTmdbWins), onExit = {
            detailsItem = null
            detailsMetadata = null
            homeScope.launch {
                kotlinx.coroutines.delay(60)
                runCatching { trendingDetailsFocus.requestFocus() }
            }
        })
    }
    InAppToast(trendingToast)
}

private fun rowHasData(row: HomeRow, state: HomeUiState): Boolean = when (row) {
    HomeRow.TRENDING -> state.trendingItems.size >= TrendingDao.MIN_ELIGIBLE_ITEMS
    HomeRow.HERO -> state.heroItems.isNotEmpty()
    HomeRow.RECENT_MOVIES -> state.recentMovies.isNotEmpty()
    HomeRow.RECENT_SERIES -> state.recentSeries.isNotEmpty()
    HomeRow.RECENT_CHANNELS -> when (state.config.recentLiveMode) {
        HomeLiveRowMode.CARDS -> state.recentLive.isNotEmpty()
        HomeLiveRowMode.ON_NOW -> state.recentGuide.hasContent
    }
    HomeRow.FAVORITE_CHANNELS -> when (state.config.favoriteLiveMode) {
        HomeLiveRowMode.CARDS -> state.favoriteLive.isNotEmpty()
        HomeLiveRowMode.ON_NOW -> state.favoriteGuide.hasContent
    }
    HomeRow.CONTINUE_MOVIES -> state.continueMovies.isNotEmpty()
    HomeRow.CONTINUE_SERIES -> state.continueSeries.isNotEmpty()
}

private fun rowCanRender(row: HomeRow, state: HomeUiState, showHeroFallback: Boolean): Boolean =
    when (row) {
        HomeRow.HERO -> state.heroItems.isNotEmpty() || showHeroFallback
        else -> rowHasData(row, state)
    }

private fun HeroItem.heroKey(): String = when (this) {
    is HeroItem.MovieHero -> "movie:${movie.id}"
    is HeroItem.SeriesHero -> "episode:${episode.id}"
    is HeroItem.LiveHero -> "live:${channel.id}"
}

private fun expandedHeroImageUrl(item: HeroItem, metadata: HomeHeroMetadata?): String? = when (item) {
    is HeroItem.MovieHero ->
        metadata?.backdropUrl
            ?: item.movie.backdropUrl?.takeIf { it.isNotBlank() }
            ?: item.movie.posterUrl?.takeIf { it.isNotBlank() }
    is HeroItem.SeriesHero ->
        metadata?.backdropUrl
            ?: item.series.backdropUrl?.takeIf { it.isNotBlank() }
            ?: item.series.posterUrl?.takeIf { it.isNotBlank() }
    is HeroItem.LiveHero -> item.channel.logoUrl?.takeIf { it.isNotBlank() }
}

private fun expandedHeroPlot(item: HeroItem, metadata: HomeHeroMetadata?): String? = when (item) {
    is HeroItem.MovieHero -> metadata?.plot ?: item.movie.plot?.takeIf { it.isNotBlank() }
    is HeroItem.SeriesHero -> metadata?.plot ?: item.episode.plot?.takeIf { it.isNotBlank() } ?: item.series.plot?.takeIf { it.isNotBlank() }
    is HeroItem.LiveHero -> null
}

@Composable
private fun TrendingHeroSection(
    items: List<TrendingHomeItem>,
    activeIndex: Int,
    seasonCounts: Map<Long, Int>,
    primaryFocusRequester: FocusRequester,
    detailsFocusRequester: FocusRequester,
    onNavigate: (Int) -> Unit,
    onActivate: (TrendingHomeItem) -> Unit,
    onDetails: (TrendingHomeItem) -> Unit,
    onFocus: () -> Unit,
    onSectionFocusChanged: (Boolean) -> Unit,
    onContainerDown: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    val item = items.getOrNull(activeIndex) ?: return
    val movieLabel = stringResource(R.string.home_trending_movie)
    val seriesLabel = stringResource(R.string.home_trending_series)
    // ZenPlay: the carousel is the year's new releases from the user's own lists, not TMDB's chart.
    val sectionLabel = stringResource(R.string.zenplay_home_trending)
    val playLabel = stringResource(R.string.home_trending_play)
    val openEpisodesLabel = stringResource(R.string.home_trending_open_episodes)
    val detailsLabel = stringResource(R.string.home_trending_more_details)
    var actionButtonsFocused by remember { mutableStateOf(false) }
    var focusedActionIndex by remember { mutableIntStateOf(0) }
    var resetClock by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    val intervalMs = 10_000L

    fun navigate(delta: Int) {
        if (items.isEmpty()) return
        onNavigate(wrappedTrendingIndex(activeIndex, delta, items.size))
        progress = 0f
        resetClock++
    }

    LaunchedEffect(activeIndex, actionButtonsFocused, resetClock, items.size) {
        // Focus is the television's interaction signal. Freeze the current progress while the user
        // reads or operates Play/Details, then resume from the same point after focus leaves.
        if (!shouldAutoAdvanceTrending(items.size, actionButtonsFocused)) return@LaunchedEffect
        val startProgress = progress.coerceIn(0f, 1f)
        val duration = (intervalMs * (1f - startProgress)).toLong().coerceAtLeast(1L)
        val startedAt = System.nanoTime()
        while (true) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            progress = (startProgress + (1f - startProgress) * elapsedMs.toFloat() / duration).coerceIn(0f, 1f)
            if (elapsedMs >= duration) break
            kotlinx.coroutines.delay(80L)
        }
        progress = 0f
        onNavigate(wrappedTrendingIndex(activeIndex, 1, items.size))
    }

    val snapshot = item.snapshot
    val backdrop = MetadataImages.backdrop(snapshot.backdropPath, size = "w1280")
        ?: when (item) {
            is TrendingHomeItem.Movie -> item.movie.backdropUrl ?: item.movie.posterUrl
            is TrendingHomeItem.Series -> item.series.backdropUrl ?: item.series.posterUrl
        }
    val displaySignals = ProviderVariantParser.displaySignals(snapshot.providerRawName)
    val typeLabel = if (item is TrendingHomeItem.Movie) movieLabel else seriesLabel
    val trendRank = displayableTrendingRank(snapshot.trendingRank, snapshot.generationId)
    val seasonCount = (item as? TrendingHomeItem.Series)?.let { seasonCounts[it.series.id] }

    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val heroHeight = tvTrendingHeroHeightDp(screenHeightDp).dp
    val compactHero = screenHeightDp < 650

    val heroShape = RoundedCornerShape(24.dp)
    Column(modifier = modifier) {
        HomeSectionHeader(
            title = sectionLabel,
            prominent = true,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(if (compactHero) 10.dp else 14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .clip(heroShape)
                .background(colors.surfaceContainerLowest)
                .border(
                    width = 1.dp,
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(
                            ZenPlayHeroBlue.copy(alpha = 0.48f),
                            Color.White.copy(alpha = 0.10f),
                            ZenPlayHeroPurple.copy(alpha = 0.42f),
                        ),
                    ),
                    shape = heroShape,
                )
                .onFocusChanged {
                    onSectionFocusChanged(it.hasFocus)
                    if (it.hasFocus) onFocus()
                }
                .focusGroup(),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (!backdrop.isNullOrBlank()) {
                    AsyncImage(
                        model = backdrop,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // A restrained brand wash ties unrelated provider artwork into ZenPlay's palette.
                // It is one cached gradient draw, not a blur or a second decoded image.
                Box(
                    modifier = Modifier.fillMaxSize().gradientWash(
                        vertical = false,
                        0f to ZenPlayHeroBlue.copy(alpha = 0.10f),
                        0.60f to Color.Transparent,
                        1f to ZenPlayHeroPurple.copy(alpha = 0.12f),
                    ),
                )
                Box(
                    modifier = Modifier.fillMaxSize().gradientWash(
                        vertical = false,
                        0f to colors.surfaceContainerLowest.copy(alpha = 0.98f),
                        0.58f to colors.surfaceContainerLowest.copy(alpha = 0.68f),
                        1f to Color.Transparent,
                    ),
                )
                Box(
                    modifier = Modifier.fillMaxSize().gradientWash(
                        vertical = true,
                        0.58f to Color.Transparent,
                        1f to colors.surfaceContainerLowest,
                    ),
                )

                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(if (compactHero) 0.74f else 0.64f)
                        .padding(
                            horizontal = if (compactHero) 26.dp else 38.dp,
                            vertical = if (compactHero) 16.dp else 24.dp,
                        ),
                    verticalArrangement = Arrangement.Center,
                ) {
                    trendRank?.let { rank ->
                        TrendingRankBadge(stringResource(R.string.zenplay_home_trending_rank, rank))
                        Spacer(Modifier.height(if (compactHero) 7.dp else 10.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        TrendingTypeBadge(typeLabel)
                        snapshot.year?.let {
                            Text(it.toString(), style = MaterialTheme.typography.titleSmall, color = Color(0xFFD4DED9))
                        }
                        snapshot.rating?.let {
                            Text("•", style = MaterialTheme.typography.titleSmall, color = Color(0xFFD4DED9))
                            Text(
                                text = stringResource(R.string.content_rating, it),
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFFFFE071),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.height(if (compactHero) 5.dp else 8.dp))
                    Text(
                        text = snapshot.localizedTitle,
                        style = if (compactHero) MaterialTheme.typography.headlineMedium
                        else MaterialTheme.typography.headlineLarge,
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    snapshot.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                        Spacer(Modifier.height(if (compactHero) 6.dp else 10.dp))
                        Text(
                            overview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = if (compactHero) 2 else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 680.dp),
                        )
                    }
                    if (displaySignals.quality.label != null || displaySignals.capabilities.isNotEmpty() || seasonCount != null) {
                        Spacer(Modifier.height(if (compactHero) 7.dp else 11.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            displaySignals.quality.label?.let { TrendingBadge(it, primary = true) }
                            displaySignals.capabilities.forEach { TrendingBadge(it) }
                            seasonCount?.let {
                                TrendingBadge(pluralStringResource(R.plurals.home_trending_seasons, it, it))
                            }
                        }
                    }
                    Spacer(Modifier.height(if (compactHero) 8.dp else 13.dp))
                    Row(
                        modifier = Modifier
                            .onFocusChanged { actionButtonsFocused = it.hasFocus }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    event.key == Key.DirectionLeft && focusedActionIndex == 0 && items.size > 1 -> {
                                        navigate(-1)
                                        true
                                    }
                                    event.key == Key.DirectionRight && focusedActionIndex == 1 && items.size > 1 -> {
                                        navigate(1)
                                        true
                                    }
                                    event.key == Key.DirectionDown && onContainerDown != null -> {
                                        onContainerDown()
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrendingActionButton(
                            label = if (item is TrendingHomeItem.Movie) playLabel else openEpisodesLabel,
                            onClick = { onActivate(item) },
                            icon = if (item is TrendingHomeItem.Movie) OwnTVIcon.PLAY else OwnTVIcon.SERIES,
                            primary = true,
                            modifier = Modifier
                                .focusRequester(primaryFocusRequester)
                                .onFocusChanged { if (it.hasFocus) focusedActionIndex = 0 },
                        )
                        TrendingActionButton(
                            label = detailsLabel,
                            onClick = { onDetails(item) },
                            icon = OwnTVIcon.INFO,
                            modifier = Modifier
                                .focusRequester(detailsFocusRequester)
                                .onFocusChanged { if (it.hasFocus) focusedActionIndex = 1 },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.indices.forEach { index ->
                    Box(
                        Modifier
                            .width(if (index == activeIndex) 22.dp else 7.dp)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == activeIndex) {
                                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                                        listOf(ZenPlayHeroBlue, ZenPlayHeroPurple),
                                    )
                                } else {
                                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                                        listOf(
                                            colors.onSurfaceVariant.copy(alpha = 0.32f),
                                            colors.onSurfaceVariant.copy(alpha = 0.32f),
                                        ),
                                    )
                                },
                            ),
                    )
                    if (index != items.lastIndex) Spacer(Modifier.width(7.dp))
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(colors.surfaceContainerHigh)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(ZenPlayHeroBlue, ZenPlayHeroPurple),
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun TrendingRankBadge(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xB80A1123))
            .border(1.dp, ZenPlayHeroBlue.copy(alpha = 0.58f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun TrendingTypeBadge(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = ZenPlayHeroSilver,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.11f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun TrendingMatchBadge(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFFBDECA5),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x3659AD2F))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TrendingBadge(label: String, primary: Boolean = false) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (primary) ZenPlayHeroBlue else ZenPlayHeroSilver,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0x8C06100D))
            .border(
                1.dp,
                if (primary) ZenPlayHeroPurple.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.16f),
                RoundedCornerShape(7.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TrendingActionButton(
    label: String,
    icon: OwnTVIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        unfocusedContainerColor = if (primary) colors.primary else Color.White.copy(alpha = 0.12f),
        focusedContainerColor = if (primary) colors.primaryContainer else colors.surfaceContainerHigh,
        modifier = modifier
            .height(42.dp),
    ) { focused ->
        val contentColor = if (primary && !focused) Color(0xFF071327) else colors.onSurface
        Row(
            modifier = Modifier.padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OwnTVIcon(icon, tint = contentColor, modifier = Modifier.size(16.dp), filled = primary)
            Text(label, style = MaterialTheme.typography.labelLarge, color = contentColor, fontWeight = FontWeight.Bold)
        }
    }
}

private val ZenPlayHeroSilver = Color(0xFFE8EDF7)
private val ZenPlayHeroBlue = Color(0xFF62AAFF)
private val ZenPlayHeroPurple = Color(0xFFB77CFF)

@Composable
private fun TrendingHomeItem.toDetailsUi(meta: MetadataCacheEntity?, tmdbWins: Boolean): MediaDetailsUi {
    val snapshot = snapshot
    val providerPoster = when (this) {
        is TrendingHomeItem.Movie -> movie.posterUrl
        is TrendingHomeItem.Series -> series.posterUrl
    }
    val providerBackdrop = when (this) {
        is TrendingHomeItem.Movie -> movie.backdropUrl
        is TrendingHomeItem.Series -> series.backdropUrl
    }
    val providerTitle = when (this) {
        is TrendingHomeItem.Movie -> movie.name
        is TrendingHomeItem.Series -> series.name
    }
    val providerPlot = when (this) {
        is TrendingHomeItem.Movie -> movie.plot?.takeIf { it.isNotBlank() }
        is TrendingHomeItem.Series -> series.plot?.takeIf { it.isNotBlank() }
    }
    val tmdbPlot = meta?.overview?.takeIf { it.isNotBlank() } ?: snapshot.overview
    return MediaDetailsUi(
        title = providerTitle,
        subtitle = stringResource(if (this is TrendingHomeItem.Movie) R.string.home_trending_movie else R.string.home_trending_series),
        backdropUrl = MetadataImages.backdrop(meta?.backdropPath ?: snapshot.backdropPath, size = "w1280") ?: providerBackdrop,
        posterUrl = if (tmdbWins) {
            MetadataImages.poster(meta?.posterPath ?: snapshot.posterPath, size = "w500") ?: providerPoster
        } else {
            providerPoster ?: MetadataImages.poster(meta?.posterPath ?: snapshot.posterPath, size = "w500")
        },
        metaLine = listOfNotNull(snapshot.year?.toString(), snapshot.rating?.let { stringResource(R.string.content_rating, it) }).joinToString(" · "),
        genres = trendingJsonList(meta?.genresJson),
        plot = if (tmdbWins) tmdbPlot ?: providerPlot else providerPlot ?: tmdbPlot,
        cast = tv.own.owntv.core.metadata.MetadataCast.parse(meta?.castJson),
    )
}

private fun trendingJsonList(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = org.json.JSONArray(json)
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroRowSection(
    items: List<HeroItem>,
    activeHeroIndex: Int,
    expandedIndex: Int,
    heroPreviewEngine: HeroPreviewEngine,
    engineState: HeroPreviewEngine.State,
    heroFocusRequester: FocusRequester,
    heroMetadata: Map<String, HomeHeroMetadata>,
    onHeroFocusChanged: (index: Int, hasFocus: Boolean) -> Unit,
    onPlay: (HeroItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val approxRowWidth = screenWidthDp - Dimens.SidebarWidthCollapsed - Dimens.HomeRowPaddingH
    val maxCardHeight = (approxRowWidth - Dimens.HeroBaseWidth - Dimens.HeroGap) * 9f / 16f
    val cardHeight = maxCardHeight.coerceIn(Dimens.HeroMinCardHeight, Dimens.HeroMaxCardHeight)
    val posterHeight = cardHeight - Dimens.HeroMetaHeight
    val expandedWidth = cardHeight * 16f / 9f
    val cardShape = RoundedCornerShape(Dimens.HeroCardCorner)
    val posterClip = RoundedCornerShape(Dimens.HeroPosterCorner)

    var rowTopLeftInRoot by remember { mutableStateOf(Offset.Zero) }
    var previewRectInRowPx by remember { mutableStateOf<Rect?>(null) }
    var localFocusedIndex by remember { mutableStateOf(-1) }
    var rowWidthDp by remember { mutableStateOf(0.dp) }
    var alignToActiveHeroKey by remember { mutableStateOf<String?>(null) }
    val heroRowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val itemsSignature = remember(items) { items.joinToString(separator = "|") { it.heroKey() } }
    val activeHeroKey = items.getOrNull(activeHeroIndex)?.heroKey()

    LaunchedEffect(itemsSignature) {
        if (activeHeroIndex !in items.indices) return@LaunchedEffect

        alignToActiveHeroKey = activeHeroKey
        heroRowState.scrollToItem(activeHeroIndex)

        if (activeHeroIndex == 0 && localFocusedIndex >= 0 && localFocusedIndex != activeHeroIndex) {
            localFocusedIndex = activeHeroIndex
            runCatching { heroFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(expandedIndex, items.size) {
        // The rect is only ever written by the expanded card's onGloballyPositioned; drop it on collapse
        // so the next expansion can't flash its overlay at the previous card's stale position.
        if (expandedIndex < 0) {
            previewRectInRowPx = null
        } else if (expandedIndex < items.size) {
            heroRowState.animateScrollToItem(expandedIndex)
        }
    }

    LaunchedEffect(localFocusedIndex) {
        if (localFocusedIndex < 0) return@LaunchedEffect
        heroRowState.animateScrollToItem(localFocusedIndex)
    }

    val endPadding = (rowWidthDp - Dimens.HeroBaseWidth - Dimens.HomeRowPaddingH).coerceAtLeast(Dimens.HomeRowPaddingH)

    Column(modifier = modifier) {
        HomeSectionHeader(
            title = stringResource(R.string.home_keep_watching),
            modifier = Modifier.padding(horizontal = Dimens.HomeRowPaddingH),
        )
        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .onGloballyPositioned {
                    rowTopLeftInRoot = it.positionInRoot()
                    rowWidthDp = with(density) { it.size.width.toDp() }
                },
        ) {
            LazyRow(
                state = heroRowState,
                horizontalArrangement = Arrangement.spacedBy(Dimens.HeroGap),
                contentPadding = PaddingValues(start = Dimens.HomeRowPaddingH, end = endPadding),
                modifier = Modifier
                    .fillMaxSize()
                    .focusProperties {
                        onEnter = {
                            if (
                                requestedFocusDirection == FocusDirection.Down ||
                                requestedFocusDirection == FocusDirection.Up
                            ) {
                                scope.launch {
                                    heroRowState.scrollToItem(0)
                                    runCatching { heroFocusRequester.requestFocus() }
                                }
                                cancelFocusChange()
                            }
                        }
                    },
            ) {
                itemsIndexed(
                    items,
                    key = { _, item -> item.heroKey() },
                ) { index, item ->
                    val isExpanded = index == expandedIndex
                    val targetWidth = if (isExpanded) expandedWidth else Dimens.HeroBaseWidth
                    val width by animateDpAsState(
                        targetValue = targetWidth,
                        animationSpec = tween(durationMillis = if (isExpanded) 500 else 150),
                        label = "heroCardWidth",
                    )

                    val imageUrl = when (item) {
                        is HeroItem.MovieHero -> item.movie.posterUrl
                        is HeroItem.SeriesHero -> item.series.posterUrl
                        is HeroItem.LiveHero -> item.channel.logoUrl
                    }
                    val itemMetadata = heroMetadata[item.heroKey()]
                    val expandedImageUrl = expandedHeroImageUrl(item, itemMetadata)

                    val heroGlowColor = colors.focusGlow
                    Box(
                        modifier = Modifier
                            .height(cardHeight)
                            .width(width)
                            .then(if (isExpanded) Modifier.zIndex(1f) else Modifier)
                            .then(
                                if (isExpanded) Modifier.onGloballyPositioned { coords ->
                                    val b = coords.boundsInRoot()
                                    previewRectInRowPx = Rect(
                                        left = b.left - rowTopLeftInRoot.x,
                                        top = b.top - rowTopLeftInRoot.y,
                                        right = b.right - rowTopLeftInRoot.x,
                                        bottom = b.bottom - rowTopLeftInRoot.y,
                                    )
                                } else Modifier
                            ),
                    ) {
                        FocusableSurface(
                            onClick = { onPlay(item) },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .width(width)
                                .height(cardHeight)
                                .then(if (index == 0) Modifier.focusRequester(heroFocusRequester) else Modifier)
                                .onFocusChanged { fs ->
                                    val redirectToActiveHero = fs.hasFocus &&
                                        activeHeroIndex == 0 &&
                                        index != activeHeroIndex &&
                                        alignToActiveHeroKey == activeHeroKey
                                    if (fs.hasFocus) {
                                        if (redirectToActiveHero) {
                                            scope.launch {
                                                heroRowState.scrollToItem(activeHeroIndex)
                                                runCatching { heroFocusRequester.requestFocus() }
                                            }
                                        } else {
                                            alignToActiveHeroKey = null
                                            localFocusedIndex = index
                                        }
                                    }
                                    if (!redirectToActiveHero) onHeroFocusChanged(index, fs.hasFocus)
                                }
                                .then(
                                    if (isExpanded) Modifier.drawBehind {
                                        val radius = 18.dp.toPx()
                                        drawIntoCanvas { canvas ->
                                            val paint = android.graphics.Paint().apply {
                                                isAntiAlias = true
                                                color = heroGlowColor.toArgb()
                                                maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
                                            }
                                            canvas.nativeCanvas.drawRoundRect(
                                                0f, 0f, size.width, size.height,
                                                Dimens.HeroCardCorner.toPx(), Dimens.HeroCardCorner.toPx(),
                                                paint,
                                            )
                                        }
                                    } else Modifier
                                ),
                            shape = cardShape,
                            focusedScale = 1f,
                            glowElevation = if (isExpanded) 0 else 10,
                            focusedContainerColor = colors.surfaceContainerHigh,
                            unfocusedContainerColor = colors.surfaceContainerHigh,
                            selectedContainerColor = colors.surfaceContainerHigh,
                            contentAlignment = Alignment.Center,
                            surface = GlassSurface.CARDS,
                        ) { focused ->
                            if (isExpanded) {
                                // No blurred backdrop here: the preview overlay covers this card as soon
                                // as previewRectInRowPx is known and renders the blur itself — doubling the
                                // blur underneath just costs frames on TV hardware.
                                Box(Modifier.fillMaxSize().background(Color.Black)) {
                                    val cardImageUrl = expandedImageUrl ?: imageUrl
                                    if (!cardImageUrl.isNullOrBlank()) {
                                        if (item is HeroItem.LiveHero) {
                                            AsyncImage(
                                                model = cardImageUrl,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize().blur(20.dp),
                                                contentScale = ContentScale.Crop,
                                                alpha = 0.5f,
                                            )
                                            AsyncImage(
                                                model = cardImageUrl,
                                                contentDescription = null,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier
                                                    .align(Alignment.Center)
                                                    .size(80.dp),
                                            )
                                        } else {
                                            AsyncImage(
                                                model = cardImageUrl,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    } else {
                                        Box(
                                            Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            val fallback = when (item) {
                                                is HeroItem.MovieHero -> OwnTVIcon.MOVIES
                                                is HeroItem.SeriesHero -> OwnTVIcon.SERIES
                                                is HeroItem.LiveHero -> OwnTVIcon.LIVE_TV
                                            }
                                            OwnTVIcon(fallback, tint = colors.onSurfaceVariant, modifier = Modifier.size(64.dp))
                                        }
                                    }
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(posterHeight)
                                            .clip(posterClip)
                                            .background(colors.surfaceContainerLowest),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (!imageUrl.isNullOrBlank()) {
                                            if (item is HeroItem.LiveHero) {
                                                AsyncImage(
                                                    model = imageUrl,
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxSize().blur(20.dp),
                                                    contentScale = ContentScale.Crop,
                                                    alpha = 0.5f,
                                                )
                                                AsyncImage(
                                                    model = imageUrl,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(80.dp),
                                                    contentScale = ContentScale.Fit,
                                                )
                                            } else {
                                                AsyncImage(
                                                    model = imageUrl,
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop,
                                                )
                                            }
                                        } else {
                                            val fallback = when (item) {
                                                is HeroItem.MovieHero -> OwnTVIcon.MOVIES
                                                is HeroItem.SeriesHero -> OwnTVIcon.SERIES
                                                is HeroItem.LiveHero -> OwnTVIcon.LIVE_TV
                                            }
                                            OwnTVIcon(fallback, tint = colors.onSurfaceVariant, modifier = Modifier.size(42.dp))
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))
                                    val title = when (item) {
                                        is HeroItem.MovieHero -> item.item.title
                                        is HeroItem.SeriesHero -> item.item.title
                                        is HeroItem.LiveHero -> item.channel.name
                                    }
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (focused) colors.primary else colors.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )

                                    if (item.durationMs > 0) {
                                        Spacer(Modifier.height(6.dp))
                                        val fraction = (item.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(100))
                                                .background(Color.Black.copy(alpha = 0.25f)),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(fraction)
                                                    .height(4.dp)
                                                    .background(colors.primary),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val rect = previewRectInRowPx
            if (rect != null && expandedIndex >= 0) {
                val expandedItem = items.getOrNull(expandedIndex)
                if (expandedItem != null) {
                    val ox = with(density) { rect.left.toDp() }
                    val oy = with(density) { rect.top.toDp() }
                    val ow = with(density) { rect.width.toDp() }
                    val oh = with(density) { rect.height.toDp() }

                    Box(
                        modifier = Modifier
                            .focusProperties { canFocus = false }
                            .absoluteOffset(x = ox, y = oy)
                            .width(ow)
                            .height(oh)
                            .clip(cardShape),
                    ) {
                        Box(
                            Modifier.fillMaxSize().background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) {
                            HeroPreviewSurface(
                                engine = heroPreviewEngine,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (engineState != HeroPreviewEngine.State.PLAYING) {
                                // Opaque cover: the SurfaceView below holds the previous preview's last
                                // frame after stop(), and the parent's black background is punched out by
                                // the SurfaceView. The loading images and fallback icon rely on this layer
                                // to hide it.
                                Box(Modifier.fillMaxSize().background(Color.Black))
                                val expandedMeta = heroMetadata[expandedItem.heroKey()]
                                val artUrl = expandedHeroImageUrl(expandedItem, expandedMeta)
                                if (!artUrl.isNullOrBlank()) {
                                    if (expandedItem is HeroItem.LiveHero) {
                                        AsyncImage(
                                            model = artUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize().blur(20.dp),
                                            contentScale = ContentScale.Crop,
                                            alpha = 0.5f,
                                        )
                                        AsyncImage(
                                            model = artUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.size(80.dp),
                                        )
                                    } else {
                                        AsyncImage(
                                            model = artUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                } else {
                                    val fallback = when (expandedItem) {
                                        is HeroItem.MovieHero -> OwnTVIcon.MOVIES
                                        is HeroItem.SeriesHero -> OwnTVIcon.SERIES
                                        is HeroItem.LiveHero -> OwnTVIcon.LIVE_TV
                                    }
                                    OwnTVIcon(fallback, tint = colors.onSurfaceVariant, modifier = Modifier.size(64.dp))
                                }
                            }
                        }

                        Box(
                            Modifier
                                .fillMaxSize()
                                .gradientWash(
                                    vertical = true,
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.86f),
                                    ),
                                ),
                        )

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                                .widthIn(max = Dimens.HeroOverlayMaxWidth),
                        ) {
                            val expandedMeta = heroMetadata[expandedItem.heroKey()]
                            val title = when (expandedItem) {
                                is HeroItem.MovieHero -> expandedItem.item.title
                                is HeroItem.SeriesHero -> expandedItem.item.title
                                is HeroItem.LiveHero -> expandedItem.channel.name
                            }
                            val logoUrl = expandedMeta?.logoUrl?.takeIf { expandedItem !is HeroItem.LiveHero }
                            if (!logoUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = logoUrl,
                                    contentDescription = title,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.width(260.dp).height(58.dp),
                                )
                            } else {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            val subtitle = when (expandedItem) {
                                is HeroItem.MovieHero ->
                                    expandedItem.item.subtitle
                                        ?: expandedItem.movie.year?.let { localizedInteger(it, grouping = false) }.orEmpty()
                                is HeroItem.SeriesHero ->
                                    expandedItem.item.subtitle.orEmpty()
                                is HeroItem.LiveHero -> stringResource(R.string.home_recent_live)
                            }
                            if (subtitle.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            val statText = heroStatLabel(expandedItem, System.currentTimeMillis())
                            if (statText != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = statText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            val plot = expandedHeroPlot(expandedItem, expandedMeta)
                            if (!plot.isNullOrBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = plot,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Spacer(Modifier.height(10.dp))
                            OwnTVButton(
                                label = when (expandedItem.watchNextType) {
                                    LauncherWatchNextType.NEXT -> stringResource(R.string.home_play_next)
                                    LauncherWatchNextType.CONTINUE ->
                                        if (expandedItem is HeroItem.LiveHero) stringResource(R.string.home_tune_in) else stringResource(R.string.home_resume)
                                },
                                onClick = { onPlay(expandedItem) },
                                modifier = Modifier.focusProperties { canFocus = false },
                                style = OwnTVButtonStyle.SECONDARY,
                                enabled = true,
                            )
                        }

                        if (expandedItem.durationMs > 0) {
                            val fraction = (expandedItem.positionMs.toFloat() / expandedItem.durationMs.toFloat()).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .height(Dimens.HeroProgressHeight)
                                    .background(Color.Black.copy(alpha = 0.35f)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .height(Dimens.HeroProgressHeight)
                                        .background(colors.primary),
                                )
                            }
                        }

                        if (engineState == HeroPreviewEngine.State.LOADING) {
                            OwnTVSpinner(
                                sizeDp = 18,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 16.dp, bottom = 16.dp)
                                    .alpha(0.3f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun heroStatLabel(item: HeroItem, nowMs: Long): String? =
    when (item) {
        is HeroItem.MovieHero,
        is HeroItem.SeriesHero -> finishByLabel(LocalContext.current, item.positionMs, item.durationMs, nowMs)
        is HeroItem.LiveHero -> relativeLastWatchedLabel(item.lastEngagementAt, nowMs)
    }

@Composable
private fun finishByLabel(context: Context, positionMs: Long, durationMs: Long, nowMs: Long): String? {
    if (durationMs <= 0) return null

    val safePosition = positionMs.coerceIn(0L, durationMs)
    val remainingMs = durationMs - safePosition
    if (remainingMs <= 0L) return null

    val finishMs = roundUpToNextQuarterHour(nowMs + remainingMs)
    val time = formatSystemTime(context, finishMs)
    return stringResource(R.string.home_finish_by, time)
}

private fun roundUpToNextQuarterHour(ms: Long): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = ms
    }
    val minute = calendar.get(Calendar.MINUTE)
    val second = calendar.get(Calendar.SECOND)
    val millisecond = calendar.get(Calendar.MILLISECOND)
    val remainder = minute % 15
    val shouldAdvance = remainder != 0 || second != 0 || millisecond != 0

    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)

    if (shouldAdvance) {
        val minutesToAdd = if (remainder == 0) 15 else 15 - remainder
        calendar.add(Calendar.MINUTE, minutesToAdd)
    }

    return calendar.timeInMillis
}

@Composable
private fun relativeLastWatchedLabel(lastEngagementAt: Long, nowMs: Long): String {
    val elapsedMs = nowMs - lastEngagementAt
    if (elapsedMs < 60_000L) return stringResource(R.string.home_last_watched_now)

    val elapsedMinutes = elapsedMs / 60_000L
    if (elapsedMinutes < 60L) {
        return pluralStringResource(R.plurals.home_last_watched_minutes, elapsedMinutes.toInt(), elapsedMinutes.toInt())
    }

    val elapsedHours = elapsedMinutes / 60L
    if (elapsedHours < 24L) {
        return pluralStringResource(R.plurals.home_last_watched_hours, elapsedHours.toInt(), elapsedHours.toInt())
    }

    val elapsedDays = elapsedHours / 24L
    return pluralStringResource(R.plurals.home_last_watched_days, elapsedDays.toInt(), elapsedDays.toInt())
}

@Composable
private fun HeroPreviewSurface(
    engine: HeroPreviewEngine,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.view.SurfaceView(ctx).apply {
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) = engine.setSurface(holder.surface)
                    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = engine.setSurface(null)
                })
            }
        },
        update = { it.keepScreenOn = false },
    )
}

@Composable
private fun ContinueWatchingRow(
    title: String,
    items: List<LauncherContinuationItem>,
    posterOverrides: Map<String, String> = emptyMap(),
    landscapeTiles: Boolean = false,
    onItemClick: (LauncherContinuationItem) -> Unit,
    onFocus: () -> Unit,
    onItemFocus: (LauncherContinuationItem) -> Unit = { onFocus() },
    firstItemFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        HomeSectionHeader(
            title = title,
            modifier = Modifier.padding(horizontal = Dimens.HomeRowPaddingH),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = Dimens.HomeRowPaddingH),
            modifier = Modifier.focusGroup(),
        ) {
            itemsIndexed(items, key = { _, item -> item.stableKey }) { index, item ->
                val itemModifier = when {
                    firstItemFocusRequester != null && index == 0 -> Modifier.focusRequester(firstItemFocusRequester)
                    else -> Modifier
                }
                val progress = continuationProgress(item)
                if (landscapeTiles) {
                    val overrideImageUrl = posterOverrides[item.stableKey]
                    Box(Modifier.width(275.dp)) {
                        LandscapeContinuationCard(
                            imageUrl = overrideImageUrl ?: item.posterUrl,
                            cropImage = !overrideImageUrl.isNullOrBlank(),
                            title = item.title,
                            chipText = seasonEpisodeChip(item),
                            progressFraction = progress,
                            modifier = itemModifier,
                            onFocus = { onItemFocus(item) },
                            onClick = { onItemClick(item) },
                        )
                    }
                } else {
                    Box(Modifier.width(150.dp)) {
                        PosterCard(
                            posterUrl = posterOverrides[item.stableKey] ?: item.posterUrl,
                            title = item.title,
                            progressFraction = progress,
                            modifier = itemModifier,
                            onFocus = { onItemFocus(item) },
                            onClick = { onItemClick(item) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Now Trending drawn as posters — the alternative to the hero, for anyone who wants Home to be one
 * consistent set of rows. Read-only, like the hero: core's worker decides what is in it.
 */
@Composable
private fun TrendingPosterRow(
    title: String,
    items: List<TrendingHomeItem>,
    onItemClick: (TrendingHomeItem) -> Unit,
    onFocus: () -> Unit,
    firstItemFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HomeSectionHeader(
            title = title,
            modifier = Modifier.padding(horizontal = Dimens.HomeRowPaddingH),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = Dimens.HomeRowPaddingH),
            modifier = Modifier.focusGroup(),
        ) {
            itemsIndexed(items, key = { _, item -> item.stableKey }) { index, item ->
                val itemModifier =
                    if (firstItemFocusRequester != null && index == 0) {
                        Modifier.focusRequester(firstItemFocusRequester)
                    } else {
                        Modifier
                    }
                Box(Modifier.width(150.dp)) {
                    PosterCard(
                        posterUrl = when (item) {
                            is TrendingHomeItem.Movie -> item.movie.posterUrl
                            is TrendingHomeItem.Series -> item.series.posterUrl
                        },
                        title = when (item) {
                            is TrendingHomeItem.Movie -> item.movie.name
                            is TrendingHomeItem.Series -> item.series.name
                        },
                        progressFraction = null,
                        modifier = itemModifier,
                        onFocus = onFocus,
                        onClick = { onItemClick(item) },
                    )
                }
            }
        }
    }
}

private fun continuationProgress(item: LauncherContinuationItem): Float? =
    if (item.durationMs > 0) {
        (item.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

@Composable
private fun seasonEpisodeChip(item: LauncherContinuationItem): String? {
    val season = item.seasonNumber?.takeIf { it > 0 }
    val episode = item.episodeNumber?.takeIf { it > 0 }
    return when {
        season != null && episode != null -> stringResource(R.string.home_season_episode, season, episode)
        season != null -> stringResource(R.string.home_season, season)
        episode != null -> stringResource(R.string.home_episode, episode)
        else -> null
    }
}

@Composable
private fun LandscapeContinuationCard(
    imageUrl: String?,
    cropImage: Boolean,
    title: String,
    chipText: String?,
    progressFraction: Float?,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.hasFocus) onFocus() },
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.04f,
        glowElevation = 14,
        focusedContainerColor = colors.surfaceContainerHigh,
        unfocusedContainerColor = colors.surfaceContainerHigh,
        selectedContainerColor = colors.surfaceContainerHigh,
        contentAlignment = Alignment.Center,
        surface = GlassSurface.CARDS,
    ) { focused ->
        Column(modifier = Modifier.fillMaxWidth().padding(6.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceContainerLowest),
            ) {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = if (cropImage) ContentScale.Crop else ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        OwnTVIcon(OwnTVIcon.SERIES, tint = colors.onSurfaceVariant, modifier = Modifier.size(36.dp))
                    }
                }

                if (!chipText.isNullOrBlank()) {
                    Text(
                        text = chipText,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.62f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }

                if (progressFraction != null && progressFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.4f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(colors.primary),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (focused) colors.primary else colors.onSurface,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HeroFallbackPane(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester,
    onChildFocused: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .focusRequester(focusRequester)
            .focusable()
            .onFocusChanged { if (it.hasFocus) onChildFocused() }
            .clip(RoundedCornerShape(20.dp))
            .background(colors.panel)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLockup(markSize = 72, textSize = 42)
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.home_no_preview),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_continue_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyHomeState(
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    Box(
        modifier = modifier
            .focusProperties { canFocus = false }
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLockup(markSize = 84, textSize = 48)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_start_watching),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_continue_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AllRowsHiddenState(
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    Box(
        modifier = modifier
            .focusProperties { canFocus = false }
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLockup(markSize = 84, textSize = 48)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_no_rows),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_enable_rows),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Instant structure painted while Home's data loads on a cold start (see [HomeViewModel.HomeUiState.isLoading]).
 * Static placeholders only — no shimmer/animation, on purpose: this is a low-end-TV first paint, where an
 * animating skeleton would just compete with the cold DB reads for the same weak CPU/GPU we're trying to
 * unblock. Spacing/shape reuse the real rows' Dimens so the skeleton→content hand-off doesn't visibly jump.
 */
@Composable
private fun HomeSkeleton(modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    Column(
        modifier = modifier
            .background(colors.surface)
            .padding(vertical = Dimens.ScreenPaddingV),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapLarge),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.HomeRowPaddingH)
                .aspectRatio(21f / 9f)
                .clip(RoundedCornerShape(Dimens.HeroCardCorner))
                .background(colors.surfaceContainerLowest),
        )
        SkeletonRowPlaceholder(cardCount = 6, cardWidth = 150.dp, cardHeight = 220.dp)
        SkeletonRowPlaceholder(cardCount = 6, cardWidth = 180.dp, cardHeight = 100.dp)
    }
}

@Composable
private fun SkeletonRowPlaceholder(
    cardCount: Int,
    cardWidth: Dp,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val placeholder = OwnTVTheme.colors.surfaceContainerLowest
    Column(modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(start = Dimens.HomeRowPaddingH)
                .width(150.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(100))
                .background(placeholder),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.HomeRowPaddingH),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(cardCount) {
                Box(
                    modifier = Modifier
                        .width(cardWidth)
                        .height(cardHeight)
                        .clip(RoundedCornerShape(14.dp))
                        .background(placeholder),
                )
            }
        }
    }
}
