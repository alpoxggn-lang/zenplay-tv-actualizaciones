package tv.own.owntv.features.home

import android.content.Context
import androidx.room.useReaderConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.TrendingItemEntity
import tv.own.owntv.core.catalog.CatalogVariantCandidate
import tv.own.owntv.core.catalog.catalogVariantCandidate
import tv.own.owntv.core.catalog.catalogVariantDisplayNames
import tv.own.owntv.core.catalog.qualityLabel
import tv.own.owntv.core.home.TrendingHomeItem
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeSourceIds
import tv.own.owntv.core.settings.SettingsRepository
import java.util.Calendar

/**
 * ZenPlay's own Home rows, built from the user's playlists instead of TMDB:
 *
 * - **Tendencias** — the current year's movies and series, newest additions first. The selection is
 *   kept for [TRENDING_TTL_MS] (24 h) so the carousel is stable through the day, then rebuilt; it is
 *   also rebuilt at once when the year changes or when [invalidateTrending] is called (the
 *   "Actualizar" button).
 * - **Recién agregado · Películas / Series** — the most recently added titles, read live on every
 *   Home load so a finished sync shows up straight away.
 *
 * Items are wrapped in [TrendingHomeItem] with a locally made snapshot, so Home's existing trending
 * carousel and poster rows draw them unchanged (a null TMDB poster/backdrop falls back to the
 * provider's artwork).
 */
