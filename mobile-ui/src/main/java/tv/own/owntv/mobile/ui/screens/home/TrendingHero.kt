package tv.own.owntv.mobile.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.theme.MobileDimens

/** The six newest provider additions released in 2026, with touch controls and ten-second rotation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrendingHero(
    items: List<FeaturedItem>,
    onActivate: (FeaturedItem, onUnavailable: () -> Unit) -> Unit,
    onOpenDetails: (FeaturedItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    // A sync can replace a title without changing the count: key the saved selection to actual IDs.
    val itemIds = items.map { it.id }
    var activeIndex by rememberSaveable(itemIds) { mutableIntStateOf(0) }
    var manuallyPaused by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    var resetClock by remember { mutableIntStateOf(0) }
    var progress by remember(itemIds) { mutableFloatStateOf(0f) }
    val item = items[activeIndex.coerceIn(items.indices)]
    var unavailable by remember(item.id) { mutableStateOf(false) }

    fun navigate(delta: Int) {
        activeIndex = (activeIndex + delta + items.size) % items.size
        progress = 0f
        resetClock++
    }
    // pointerInput survives recomposition; a swipe must use the current index and catalog.
    val navigateOnSwipe by rememberUpdatedState<(Int) -> Unit> { delta -> navigate(delta) }

    LaunchedEffect(itemIds, activeIndex, manuallyPaused, touching, resetClock) {
        if (manuallyPaused || touching || items.size < 2) return@LaunchedEffect
        val startProgress = progress.coerceIn(0f, 1f)
        val duration = (INTERVAL_MS * (1f - startProgress)).toLong().coerceAtLeast(1L)
        val startedAt = System.nanoTime()
        while (true) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            progress = (startProgress + (1f - startProgress) * elapsedMs.toFloat() / duration).coerceIn(0f, 1f)
            if (elapsedMs >= duration) break
            delay(TICK_MS)
        }
        progress = 0f
        activeIndex = (activeIndex + 1) % items.size
    }

    val backdrop = item.backdropUrl?.takeIf { it.isNotBlank() } ?: item.posterUrl
    val surface = MaterialTheme.colorScheme.surfaceContainerLowest

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MobileDimens.ScreenPaddingH)
            .clip(RoundedCornerShape(MobileDimens.GroupCorner))
            .background(surface)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    touching = true
                    try {
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                    } finally {
                        touching = false
                    }
                }
            }
            .pointerInput(itemIds) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (kotlin.math.abs(dragged) > SWIPE_SLOP_PX) navigateOnSwipe(if (dragged < 0) 1 else -1) },
                ) { change, amount ->
                    dragged += amount
                    change.consume()
                }
            },
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (!backdrop.isNullOrBlank()) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to surface.copy(alpha = 0.82f),
                        0.58f to surface.copy(alpha = 0.30f),
                        1f to Color.Transparent,
                    ),
                ),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.45f to Color.Transparent, 1f to surface),
                ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(MobileDimens.GapMedium)
                    .width(PosterWidth)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(MobileDimens.PosterArtCorner))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(MobileDimens.PosterArtCorner))
                    .clickable { onOpenDetails(item) },
                contentAlignment = Alignment.Center,
            ) {
                if (!item.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = if (item.isSeries) MobileIcons.Theaters else MobileIcons.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(MobileDimens.GapMedium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(MobileDimens.GapSmall))
                Text(
                    text = stringResource(R.string.zenplay_home_trending),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(MobileDimens.GapSmall))
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
                horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
            ) {
                Text(
                    text = stringResource(R.string.zenplay_home_featured_year2, FEATURED_MOVIE_YEAR) +
                        " · " + stringResource(if (item.isSeries) R.string.zenplay_kind_series else R.string.zenplay_kind_movie),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.rating?.takeIf { it > 0 }?.let {
                    Text(
                        text = stringResource(R.string.content_rating, it),
                        style = MaterialTheme.typography.titleSmall,
                        color = RatingColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(MobileDimens.GapSmall))
            Text(
                text = item.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.plot?.takeIf { it.isNotBlank() }?.let { overview ->
                Spacer(Modifier.height(MobileDimens.GapSmall))
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(MobileDimens.GapMedium))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
                verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
            ) {
                TrendingActionButton(
                    label = stringResource(
                        if (item.isSeries) R.string.home_trending_open_episodes else R.string.home_trending_play,
                    ),
                    icon = if (item.isSeries) MobileIcons.Theaters else MobileIcons.PlayArrow,
                    primary = true,
                    onClick = { onActivate(item) { unavailable = true } },
                )
                TrendingActionButton(
                    label = stringResource(R.string.home_trending_more_details),
                    icon = MobileIcons.Info,
                    onClick = { onOpenDetails(item) },
                )
            }
            if (unavailable) {
                Spacer(Modifier.height(MobileDimens.GapSmall))
                Text(
                    text = stringResource(R.string.home_trending_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = MobileDimens.GapSmall),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (items.size > 1) {
                TrendingControlButton(
                    icon = MobileIcons.SkipPrevious,
                    description = stringResource(R.string.home_trending_previous),
                    onClick = { navigate(-1) },
                )
            }
            Text(
                text = stringResource(R.string.zenplay_home_carousel_position, activeIndex + 1, items.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MobileDimens.GapSmall),
            )
            if (items.size > 1) {
                TrendingControlButton(
                    icon = if (manuallyPaused) MobileIcons.PlayArrow else MobileIcons.Pause,
                    description = stringResource(
                        if (manuallyPaused) R.string.home_trending_resume else R.string.home_trending_pause,
                    ),
                    onClick = {
                        manuallyPaused = !manuallyPaused
                        if (!manuallyPaused) {
                            progress = 0f
                            resetClock++
                        }
                    },
                )
                Spacer(Modifier.width(MobileDimens.GapSmall))
                TrendingControlButton(
                    icon = MobileIcons.SkipNext,
                    description = stringResource(R.string.home_trending_next),
                    onClick = { navigate(1) },
                )
            }
        }
        if (items.size > 1) {
            Box(
                modifier = Modifier.fillMaxWidth().height(ProgressHeight)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(ProgressHeight)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun TrendingActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val container =
        if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val content =
        if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = MobileDimens.TouchTarget)
            .clip(RoundedCornerShape(9.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = MobileDimens.GapMedium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TrendingControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(MobileDimens.TouchTarget)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

private const val INTERVAL_MS = 10_000L
private const val TICK_MS = 80L
private const val SWIPE_SLOP_PX = 60f
private val PosterWidth = 96.dp
private val ProgressHeight = 3.dp
private val RatingColor = Color(0xFFFFE071)
