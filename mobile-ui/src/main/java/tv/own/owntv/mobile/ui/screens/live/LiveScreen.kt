package tv.own.owntv.mobile.ui.screens.live

import androidx.activity.compose.BackHandler
import tv.own.owntv.mobile.ui.components.MobileIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.LoadState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.live.parseLiveKey
import tv.own.owntv.core.live.serialize
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.CategoryPickerSheet
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.BrowseCategorySheet
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.mobile.ui.components.TwoPane
import tv.own.owntv.mobile.ui.components.mobileGroupPlate
import tv.own.owntv.mobile.ui.nav.isExpandedWidth
import tv.own.owntv.mobile.ui.screens.ObeyScrollToTop
import tv.own.owntv.mobile.ui.shell.LocalStreamOnScreen
import tv.own.owntv.mobile.ui.theme.MobileDimens

/**
 * Live TV opens a vertical category list. Choosing a category opens its channels; Back returns to
 * the same position in the category list. Tablets retain the channel preview after this first step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    scrollToTop: SharedFlow<String>,
    onOpenChannel: (channelId: Long, openCatchup: Boolean) -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Favourites and History already identify a folder, so they open its channels directly.
     */
    lockedKey: LiveKey? = null,
    /** ZenPlay: opens "Agenda deportiva de hoy" from the banner above the categories. */
    onOpenAgenda: (() -> Unit)? = null,
    vm: LiveViewModel = koinViewModel(),
) {
    // Locking and unlocking are one pair: the pin belongs to this screen's lifetime, not to the view
    // model's. On the television that view model is a single instance shared with the browse section,
    // so a pin left behind froze its category rail. `DisposableEffect` (not `LaunchedEffect`) also
    // means the pin is in place before the first frame, so the list never flashes the wrong folder.
    // Channels kept with "Add to Multiview" are spent by playing one. While any are waiting, a tap
    // plays rather than browses — see the tap handler below.
    val tuner: LiveTuner = koinInject()
    val multiviewSelection by tuner.multiviewSelection.collectAsStateWithLifecycle()
    val multiviewPending = multiviewSelection.isNotEmpty()

    if (lockedKey != null) {
        val pinned = lockedKey
        DisposableEffect(pinned) {
            vm.lock(pinned)
            onDispose { vm.unlock() }
        }
    }

    val storedCategories by vm.categories.collectAsStateWithLifecycle()
    val channelScope by vm.channelScope.collectAsStateWithLifecycle()
    val categories = storedCategories.filter { it.key in channelScope?.allowedKeys.orEmpty() }
    var openedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var lastProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var lastSourceIds by rememberSaveable { mutableStateOf<List<Long>?>(null) }
    val sameContext = channelScope != null && (lastProfileId == null ||
        (lastProfileId == channelScope?.profileId && lastSourceIds == channelScope?.sourceIds))
    val opened = categories.firstOrNull { sameContext && it.key.serialize() == openedCategory }
    val showingCategories = lockedKey == null && opened == null
    val requestedKey = lockedKey ?: opened?.key
    val channelFlow = remember(vm, requestedKey, channelScope) { vm.channelsFor(requestedKey, channelScope) }
    val channels = channelFlow.collectAsLazyPagingItems()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle()
    val providers by vm.providerNames.collectAsStateWithLifecycle()
    val showNumbers by vm.showChannelNumbers.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val categoryListState = rememberLazyListState()
    if (showingCategories) {
        categoryListState.ObeyScrollToTop(route = "live", scrollToTop = scrollToTop)
    } else {
        listState.ObeyScrollToTop(route = "live", scrollToTop = scrollToTop)
    }

    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var categoryPicker by remember { mutableStateOf(false) }
    var categoryMenuFor by remember { mutableStateOf<LiveCategory?>(null) }
    // Survives a rotation, so turning a tablet keeps the channel you were watching beside the list.
    var previewing by rememberSaveable { mutableStateOf<Long?>(null) }
    val twoPane = isExpandedWidth()
    val openCategory: (LiveCategory) -> Unit = { category ->
        if (category.key in channelScope?.allowedKeys.orEmpty()) {
            previewing = null
            vm.select(category.key)
            openedCategory = category.key.serialize()
        }
    }
    val backToCategories = {
        previewing = null
        openedCategory = null
        menuFor = null
        categoryMenuFor = null
    }
    BackHandler(enabled = lockedKey == null && openedCategory != null) { backToCategories() }
    // Clear the page when its profile/source changes, but preserve it through sort/customization
    // updates. The sameContext guard already prevents an old preview from composing this frame.
    LaunchedEffect(channelScope?.profileId, channelScope?.sourceIds, channelScope?.allowedKeys) {
        val scope = channelScope ?: return@LaunchedEffect
        val missingCategory = openedCategory?.let { parseLiveKey(it) !in scope.allowedKeys } == true
        if (!sameContext || missingCategory) {
            backToCategories()
            categoryPicker = false
        }
        lastProfileId = scope.profileId
        lastSourceIds = scope.sourceIds
    }
    // Restore the open folder after process recreation, even when remembering the last category
    // is disabled. A remembered selection alone never bypasses the initial category list.
    LaunchedEffect(openedCategory, lockedKey) {
        if (lockedKey == null) openedCategory?.let { parseLiveKey(it) }?.let(vm::select)
    }

    // While the pane is showing the channel, the picture is already on screen, so the shell must not
    // also put a floating window over it. Cleared on the way out, or leaving Live TV with a channel
    // open would take the mini player away on every other screen too.
    val streamOnScreen = LocalStreamOnScreen.current
    val paneHasChannel = twoPane && !showingCategories && sameContext && previewing != null
    DisposableEffect(paneHasChannel) {
        streamOnScreen.value = paneHasChannel
        onDispose { streamOnScreen.value = false }
    }

    // The guide is read for what is actually on screen. Watching the visible range rather than each
    // row means one batched query per scroll settle instead of one per row appearing.
    LaunchedEffect(listState, channels, showingCategories) {
        if (showingCategories) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices ->
                vm.loadNowPlaying(indices.mapNotNull { index ->
                    if (index < channels.itemCount) channels.peek(index) else null
                })
            }
    }

    // Changing category scrolls back to the top: the position of the old list means nothing in the new one.
    LaunchedEffect(requestedKey) { listState.scrollToItem(0) }

    val listPane = @Composable {
    Column(Modifier.fillMaxSize()) {
        if (lockedKey == null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { backToCategories() }) {
                    Icon(MobileIcons.ArrowBack, stringResource(R.string.zenplay_live_back_categories))
                }
                Text(
                    text = opened?.label().orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (channelScope == null || channels.loadState.refresh is LoadState.Loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (channels.loadState.refresh is LoadState.Error) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(MobileDimens.GapLarge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.zenplay_live_load_failed), textAlign = TextAlign.Center)
                    TextButton(onClick = channels::retry) { Text(stringResource(R.string.common_retry)) }
                }
            } else if (channels.itemCount == 0) {
                EmptyChannels()
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().mobileGroupPlate()) {
                    items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                        val channel = channels[index]
                        if (channel != null) {
                            ChannelRow(
                                channel = channel,
                                number = channel.number?.takeIf { showNumbers && it > 0 },
                                nowPlaying = nowPlaying[channel.id],
                                providerName = providers[channel.sourceId],
                                isFavorite = channel.id in favorites,
                                // Beside the list the channel opens in the pane; on a phone it is
                                // a screen of its own, which is the same screen either way.
                                onClick = {
                                    when {
                                        // Channels are waiting to become a Multiview grid, so the
                                        // tap that says "now" must actually start playing. Sent to
                                        // the channel page instead, it took two taps to open the
                                        // grid and the first one looked like it had done nothing.
                                        multiviewPending -> {
                                            // Selected, not started: the grid is about to open and
                                            // would only have to stop it again — and losing that
                                            // race is what played a second channel under the tiles.
                                            tuner.selectWithoutPlaying(channel)
                                            onOpenPlayer()
                                        }
                                        twoPane -> previewing = channel.id
                                        else -> onOpenChannel(channel.id, false)
                                    }
                                },
                                onLongClick = { menuFor = channel },
                            )
                        }
                    }
                }
            }
        }
    }
    }

    if (showingCategories) {
        LiveCategoryList(
            categories = categories,
            state = categoryListState,
            refreshing = refreshing,
            onRefresh = vm::refresh,
            onSearch = { categoryPicker = true },
            onSelect = openCategory,
            onLongClick = { categoryMenuFor = it },
            onOpenAgenda = onOpenAgenda,
            modifier = modifier,
        )
    } else if (twoPane) {
        TwoPane(
            list = listPane,
            detail = {
                val channelId = previewing.takeIf { sameContext }
                if (channelId == null) {
                    SelectAChannel()
                } else {
                    ChannelDetailScreen(
                        channelId = channelId,
                        openCatchup = false,
                        onFullscreen = onOpenPlayer,
                        // Back here empties the pane rather than leaving Live TV: the list is still
                        // on screen, so the thing the user is finished with is the channel.
                        onBack = { previewing = null },
                    )
                }
            },
            modifier = modifier,
        )
    } else {
        Box(modifier) { listPane() }
    }

    if (categoryPicker) {
        CategoryPickerSheet(
            labels = categories.map { it.label() },
            selectedIndex = -1,
            onSelect = { index -> categories.getOrNull(index)?.let(openCategory) },
            onDismiss = { categoryPicker = false },
        )
    }

    menuFor?.let { channel ->
        ChannelMenu(
            channel = channel,
            selected = requestedKey ?: LiveKey.All,
            isFavorite = channel.id in favorites,
            originName = categories.firstOrNull { it.key == requestedKey }?.label().orEmpty(),
            vm = vm,
            onOpenCatchup = { onOpenChannel(channel.id, true) },
            onDismiss = { menuFor = null },
        )
    }

    categoryMenuFor?.let { category ->
        BrowseCategorySheet(
            title = category.label(),
            onHide = { vm.hideCategory(category.key) },
            onMove = { kind -> vm.moveCategory(category.key, kind) },
            onDismiss = { categoryMenuFor = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveCategoryList(
    categories: List<LiveCategory>,
    state: LazyListState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    onSelect: (LiveCategory) -> Unit,
    onLongClick: (LiveCategory) -> Unit,
    onOpenAgenda: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        // ZenPlay: today's sports agenda, the first thing on "En vivo".
        if (onOpenAgenda != null) {
            tv.own.owntv.mobile.zenplay.ZenSportsAgendaBanner(
                onClick = onOpenAgenda,
                modifier = Modifier.padding(horizontal = MobileDimens.ScreenPaddingH, vertical = MobileDimens.GapSmall),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = MobileDimens.ScreenPaddingH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.zenplay_live_categories),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSearch) {
                Icon(MobileIcons.Search, stringResource(R.string.content_search_categories))
            }
        }
        Text(
            text = stringResource(R.string.zenplay_live_choose_category),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MobileDimens.ScreenPaddingH, vertical = MobileDimens.GapSmall),
        )
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            if (categories.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize().mobileGroupPlate()) {
                    items(categories, key = { it.key.serialize() }) { category ->
                        MobileListRow(
                            title = category.label(),
                            leading = {
                                val icon = when (category.builtIn) {
                                    LiveCategory.BuiltIn.ALL -> MobileIcons.LiveTv
                                    LiveCategory.BuiltIn.FAVORITES -> MobileIcons.Star
                                    LiveCategory.BuiltIn.HISTORY, LiveCategory.BuiltIn.CATCHUP -> MobileIcons.History
                                    null -> MobileIcons.Folder
                                }
                                Icon(icon, contentDescription = null)
                            },
                            trailing = { Icon(MobileIcons.KeyboardArrowRight, contentDescription = null) },
                            onClick = { onSelect(category) },
                            onLongClick = if (category.builtIn == null) ({ onLongClick(category) }) else null,
                        )
                    }
                }
            }
        }
    }
}

