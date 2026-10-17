package tv.own.owntv.mobile.ui.screens.home

import androidx.paging.PagingSource
import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser
import tv.own.owntv.core.catalog.CatalogVariantCandidate
import tv.own.owntv.core.catalog.catalogVariantCandidate
import tv.own.owntv.core.catalog.catalogVariantDisplayNames
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
    val sourceId: Long,
    val remoteId: String?,
    val variantQualifiers: List<String>,
)

internal fun MovieEntity.toFeatured(): FeaturedItem {
    val variant = catalogVariantCandidate()
    return FeaturedItem(
        id, false, name, posterUrl, backdropUrl, rating, plot,
        sourceId = sourceId, remoteId = remoteId, variantQualifiers = variant.qualifiers,
    )
}

internal fun tv.own.owntv.core.database.entity.SeriesEntity.toFeatured(): FeaturedItem {
    val variant = catalogVariantCandidate()
    return FeaturedItem(
        id, true, name, posterUrl, backdropUrl, rating, plot,
        sourceId = sourceId, remoteId = remoteId, variantQualifiers = variant.qualifiers,
    )
}

/** Adds a suffix only when two carousel entries share a title; the stored/provider title stays raw. */
internal fun List<FeaturedItem>.withVariantDisplayNames(): List<FeaturedItem> {
    val names = catalogVariantDisplayNames(map { item ->
        CatalogVariantCandidate(
            key = item.key,
            title = item.name,
            sourceId = item.sourceId,
            remoteId = item.remoteId,
            qualifiers = item.variantQualifiers,
        )
    })
    return map { item -> item.copy(name = names[item.key] ?: item.name) }
}

internal fun tv.own.owntv.core.database.entity.SeriesEntity.featuredReleaseYear(): Int? =
    year?.takeIf { it in 1800..2199 }
        ?: parsedYear?.takeIf { it in 1800..2199 }
        ?: ProviderCatalogMetadataParser.parse(name, providerYear = null).parsedYear

/** This year's newest shows. Same-name provider variants remain separate. */
internal suspend fun readFeaturedSeries(
    sourceFactory: () -> PagingSource<Int, tv.own.owntv.core.database.entity.SeriesEntity>,
    include: (tv.own.owntv.core.database.entity.SeriesEntity) -> Boolean,
): List<tv.own.owntv.core.database.entity.SeriesEntity> {
    val year = FEATURED_MOVIE_YEAR
    return readRecentlyAdded(
        sourceFactory = sourceFactory,
        include = { show -> include(show) && show.featuredReleaseYear() == year },
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
    return readRecentlyAdded(
        sourceFactory = sourceFactory,
        include = { movie -> include(movie) && movie.featuredReleaseYear() == FEATURED_MOVIE_YEAR },
        limit = FEATURED_MOVIE_LIMIT,
    )
}
