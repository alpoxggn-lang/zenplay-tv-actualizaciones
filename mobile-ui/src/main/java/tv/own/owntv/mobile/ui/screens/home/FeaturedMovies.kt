package tv.own.owntv.mobile.ui.screens.home

import androidx.paging.PagingSource
import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser
import tv.own.owntv.core.database.entity.MovieEntity

/** ZenPlay: Tendencias follows the calendar — this year's releases, whatever the year is. */
internal val FEATURED_MOVIE_YEAR: Int get() = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
internal const val FEATURED_MOVIE_LIMIT = 6
internal const val FEATURED_SERIES_LIMIT = 4

/** One title in the Tendencias carousel: a film or a show, as the hero draws it. */
@androidx.compose.runtime.Immutable
data class FeaturedItem(
    val id: Long,
    val isSeries: Boolean,
    val name: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: Double?,
    val plot: String?,
)

internal fun MovieEntity.toFeatured() = FeaturedItem(id, false, name, posterUrl, backdropUrl, rating, plot)
internal fun tv.own.owntv.core.database.entity.SeriesEntity.toFeatured() =
    FeaturedItem(id, true, name, posterUrl, backdropUrl, rating, plot)

internal fun tv.own.owntv.core.database.entity.SeriesEntity.featuredReleaseYear(): Int? =
    year?.takeIf { it in 1800..2199 }
        ?: parsedYear?.takeIf { it in 1800..2199 }
        ?: ProviderCatalogMetadataParser.parse(name, providerYear = null).parsedYear

/** This year's newest shows, one row per title — the series half of Tendencias. */
internal suspend fun readFeaturedSeries(
    sourceFactory: () -> PagingSource<Int, tv.own.owntv.core.database.entity.SeriesEntity>,
    include: (tv.own.owntv.core.database.entity.SeriesEntity) -> Boolean,
): List<tv.own.owntv.core.database.entity.SeriesEntity> {
    val year = FEATURED_MOVIE_YEAR
    val selectedTitles = mutableSetOf<String>()
    return readRecentlyAdded(
        sourceFactory = { selectedTitles.clear(); sourceFactory() },
        include = { show ->
            if (!include(show) || show.featuredReleaseYear() != year) {
                false
            } else {
                val signature = show.titleSignature.ifBlank {
                    ProviderCatalogMetadataParser.parse(show.canonicalTitle.ifBlank { show.name }, providerYear = null).titleSignature
                }
                selectedTitles.add(signature)
            }
        },
        limit = FEATURED_SERIES_LIMIT,
    )
}

/** Provider metadata wins; only missing/invalid years fall back to locally parsed title metadata. */
internal fun MovieEntity.featuredReleaseYear(): Int? =
    year?.takeIf { it in 1800..2199 }
        ?: parsedYear?.takeIf { it in 1800..2199 }
        ?: ProviderCatalogMetadataParser.parse(name, providerYear = null).parsedYear

/**
 * The source is the complete active catalog, ordered by the provider's addedAt DESC (then stable
 * sortOrder/id ties). Scan past older-release or hidden titles until six eligible movies are found;
 * the ordinary 24-title recent rail must not limit this selection. No remote match is required.
 */
internal suspend fun readFeaturedMovies(
    sourceFactory: () -> PagingSource<Int, MovieEntity>,
    include: (MovieEntity) -> Boolean,
): List<MovieEntity> {
    val selectedTitles = mutableSetOf<Pair<String, Int>>()
    return readRecentlyAdded(
        sourceFactory = {
            // The reader can retry an invalidated snapshot; never carry its identities into a retry.
            selectedTitles.clear()
            sourceFactory()
        },
        include = { movie ->
            if (!include(movie) || movie.featuredReleaseYear() != FEATURED_MOVIE_YEAR) {
                false
            } else {
                val signature = movie.titleSignature.ifBlank {
                    ProviderCatalogMetadataParser.parse(
                        movie.canonicalTitle.ifBlank { movie.name }, providerYear = null,
                    ).titleSignature
                }
                // Multiple resolutions/languages of a film occupy one slot; retain the newest row.
                selectedTitles.add(signature to FEATURED_MOVIE_YEAR)
            }
        },
        limit = FEATURED_MOVIE_LIMIT,
    )
}