class ZenPlayHomeRows(
    context: Context,
    private val db: OwnTVDatabase,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
) {
    data class Rows(
        val trending: List<TrendingHomeItem> = emptyList(),
        val recentMovies: List<TrendingHomeItem> = emptyList(),
        val recentSeries: List<TrendingHomeItem> = emptyList(),
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * "Actualizar": forget the saved Tendencias selection and keep picking it fresh until a playlist
     * sync that started after this call has finished, so the new catalogue is what gets chosen.
     */
    fun invalidateTrending() {
        prefs.edit().clear().putLong(KEY_FORCE_AT, System.currentTimeMillis()).apply()
    }

    suspend fun load(profileId: Long): Rows = withContext(Dispatchers.IO) {
        runCatching {
            val movieSources = activeSourceIds(settings, sourceDao, profileId, MediaType.MOVIE)
            val seriesSources = activeSourceIds(settings, sourceDao, profileId, MediaType.SERIES)
            val recentMovieIds = recentIds(TABLE_MOVIES, movieSources, year = null, limit = RECENT_LIMIT)
            val recentSeriesIds = recentIds(TABLE_SERIES, seriesSources, year = null, limit = RECENT_LIMIT)
            val recentMovies = movieDao.getByIds(recentMovieIds).sortedByIdOrder(recentMovieIds) { it.id }
            val recentSeries = seriesDao.getSeriesByIds(recentSeriesIds).sortedByIdOrder(recentSeriesIds) { it.id }
            Rows(
                trending = trending(profileId, movieSources, seriesSources, lastSyncAt(profileId)),
                recentMovies = recentMovies.mapIndexed { i, m -> movieItem(m, i) },
                recentSeries = recentSeries.mapIndexed { i, s -> seriesItem(s, i) },
            )
        }.getOrElse { Rows() }
    }

    private suspend fun lastSyncAt(profileId: Long): Long =
        sourceDao.observeForProfile(profileId).first().maxOfOrNull { it.lastSyncAt ?: 0L } ?: 0L

    private suspend fun trending(
        profileId: Long,
        movieSources: List<Long>,
        seriesSources: List<Long>,
        lastSyncAt: Long,
    ): List<TrendingHomeItem> {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val cacheKey = "$profileId|$year|${movieSources.sorted()}|${seriesSources.sorted()}"
        val now = System.currentTimeMillis()
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        val forceAt = prefs.getLong(KEY_FORCE_AT, 0L)
        // A pending "Actualizar" is satisfied once a sync has completed after it was pressed.
        if (forceAt > 0L && lastSyncAt > forceAt) prefs.edit().remove(KEY_FORCE_AT).apply()
        val forcing = forceAt > 0L && lastSyncAt <= forceAt
        val fresh = !forcing && prefs.getString(KEY_CACHE, null) == cacheKey && now - savedAt in 0 until TRENDING_TTL_MS
        if (fresh) {
            val movieIds = prefs.getString(KEY_MOVIES, "").orEmpty().toIdList()
            val seriesIds = prefs.getString(KEY_SERIES, "").orEmpty().toIdList()
            val movies = movieDao.getByIds(movieIds).sortedByIdOrder(movieIds) { it.id }
            val series = seriesDao.getSeriesByIds(seriesIds).sortedByIdOrder(seriesIds) { it.id }
            // Titles the provider removed since are simply dropped; rebuild if too few survive.
            if (movies.size + series.size >= MIN_TRENDING) return interleave(movies, series)
        }

        var movieIds = pickTrending(TABLE_MOVIES, movieSources, year, MAX_TRENDING_MOVIES)
        var seriesIds = pickTrending(TABLE_SERIES, seriesSources, year, MAX_TRENDING_SERIES)
        // Early in a year there may be too few of this year's titles: top up with last year's, then
        // with whatever was added most recently, so the carousel is never empty.
        if (movieIds.size + seriesIds.size < MIN_TRENDING) {
            movieIds = (movieIds + pickTrending(TABLE_MOVIES, movieSources, year - 1, MAX_TRENDING_MOVIES)).distinct().take(MAX_TRENDING_MOVIES)
            seriesIds = (seriesIds + pickTrending(TABLE_SERIES, seriesSources, year - 1, MAX_TRENDING_SERIES)).distinct().take(MAX_TRENDING_SERIES)
        }
        if (movieIds.size + seriesIds.size < MIN_TRENDING) {
            movieIds = (movieIds + pickTrending(TABLE_MOVIES, movieSources, null, MAX_TRENDING_MOVIES)).distinct().take(MAX_TRENDING_MOVIES)
            seriesIds = (seriesIds + pickTrending(TABLE_SERIES, seriesSources, null, MAX_TRENDING_SERIES)).distinct().take(MAX_TRENDING_SERIES)
        }
        val movies = movieDao.getByIds(movieIds).sortedByIdOrder(movieIds) { it.id }
        val series = seriesDao.getSeriesByIds(seriesIds).sortedByIdOrder(seriesIds) { it.id }
        // Only remember a selection that actually has content, so an empty first launch (before
        // the catalogue has synced) does not pin an empty carousel for a whole day.
        if (movies.size + series.size >= MIN_TRENDING) {
            prefs.edit()
                .putString(KEY_CACHE, cacheKey)
                .putLong(KEY_SAVED_AT, now)
                .putString(KEY_MOVIES, movies.joinToString(",") { it.id.toString() })
                .putString(KEY_SERIES, series.joinToString(",") { it.id.toString() })
                .apply()
        }
        return interleave(movies, series)
    }

    private fun interleave(movies: List<MovieEntity>, series: List<SeriesEntity>): List<TrendingHomeItem> = buildList {
        for (i in 0 until maxOf(movies.size, series.size)) {
            movies.getOrNull(i)?.let { add(movieItem(it, size)) }
            series.getOrNull(i)?.let { add(seriesItem(it, size)) }
        }
    }.withVariantDisplayNames()

    private fun List<TrendingHomeItem>.withVariantDisplayNames(): List<TrendingHomeItem> {
        val names = catalogVariantDisplayNames(map { item ->
            val variant = when (item) {
                is TrendingHomeItem.Movie -> item.movie.catalogVariantCandidate()
                is TrendingHomeItem.Series -> item.series.catalogVariantCandidate()
            }
            CatalogVariantCandidate(
                key = item.stableKey,
                title = item.snapshot.localizedTitle,
                sourceId = variant.sourceId,
                remoteId = variant.remoteId,
                qualifiers = variant.qualifiers,
            )
        })
        return map { item ->
            val snapshot = item.snapshot.copy(localizedTitle = names[item.stableKey] ?: item.snapshot.localizedTitle)
            when (item) {
                is TrendingHomeItem.Movie -> item.copy(snapshot = snapshot)
                is TrendingHomeItem.Series -> item.copy(snapshot = snapshot)
            }
        }
    }

    /** Newest additions for [year] (or any year when null), preserving every provider variant. */
    private suspend fun pickTrending(table: String, sourceIds: List<Long>, year: Int?, limit: Int): List<Long> =
        recentIds(table, sourceIds, year, limit)

    /**
     * Ids of the most recently added rows of [table] in [sourceIds], newest first. Identity belongs to
     * the provider row, not its title: same-name language/quality/server variants all remain visible.
     */
    private suspend fun recentIds(table: String, sourceIds: List<Long>, year: Int?, limit: Int): List<Long> {
        if (sourceIds.isEmpty()) return emptyList()
        val inList = sourceIds.joinToString(",")
        val yearClause = if (year != null) " AND COALESCE(year, parsedYear) = $year" else ""
        val sql = "SELECT id FROM $table " +
            "WHERE sourceId IN ($inList)$yearClause ORDER BY addedAt DESC, id DESC LIMIT $limit"
        return db.useReaderConnection { connection ->
            connection.usePrepared(sql) { statement ->
                val ids = ArrayList<Long>()
                while (ids.size < limit && statement.step()) {
                    ids.add(statement.getLong(0))
                }
                ids
            }
        }
    }

    private fun movieItem(movie: MovieEntity, position: Int): TrendingHomeItem.Movie =
        TrendingHomeItem.Movie(
            snapshot = snapshot(
                sourceId = movie.sourceId, position = position, id = movie.id, mediaType = MediaType.MOVIE,
                remoteId = movie.remoteId, name = movie.name, canonical = movie.canonicalTitle,
                language = movie.providerLanguage, quality = qualityLabel(movie.qualityRank),
                capabilities = movie.advertisedCapabilities,
                year = movie.year ?: movie.parsedYear, overview = movie.plot, rating = movie.rating,
            ),
            movie = movie,
        )

    private fun seriesItem(series: SeriesEntity, position: Int): TrendingHomeItem.Series =
        TrendingHomeItem.Series(
            snapshot = snapshot(
                sourceId = series.sourceId, position = position, id = series.id, mediaType = MediaType.SERIES,
                remoteId = series.remoteId, name = series.name, canonical = series.canonicalTitle,
                language = series.providerLanguage, quality = qualityLabel(series.qualityRank),
                capabilities = series.advertisedCapabilities,
                year = series.year ?: series.parsedYear, overview = series.plot, rating = series.rating,
            ),
            series = series,
        )

    private fun snapshot(
        sourceId: Long,
        position: Int,
        id: Long,
        mediaType: MediaType,
        remoteId: String?,
        name: String,
        canonical: String,
        language: String?,
        quality: String?,
        capabilities: String?,
        year: Int?,
        overview: String?,
        rating: Double?,
    ) = TrendingItemEntity(
        sourceId = sourceId,
        position = position,
        // Only used for the item's stable key, which already includes the media type.
        tmdbId = id.toInt(),
        mediaType = mediaType,
        trendingRank = position + 1,
        providerItemId = id,
        providerRemoteId = remoteId,
        providerStableKey = remoteId ?: name,
        providerRawName = name,
        canonicalTitle = canonical.ifBlank { name },
        providerLanguage = language,
        advertisedQuality = quality,
        advertisedCapabilities = capabilities,
        localizedTitle = name,
        originalTitle = null,
        year = year,
        overview = overview,
        posterPath = null,
        backdropPath = null,
        rating = rating,
        trailerKey = null,
        generationId = GENERATION,
        refreshedAt = System.currentTimeMillis(),
    )

    private fun String.toIdList(): List<Long> = split(',').mapNotNull { it.trim().toLongOrNull() }

    private fun <T> List<T>.sortedByIdOrder(order: List<Long>, id: (T) -> Long): List<T> {
        val rank = order.withIndex().associate { it.value to it.index }
        return sortedBy { rank[id(it)] ?: Int.MAX_VALUE }
    }

    companion object {
        private const val PREFS = "zenplay_home"
        private const val KEY_CACHE = "trending_key"
        private const val KEY_SAVED_AT = "trending_saved_at"
        private const val KEY_MOVIES = "trending_movies"
        private const val KEY_SERIES = "trending_series"
        private const val KEY_FORCE_AT = "trending_force_at"
        /** Marks snapshots made here, so nothing treats their ids as TMDB ids. */
        const val GENERATION = "zenplay"
        private const val TABLE_MOVIES = "movies"
        private const val TABLE_SERIES = "series"

        /** The carousel is rebuilt at most once a day (or on "Actualizar"). */
        const val TRENDING_TTL_MS = 24L * 60 * 60 * 1000
        private const val MAX_TRENDING_MOVIES = 6
        private const val MAX_TRENDING_SERIES = 4
        /** Home only draws the trending row with at least this many titles (TrendingDao.MIN_ELIGIBLE_ITEMS). */
        private const val MIN_TRENDING = 4
        private const val RECENT_LIMIT = 20
    }
}
