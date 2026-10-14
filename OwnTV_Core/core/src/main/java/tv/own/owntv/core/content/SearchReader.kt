package tv.own.owntv.core.content

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ChannelSearchResult
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.ActiveProfileSources

/** Combined results of a global query (each list bounded). */
@Immutable
data class SearchResults(
    val channels: List<ChannelSearchResult> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

/** The curated lists offered when nothing has been typed yet. */
enum class SearchIntent {
    CONTINUE,
    UNWATCHED,
    CHANNELS,
}

/**
 * Searching a profile's channels, films and shows at once.
 *
 * The queries themselves are the DAOs'; what lives here is everything that decides *which* of their
 * rows a user may see — the FTS expression, the hidden items, the hidden categories, a kids
 * profile's adult filter, and the renames a user made in Customize. Two apps searching the same
 * database have to agree on all five, so it is written once rather than in each app's search screen.
 */
class SearchReader(
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val profileDao: ProfileDao,
    private val customize: CustomizationStore,
) {

    /**
     * Results for a typed query, already filtered and renamed for [profileId].
     *
     * [sources] is the profile's active playlists, per section, so a section switched Off never
     * surfaces. Returns nothing at all when the profile has no sources — there is nothing to search.
     */
    suspend fun search(
        profileId: Long,
        sources: ActiveProfileSources,
        query: String,
        limit: Int = LIMIT,
    ): SearchResults {
        if (profileId < 0 || !sources.hasAny) return SearchResults()
        val fts = ftsQuery(query)
        val access = accessSnapshot(profileId, sources) ?: return SearchResults()
        return SearchResults(
            channels = if (sources.liveSourceIds.isEmpty()) emptyList() else
                (
                    if (fts != null) channelDao.searchListDetailedFts(fts, sources.liveSourceIds, limit)
                    else channelDao.searchListDetailed(query, sources.liveSourceIds, limit)
                    )
                    .filter { row -> access.allows(MediaType.LIVE, CustomizeKeys.channel(row.channel), row.channel.categoryId, row.channel.sourceId) }
                    .map { row ->
                        access.live.itemNames[CustomizeKeys.channel(row.channel)]
                            ?.let { row.copy(channel = row.channel.copy(name = it)) } ?: row
                    },
            movies = if (sources.movieSourceIds.isEmpty()) emptyList() else
                (
                    if (fts != null) movieDao.searchListFts(fts, sources.movieSourceIds, limit)
                    else movieDao.searchList(query, sources.movieSourceIds, limit)
                    )
                    .filter { access.allows(MediaType.MOVIE, CustomizeKeys.movie(it), it.categoryId, it.sourceId) }
                    .map { m -> access.movie.itemNames[CustomizeKeys.movie(m)]?.let { m.copy(name = it) } ?: m },
            series = if (sources.seriesSourceIds.isEmpty()) emptyList() else
                (
                    if (fts != null) seriesDao.searchListFts(fts, sources.seriesSourceIds, limit)
                    else seriesDao.searchList(query, sources.seriesSourceIds, limit)
                    )
                    .filter { access.allows(MediaType.SERIES, CustomizeKeys.series(it), it.categoryId, it.sourceId) }
                    .map { s -> access.series.itemNames[CustomizeKeys.series(s)]?.let { s.copy(name = it) } ?: s },
        )
    }

    /** One of the curated lists shown before anything is typed. Bounded, and already source-filtered. */
    suspend fun curated(
        profileId: Long,
        sources: ActiveProfileSources,
        intent: SearchIntent,
        limit: Int = LIMIT,
    ): SearchResults {
        if (profileId < 0 || !sources.hasAny) return SearchResults()
        val access = accessSnapshot(profileId, sources) ?: return SearchResults()
        val raw = when (intent) {
            SearchIntent.CONTINUE -> SearchResults(
                channels = channelDao.recentlyWatched(profileId, limit).first()
                    .filter { it.sourceId in sources.liveSourceIds }
                    .map { ChannelSearchResult(it, null) },
                movies = if (sources.movieSourceIds.isEmpty()) emptyList()
                else movieDao.recentlyWatchedSnapshot(profileId, sources.movieSourceIds, limit),
                series = if (sources.seriesSourceIds.isEmpty()) emptyList()
                else seriesDao.recentlyWatchedSnapshot(profileId, sources.seriesSourceIds, limit),
            )
            SearchIntent.UNWATCHED -> SearchResults(
                movies = if (sources.movieSourceIds.isEmpty()) emptyList()
                else movieDao.unwatchedFavorites(profileId, sources.movieSourceIds, limit),
                series = if (sources.seriesSourceIds.isEmpty()) emptyList()
                else seriesDao.unwatchedFavorites(profileId, sources.seriesSourceIds, limit),
            )
            SearchIntent.CHANNELS -> SearchResults(
                channels = channelDao.favoritesListAlpha(profileId).first()
                    .filter { it.sourceId in sources.liveSourceIds }
                    .take(limit)
                    .map { ChannelSearchResult(it, null) },
            )
        }
        return SearchResults(
            channels = raw.channels
                .filter { row -> access.allows(MediaType.LIVE, CustomizeKeys.channel(row.channel), row.channel.categoryId, row.channel.sourceId) }
                .map { row ->
                    access.live.itemNames[CustomizeKeys.channel(row.channel)]
                        ?.let { row.copy(channel = row.channel.copy(name = it)) } ?: row
                },
            movies = raw.movies
                .filter { access.allows(MediaType.MOVIE, CustomizeKeys.movie(it), it.categoryId, it.sourceId) }
                .map { movie -> access.movie.itemNames[CustomizeKeys.movie(movie)]?.let { movie.copy(name = it) } ?: movie },
            series = raw.series
                .filter { access.allows(MediaType.SERIES, CustomizeKeys.series(it), it.categoryId, it.sourceId) }
                .map { series -> access.series.itemNames[CustomizeKeys.series(series)]?.let { series.copy(name = it) } ?: series },
        )
    }

    private suspend fun accessSnapshot(profileId: Long, sources: ActiveProfileSources): AccessSnapshot? {
        val profile = profileDao.getById(profileId) ?: return null
        val live = customize.observe(profileId, MediaType.LIVE).first()
        val movie = customize.observe(profileId, MediaType.MOVIE).first()
        val series = customize.observe(profileId, MediaType.SERIES).first()
        val categories = buildMap<Long, CategoryEntity> {
            if (sources.liveSourceIds.isNotEmpty()) putAll(categoryDao.observe(sources.liveSourceIds, MediaType.LIVE).first().associateBy { it.id })
            if (sources.movieSourceIds.isNotEmpty()) putAll(categoryDao.observe(sources.movieSourceIds, MediaType.MOVIE).first().associateBy { it.id })
            if (sources.seriesSourceIds.isNotEmpty()) putAll(categoryDao.observe(sources.seriesSourceIds, MediaType.SERIES).first().associateBy { it.id })
        }
        return AccessSnapshot(
            profile = profile,
            live = live,
            movie = movie,
            series = series,
            categories = categories,
            liveSourceIds = sources.liveSourceIds.toSet(),
            movieSourceIds = sources.movieSourceIds.toSet(),
            seriesSourceIds = sources.seriesSourceIds.toSet(),
        )
    }

    private data class AccessSnapshot(
        val profile: ProfileEntity,
        val live: tv.own.owntv.core.customize.SectionCustomizations,
        val movie: tv.own.owntv.core.customize.SectionCustomizations,
        val series: tv.own.owntv.core.customize.SectionCustomizations,
        val categories: Map<Long, CategoryEntity>,
        val liveSourceIds: Set<Long>,
        val movieSourceIds: Set<Long>,
        val seriesSourceIds: Set<Long>,
    ) {
        fun allows(type: MediaType, itemKey: String, categoryId: Long?, sourceId: Long): Boolean =
            ContentAccessPolicy.allowsSnapshot(
                profile = profile,
                itemKey = itemKey,
                categoryId = categoryId,
                category = categoryId?.let(categories::get)?.takeIf { it.mediaType == type },
                customizations = when (type) {
                    MediaType.LIVE -> live
                    MediaType.MOVIE -> movie
                    MediaType.SERIES, MediaType.EPISODE -> series
                },
                sourceId = sourceId,
                allowedSourceIds = when (type) {
                    MediaType.LIVE -> liveSourceIds
                    MediaType.MOVIE -> movieSourceIds
                    MediaType.SERIES, MediaType.EPISODE -> seriesSourceIds
                },
            )
    }

    companion object {
        /** How many rows of each kind a search returns. */
        const val LIMIT = 40

        /**
         * A sanitized FTS4 MATCH expression: each whitespace-separated token is stripped to letters
         * and digits and turned into a prefix term ("harry pot" → "harry* pot*", implicit AND).
         * Null when nothing tokenizable remains (symbols-only input) — the caller then falls back to
         * the substring LIKE queries. Prefix terms match word starts rather than mid-word substrings,
         * which is the accepted trade-off for an index-served search over ~220k rows per keystroke.
         */
        fun ftsQuery(query: String): String? {
            val tokens = query.split(Regex("\\s+"))
                .map { t -> t.filter { it.isLetterOrDigit() } }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            return tokens.joinToString(" ") { "$it*" }
        }
    }
}

/** True when the profile has at least one playlist a search could look in. */
private val ActiveProfileSources.hasAny: Boolean
    get() = liveSourceIds.isNotEmpty() || movieSourceIds.isNotEmpty() || seriesSourceIds.isNotEmpty()
