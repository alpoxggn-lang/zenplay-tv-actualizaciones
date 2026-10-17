package tv.own.owntv.core.recommendation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.TrendingCatalogRow
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.MetadataProvider
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.trending.TrendingMatcher

/**
 * One recommendation source shared by TV, phone and tablet.
 *
 * TMDB contributes related titles, but every result is matched back to the active Xtream/M3U catalog,
 * so a poster is never offered unless the app can actually play it. If metadata is disabled/offline or
 * too few recommendations exist locally, a bounded genre/category-aware local ranking fills the row.
 */
class CatalogRecommendationRepository(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val categoryDao: CategoryDao,
    private val sourceDao: SourceDao,
    private val metadata: MetadataRepository,
    private val metadataProvider: MetadataProvider,
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
    private val customizationStore: CustomizationStore,
) {
    suspend fun movies(
        current: MovieEntity,
        providerGenres: Collection<String> = emptyList(),
        limit: Int = DEFAULT_LIMIT,
    ): List<MovieEntity> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val scope = scope(MediaType.MOVIE, current.sourceId) ?: return@withContext emptyList()
        val metadataRow = safe { metadata.resolveMovie(current) }
        val genres = (providerGenres + parseGenres(metadataRow?.genresJson)).distinct()

        val related = metadataRow?.let { row ->
            remoteCandidates(
                MetadataType.MOVIE,
                row.tmdbId,
                scope.includeAdult,
                scope.language,
                scope.metadataFingerprint,
            )?.let { candidates ->
                TrendingMatcher.matchMedia(
                    candidates = candidates,
                    mediaType = MediaType.MOVIE,
                    preferredLanguage = scope.language,
                    limit = limit + REMOTE_RESERVE,
                    exactLookup = { signatures ->
                        movieDao.trendingExact(current.sourceId, signatures).filter(scope::allows)
                    },
                    ftsLookup = { query, size ->
                        movieDao.trendingFts(current.sourceId, query, size).filter(scope::allows)
                    },
                ).selections
                    .map { it.variant.item.id }
                    .let { ids ->
                        val rows = movieDao.getByIds(ids).associateBy(MovieEntity::id)
                        ids.mapNotNull(rows::get)
                    }
            }
        }.orEmpty()

        val currentIdentity = current.recommendationStableKey()
        val identity: (MovieEntity) -> String = { it.recommendationStableKey() }
        val allowed: (MovieEntity) -> Boolean = {
            scope.allows(it.categoryId, CustomizeKeys.movie(it))
        }
        val usableRelated = mergeAllowed(
            preferred = related,
            fallback = emptyList(),
            currentIdentity = currentIdentity,
            limit = limit,
            identity = identity,
            allowed = allowed,
        )
        val local = if (usableRelated.size >= limit) {
            emptyList()
        } else {
            localMovies(current, genres, scope, limit - usableRelated.size + LOCAL_RESERVE)
        }
        mergeAllowed(
            preferred = usableRelated,
            fallback = local,
            currentIdentity = currentIdentity,
            limit = limit,
            identity = identity,
            allowed = allowed,
        )
    }

    suspend fun series(
        current: SeriesEntity,
        providerGenres: Collection<String> = emptyList(),
        limit: Int = DEFAULT_LIMIT,
    ): List<SeriesEntity> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val scope = scope(MediaType.SERIES, current.sourceId) ?: return@withContext emptyList()
        val metadataRow = safe { metadata.resolveSeries(current) }
        val genres = (providerGenres + parseGenres(metadataRow?.genresJson)).distinct()

        val related = metadataRow?.let { row ->
            remoteCandidates(
                MetadataType.TV,
                row.tmdbId,
                scope.includeAdult,
                scope.language,
                scope.metadataFingerprint,
            )?.let { candidates ->
                TrendingMatcher.matchMedia(
                    candidates = candidates,
                    mediaType = MediaType.SERIES,
                    preferredLanguage = scope.language,
                    limit = limit + REMOTE_RESERVE,
                    exactLookup = { signatures ->
                        seriesDao.trendingExact(current.sourceId, signatures).filter(scope::allows)
                    },
                    ftsLookup = { query, size ->
                        seriesDao.trendingFts(current.sourceId, query, size).filter(scope::allows)
                    },
                ).selections
                    .map { it.variant.item.id }
                    .let { ids ->
                        val rows = seriesDao.getSeriesByIds(ids).associateBy(SeriesEntity::id)
                        ids.mapNotNull(rows::get)
                    }
            }
        }.orEmpty()

        val currentIdentity = current.recommendationStableKey()
        val identity: (SeriesEntity) -> String = { it.recommendationStableKey() }
        val allowed: (SeriesEntity) -> Boolean = {
            scope.allows(it.categoryId, CustomizeKeys.series(it))
        }
        val usableRelated = mergeAllowed(
            preferred = related,
            fallback = emptyList(),
            currentIdentity = currentIdentity,
            limit = limit,
            identity = identity,
            allowed = allowed,
        )
        val local = if (usableRelated.size >= limit) {
            emptyList()
        } else {
            localSeries(current, genres, scope, limit - usableRelated.size + LOCAL_RESERVE)
        }
        mergeAllowed(
            preferred = usableRelated,
            fallback = local,
            currentIdentity = currentIdentity,
            limit = limit,
            identity = identity,
            allowed = allowed,
        )
    }

    private suspend fun localMovies(
        current: MovieEntity,
        genres: Collection<String>,
        scope: RecommendationScope,
        limit: Int,
    ): List<MovieEntity> {
        val categoryIds = candidateCategoryIds(current.categoryId, genres, scope)
        val categoryCandidates = if (categoryIds.isEmpty()) {
            emptyList()
        } else {
            movieDao.recommendationCandidatesInCategories(current.sourceId, categoryIds, LOCAL_SCAN)
                .filter { scope.allows(it.categoryId, CustomizeKeys.movie(it)) }
        }
        val currentCategory = current.categoryId?.let(scope.categoriesById::get)
        fun rank(candidates: List<MovieEntity>): List<MovieEntity> = ContentRecommendationRanker.rank(
            current = current.toRecommendationItem(currentCategory, genres),
            candidates = candidates.distinctBy(MovieEntity::id).map { item ->
                item.toRecommendationItem(item.categoryId?.let(scope.categoriesById::get))
            },
            limit = limit,
        ).map { it.value }
        val categoryRanked = rank(categoryCandidates)
        if (categoryRanked.size >= limit) return categoryRanked
        val sourceCandidates = movieDao.recommendationCandidatesForSource(current.sourceId, LOCAL_SCAN)
            .filter { scope.allows(it.categoryId, CustomizeKeys.movie(it)) }
        return rank(categoryCandidates + sourceCandidates)
    }

    private suspend fun localSeries(
        current: SeriesEntity,
        genres: Collection<String>,
        scope: RecommendationScope,
        limit: Int,
    ): List<SeriesEntity> {
        val categoryIds = candidateCategoryIds(current.categoryId, genres, scope)
        val categoryCandidates = if (categoryIds.isEmpty()) {
            emptyList()
        } else {
            seriesDao.recommendationCandidatesInCategories(current.sourceId, categoryIds, LOCAL_SCAN)
                .filter { scope.allows(it.categoryId, CustomizeKeys.series(it)) }
        }
        val currentCategory = current.categoryId?.let(scope.categoriesById::get)
        fun rank(candidates: List<SeriesEntity>): List<SeriesEntity> = ContentRecommendationRanker.rank(
            current = current.toRecommendationItem(currentCategory, genres),
            candidates = candidates.distinctBy(SeriesEntity::id).map { item ->
                item.toRecommendationItem(item.categoryId?.let(scope.categoriesById::get))
            },
            limit = limit,
        ).map { it.value }
        val categoryRanked = rank(categoryCandidates)
        if (categoryRanked.size >= limit) return categoryRanked
        val sourceCandidates = seriesDao.recommendationCandidatesForSource(current.sourceId, LOCAL_SCAN)
            .filter { scope.allows(it.categoryId, CustomizeKeys.series(it)) }
        return rank(categoryCandidates + sourceCandidates)
    }

    private fun candidateCategoryIds(
        currentCategoryId: Long?,
        genres: Collection<String>,
        scope: RecommendationScope,
    ): List<Long> {
        val wanted = ContentRecommendationRanker.normalizeGenres(genres)
        val semantic = scope.categories.asSequence()
            .filterNot { it.id in scope.hiddenCategoryIds }
            .filter { category ->
                wanted.isNotEmpty() &&
                    ContentRecommendationRanker.normalizeGenres(listOf(category.name)).any(wanted::contains)
            }
            .map(CategoryEntity::id)
        return buildList {
            addAll(semantic)
            currentCategoryId?.takeIf { it !in scope.hiddenCategoryIds }?.let(::add)
        }.distinct().take(MAX_CATEGORY_QUERIES)
    }

    private suspend fun scope(type: MediaType, sourceId: Long): RecommendationScope? {
        val profileId = settings.activeProfileIdNow()
        val profile = profileId.takeIf { it >= 0 }?.let { profileDao.getById(it) } ?: return null
        if (sourceId !in sourceDao.sourceIdsForProfile(profileId)) return null
        val categories = categoryDao.listForSource(sourceId, type)
        val customizations = customizationStore.observe(profileId, type).first()
        val hiddenCategories = AdultCategoryClassifier.hiddenCategoryIds(
            categories,
            customizations.hiddenCategories,
            profile.isKids,
        )
        val config = settings.metadataConfig()
        return RecommendationScope(
            categories = categories,
            categoriesById = categories.associateBy(CategoryEntity::id),
            hiddenCategoryIds = hiddenCategories,
            hiddenItems = customizations.hiddenItems.keys,
            kids = profile.isKids,
            includeAdult = !profile.isKids,
            language = config.resolvedLanguage,
            metadataFingerprint = buildString {
                append(config.mode).append(':').append(config.tier).append(':')
                append(config.customServerUrl.trimEnd('/').hashCode()).append(':')
                append(config.tmdbApiKey.hashCode())
            },
        )
    }

    private suspend fun remoteCandidates(
        type: MetadataType,
        tmdbId: Int,
        includeAdult: Boolean,
        language: String,
        metadataFingerprint: String,
    ): List<TrendingCandidate>? {
        val key = "$metadataFingerprint:$type:$tmdbId:$includeAdult:$language"
        cachedRemote(key)?.let { return it }
        return remoteFetchLock.withLock {
            cachedRemote(key)?.let { return@withLock it }
            val fetched = metadataProvider.recommendations(type, tmdbId, includeAdult) ?: return@withLock null
            synchronized(remoteCache) {
                remoteCache[key] = CachedRemote(fetched, System.currentTimeMillis())
            }
            fetched
        }
    }

    private fun cachedRemote(key: String): List<TrendingCandidate>? = synchronized(remoteCache) {
        val cached = remoteCache[key] ?: return@synchronized null
        if (System.currentTimeMillis() - cached.fetchedAtMs > REMOTE_CACHE_TTL_MS) {
            remoteCache.remove(key)
            null
        } else {
            cached.candidates
        }
    }

    private fun <T> mergeAllowed(
        preferred: List<T>,
        fallback: List<T>,
        currentIdentity: String,
        limit: Int,
        identity: (T) -> String,
        allowed: (T) -> Boolean,
    ): List<T> {
        val identities = HashSet<String>()
        return (preferred.asSequence() + fallback.asSequence())
            .filter(allowed)
            .filter { item ->
                val itemIdentity = identity(item)
                itemIdentity != currentIdentity && identities.add(itemIdentity)
            }
            .take(limit)
            .toList()
    }

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun parseGenres(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf(String::isNotBlank)
            }
        }.getOrDefault(emptyList())
    }

    private data class RecommendationScope(
        val categories: List<CategoryEntity>,
        val categoriesById: Map<Long, CategoryEntity>,
        val hiddenCategoryIds: Set<Long>,
        val hiddenItems: Set<String>,
        val kids: Boolean,
        val includeAdult: Boolean,
        val language: String,
        val metadataFingerprint: String,
    ) {
        fun allows(categoryId: Long?, itemKey: String): Boolean {
            if (itemKey in hiddenItems || categoryId?.let(hiddenCategoryIds::contains) == true) return false
            // Kids profiles fail closed when the provider omitted the category; ordinary profiles do not.
            return !kids || (categoryId != null && categoriesById[categoryId] != null)
        }

        fun allows(row: TrendingCatalogRow): Boolean =
            allows(row.categoryId, "${row.sourceId}:${row.remoteId ?: row.name}")
    }

    private data class CachedRemote(
        val candidates: List<TrendingCandidate>,
        val fetchedAtMs: Long,
    )

    private val remoteFetchLock = Mutex()
    private val remoteCache = object : LinkedHashMap<String, CachedRemote>(REMOTE_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedRemote>?): Boolean =
            size > REMOTE_CACHE_SIZE
    }

    private companion object {
        const val DEFAULT_LIMIT = 5
        const val REMOTE_RESERVE = 4
        const val LOCAL_RESERVE = 4
        const val LOCAL_SCAN = 160
        const val MAX_CATEGORY_QUERIES = 12
        const val REMOTE_CACHE_SIZE = 64
        const val REMOTE_CACHE_TTL_MS = 6L * 60L * 60L * 1_000L
    }
}