/** A translated label for the four built-in lists, the stored provider name otherwise. */
@Composable
private fun LiveCategory.label(): String = when (builtIn) {
    LiveCategory.BuiltIn.ALL -> stringResource(R.string.content_category_all_channels)
    LiveCategory.BuiltIn.FAVORITES -> stringResource(R.string.content_category_favorites)
    LiveCategory.BuiltIn.HISTORY -> stringResource(R.string.content_category_history)
    LiveCategory.BuiltIn.CATCHUP -> stringResource(R.string.content_catchup)
    null -> title.orEmpty()
}

/** The preview pane before a channel is picked — the television's own sentence for the same panel. */
@Composable
private fun SelectAChannel() {
    Box(Modifier.fillMaxSize().padding(MobileDimens.GapLarge), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.content_preview_select_channel),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyChannels() {
    Box(Modifier.fillMaxSize().padding(MobileDimens.GapLarge), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.content_no_channels_here),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One channel: its number, its logo, its name, and what is on it now.
 *
 * The second line is deliberately shared between the programme title and the playlist name — a
 * phone row has one line to spare, and which of the two is worth showing depends on whether the
 * user has a guide and more than one playlist.
 */
@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    number: Int?,
    nowPlaying: String?,
    providerName: String?,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val separator = stringResource(R.string.content_epg_bits_separator)
    val subtitle = listOfNotNull(nowPlaying, providerName).takeIf { it.isNotEmpty() }?.joinToString(separator)

    MobileListRow(
        title = channel.name,
        subtitle = subtitle,
        leading = { ChannelLogo(channel, number) },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapTiny)) {
                if (channel.catchup) {
                    Icon(
                        imageVector = MobileIcons.History,
                        contentDescription = stringResource(R.string.content_catchup),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(TRAILING_ICON),
                    )
                }
                if (isFavorite) {
                    Icon(
                        imageVector = MobileIcons.Star,
                        contentDescription = stringResource(R.string.content_category_favorites),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(TRAILING_ICON),
                    )
                }
            }
        },
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/** The logo, with the channel number under it when the Channel numbers setting is on. */
@Composable
private fun ChannelLogo(channel: ChannelEntity, number: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ChannelLogoImage(
            url = channel.displayLogoUrl,
            modifier = Modifier.size(LOGO_SIZE),
            fallback = {
                Icon(
                    imageVector = MobileIcons.LiveTv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(LOGO_SIZE),
                )
            },
        )
        if (number != null) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val LOGO_SIZE = 32.dp
private val TRAILING_ICON = 18.dp
