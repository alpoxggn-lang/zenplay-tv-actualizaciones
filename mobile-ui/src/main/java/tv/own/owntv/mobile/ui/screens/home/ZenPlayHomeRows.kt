package tv.own.owntv.mobile.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.home.HomeFeed
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.PosterCard
import tv.own.owntv.mobile.ui.components.SectionHeader
import tv.own.owntv.mobile.ui.theme.MobileCardShape
import tv.own.owntv.mobile.ui.theme.MobileDimens

@Immutable
data class ZenPlayHomeState(
    val feed: HomeFeed = HomeFeed(),
    val recentMovies: List<MovieEntity> = emptyList(),
    val recentSeries: List<SeriesEntity> = emptyList(),
    val featuredMovies: List<MovieEntity> = emptyList(),
    /** ZenPlay: Tendencias — this year's films and shows, steady for 24 hours. */
    val featured: List<FeaturedItem> = emptyList(),
)

internal enum class ZenPlayHomeSection { TRENDING }

/** Mobile poster interactions stay identical to the library: tap for details, hold for actions. */
@Composable
internal fun <T> RecentlyAddedRow(
    title: String,
    items: List<T>,
    itemId: (T) -> Long,
    itemName: (T) -> String,
    itemPoster: (T) -> String?,
    itemRating: (T) -> Double?,
    sharedKey: (T) -> String,
    onOpen: (T) -> Unit,
    onMenu: (T) -> Unit,
) {
    if (items.isEmpty()) {
        HomeSectionEmpty(title, stringResource(R.string.zenplay_home_catalog_empty))
        return
    }
    val configuration = LocalConfiguration.current
    val posterWidth = if (configuration.screenWidthDp >= 600 || configuration.screenWidthDp > configuration.screenHeightDp) {
        MobileDimens.PosterWidthLandscape
    } else MobileDimens.PosterWidthPortrait
    Column {
        SectionHeader(title = title)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
            contentPadding = PaddingValues(horizontal = MobileDimens.ScreenPaddingH),
        ) {
            items(items, key = itemId) { item ->
                PosterCard(
                    title = itemName(item),
                    imageUrl = itemPoster(item),
                    subtitle = itemRating(item)?.takeIf { it > 0 }?.let {
                        stringResource(R.string.content_rating, it.toFloat())
                    },
                    width = posterWidth,
                    sharedKey = sharedKey(item),
                    onClick = { onOpen(item) },
                    onLongClick = { onMenu(item) },
                )
            }
        }
    }
}

@Composable
internal fun HomeSectionEmpty(title: String, message: String) {
    Column {
        SectionHeader(title = title)
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = MobileDimens.ScreenPaddingH)
                .clip(MobileCardShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(MobileDimens.GapMedium),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
    }
}
