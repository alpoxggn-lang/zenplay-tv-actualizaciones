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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.home.shouldAutoAdvanceTrending
import tv.own.owntv.core.home.wrappedTrendingIndex
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.theme.MobileDimens
import tv.own.owntv.mobile.ui.theme.MobileGroupShape

/** The newest provider additions, with swipe navigation and unobtrusive ten-second rotation. */
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
    var touching by remember { mutableStateOf(false) }
    var resetClock by remember { mutableIntStateOf(0) }
    var progress by remember(itemIds) { mutableFloatStateOf(0f) }
    val item = items[activeIndex.coerceIn(items.indices)]
    var unavailable by remember(item.id) { mutableStateOf(false) }

    fun navigate(delta: Int) {
        activeIndex = wrappedTrendingIndex(activeIndex, delta, items.size)
        progress = 0f
        resetClock++
    }
    // pointerInput survives recomposition; a swipe must use the current index and catalog.
    val navigateOnSwipe by rememberUpdatedState<(Int) -> Unit> { delta -> navigate(delta) }

    LaunchedEffect(itemIds, activeIndex, touching, resetClock) {
        // Any finger-down pauses the clock without resetting it. A tap on Play/Details therefore
        // cannot change the featured title underneath the user, while a released swipe resumes.
        if (!shouldAutoAdvanceTrending(items.size, touching)) return@LaunchedEffect
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
        activeIndex = wrappedTrendingIndex(activeIndex, 1, items.size)
    }

    val backdrop = item.backdropUrl?.takeIf { it.isNotBlank() } ?: item.posterUrl
    val surface = MaterialTheme.colorScheme.surfaceContainerLowest
    val ratingColor = if (surface.luminance() < 0.5f) RatingColor else RatingColorLightSurface
    val configuration = LocalConfiguration.current
    val tablet = configuration.smallestScreenWidthDp >= 600
    val artworkAspect = trendingArtworkAspectRatio(
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
        smallestScreenWidthDp = configuration.smallestScreenWidthDp,
    )
    val posterWidth = if (tablet) 124.dp else PosterWidth
    val contentPadding = if (tablet) MobileDimens.GapLarge else MobileDimens.GapMedium
    val positionLabel = stringResource(
        R.string.zenplay_home_carousel_position,
        activeIndex + 1,
        items.size,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MobileDimens.ScreenPaddingH)
            .clip(MobileGroupShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        surface,
                        MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ),
            )
            .border(1.dp, HeroFrameBrush, MobileGroupShape)
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
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(artworkAspect)) {
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
                        0.56f to surface.copy(alpha = 0.24f),
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
                    .padding(contentPadding)
                    .width(posterWidth)
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

        Column(modifier = Modifier.padding(contentPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(5.dp).height(27.dp).clip(CircleShape).background(
                        Brush.verticalGradient(listOf(ZenPlayBlue, ZenPlayPurple)),
                    ),
                )
                Spacer(Modifier.width(MobileDimens.GapSmall))
                Text(
                    text = stringResource(R.string.zenplay_home_trending),
                    style = if (tablet) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold,
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
                        color = ratingColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(MobileDimens.GapSmall))
            Text(
                text = item.name,
                style = if (tablet) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MobileDimens.GapSmall)
                .semantics { stateDescription = positionLabel },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.indices.forEach { index ->
                Box(
                    Modifier
                        .width(if (index == activeIndex) 20.dp else 7.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == activeIndex) {
                                Brush.horizontalGradient(listOf(ZenPlayBlue, ZenPlayPurple))
                            } else {
                                Brush.horizontalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f),
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f),
                                    ),
                                )
                            },
                        ),
                )
                if (index != items.lastIndex) Spacer(Modifier.width(6.dp))
            }
        }
        if (items.size > 1) {
            Box(
                modifier = Modifier.fillMaxWidth().height(ProgressHeight)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(ProgressHeight)
                        .background(HeroAccentBrush),
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
    val container = if (primary) {
        HeroAccentBrush
    } else {
        Brush.horizontalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
            ),
        )
    }
    val content = if (primary) HeroActionInk else MaterialTheme.colorScheme.onSurface
    val outline = if (primary) {
        Color.White.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    }
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = MobileDimens.TouchTarget)
            .clip(shape)
            .background(container)
            .border(1.dp, outline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = MobileDimens.GapMedium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, fontWeight = FontWeight.Bold)
    }
}

private const val INTERVAL_MS = 10_000L
private const val TICK_MS = 80L
private const val SWIPE_SLOP_PX = 60f
private val PosterWidth = 96.dp
private val ProgressHeight = 3.dp
private val RatingColor = Color(0xFFFFE071)
private val RatingColorLightSurface = Color(0xFF725600)
private val ZenPlaySilver = Color(0xFFE8EDF7)
private val ZenPlayBlue = Color(0xFF62AAFF)
private val ZenPlayPurple = Color(0xFFB77CFF)
private val HeroActionInk = Color(0xFF071326)
private val HeroAccentBrush = Brush.horizontalGradient(listOf(ZenPlayBlue, ZenPlayPurple))
private val HeroFrameBrush = Brush.linearGradient(
    listOf(
        ZenPlaySilver.copy(alpha = 0.22f),
        ZenPlayBlue.copy(alpha = 0.12f),
        ZenPlayPurple.copy(alpha = 0.18f),
    ),
)

/** Pure layout rule kept testable across phone rotation, split-screen and tablet windows. */
internal fun trendingArtworkAspectRatio(
    screenWidthDp: Int,
    screenHeightDp: Int,
    smallestScreenWidthDp: Int,
): Float = when {
    smallestScreenWidthDp >= 600 && screenWidthDp > screenHeightDp -> 21f / 9f
    screenWidthDp > screenHeightDp -> 18f / 8f
    else -> 16f / 10f
}
