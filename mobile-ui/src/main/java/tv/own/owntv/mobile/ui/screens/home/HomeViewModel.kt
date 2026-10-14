package tv.own.owntv.mobile.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.repository.activeSourceIds
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.home.HomeFeed
import tv.own.owntv.core.home.HomeFeedReader
import tv.own.owntv.core.model.HomeLiveRowMode
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.weather.WeatherInfo
import tv.own.owntv.core.weather.WeatherRepository
import tv.own.owntv.mobile.ui.screens.ContentActions
import tv.own.owntv.mobile.ui.screens.library.VodTuner

/**
 * Home, for the phone.
 *
 * ZenPlay's discovery rows come first, followed by core's configurable continuation rows. New
 * arrivals use the provider's date-added queries, scoped to the same active playlist and profile.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(
    private val reader: HomeFeedReader,
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
    private val tuner: VodTuner,
    private val actions: ContentActions,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    weatherRepository: WeatherRepository,
    connectivity: ConnectivityObserver,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val customize: CustomizationStore,
    private val database: OwnTVDatabase,
    context: android.content.Context,
) : ViewModel() {

    private val featuredCache = ZenPlayFeaturedCache(context)

    private val profileId: StateFlow<Long> = settings.activeProfileId
        .map { stored -> if (stored >= 0) profileDao.resolveExistingProfileId(stored) ?: -1L else -1L }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, -1L)

    /**
     * One conflated request to rebuild. The screen asks on every entry, because coming back from a
     * film is exactly when the continue rows are wrong, and a profile switch asks on its own —
     * `collectLatest` drops a load already running rather than letting the two queue up.
     */
    private val reloads = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _feed = MutableStateFlow<ZenPlayHomeState?>(null)

    /** Null until the first load finishes — the screen shows nothing rather than a false empty state. */
    val feed: StateFlow<ZenPlayHomeState?> = _feed

    init {
        reloads.tryEmit(Unit)
        viewModelScope.launch {
            var previousScope: Pair<Long, Long>? = null
            combine(profileId, settings.defaultSourceId, reloads) { pid, sourceId, _ -> pid to sourceId }
                .collectLatest { scope ->
                    // Never show the previous profile/playlist while the replacement is loading.
                    if (scope != previousScope) _feed.value = null
                    previousScope = scope
                    _feed.value = if (scope.first < 0) ZenPlayHomeState() else loadHome(scope.first)
                }
        }
        viewModelScope.launch {
            // Read-only snapshots cannot trigger this observer. Coalesce provider write batches;
            // re-entry refreshes anything changed while Home was not being observed.
            database.invalidationTracker.createFlow("movies", "series", emitInitialState = false)
                .filter { _feed.subscriptionCount.value > 0 }
                .debounce(500L)
                .collect {
                    if (_feed.subscriptionCount.value > 0) reloads.emit(Unit)
                }
        }
    }

    private suspend fun loadHome(profileId: Long): ZenPlayHomeState = withContext(Dispatchers.IO) {
        coroutineScope {
            val coreFeed = async { reader.load(profileId) }
            val isKids = profileDao.getById(profileId)?.isKids == true
            val movies = async {
                val ids = activeSourceIds(settings, sourceDao, profileId, MediaType.MOVIE)
                if (ids.isEmpty()) return@async MovieDiscoveryRows()
                val hidden = customize.observe(profileId, MediaType.MOVIE).first()
                val hiddenCategories = AdultCategoryClassifier.hiddenCategoryIds(
                    categoryDao.observe(ids, MediaType.MOVIE).first(), hidden.hiddenCategories, isKids,
                )
                val visible: (MovieEntity) -> Boolean = { movie ->
                    movie.categoryId?.let(hiddenCategories::contains) != true &&
                        CustomizeKeys.movie(movie) !in hidden.hiddenItems
                }
                val recent = async {
                    readRecentlyAdded({ movieDao.pagingAllDateAdded(ids) }, visible)
                }
                val featured = async {
                    readFeaturedMovies({ movieDao.pagingAllDateAdded(ids) }, visible)
                }
                MovieDiscoveryRows(recent.await(), featured.await())
            }
            val series = async {
                val ids = activeSourceIds(settings, sourceDao, profileId, MediaType.SERIES)
                if (ids.isEmpty()) return@async SeriesDiscoveryRows()
                val hidden = customize.observe(profileId, MediaType.SERIES).first()
                val hiddenCategories = AdultCategoryClassifier.hiddenCategoryIds(
                    categoryDao.observe(ids, MediaType.SERIES).first(), hidden.hiddenCategories, isKids,
                )
                val include: (SeriesEntity) -> Boolean = { show ->
                    show.categoryId?.let(hiddenCategories::contains) != true &&
                        CustomizeKeys.series(show) !in hidden.hiddenItems
                }
                val recentShows = async {
                    readRecentlyAdded(sourceFactory = { seriesDao.pagingAllDateAdded(ids) }, include = include)
                }
                val featuredShows = async {
                    readFeaturedSeries({ seriesDao.pagingAllDateAdded(ids) }, include)
                }
                SeriesDiscoveryRows(recentShows.await(), featuredShows.await())
            }
            val movieRows = movies.await()
            val seriesRows = series.await()
            ZenPlayHomeState(
                feed = coreFeed.await(),
                recentMovies = movieRows.recent,
                recentSeries = seriesRows.recent,
                featuredMovies = movieRows.featured,
                featured = stableFeatured(
                    profileId,
                    interleaveFeatured(
                        movieRows.featured.map { it.toFeatured() },
                        seriesRows.featured.map { it.toFeatured() },
                    ),
                ),
            )
        }
    }

    private data class SeriesDiscoveryRows(
        val recent: List<SeriesEntity> = emptyList(),
        val featured: List<SeriesEntity> = emptyList(),
    )

    /**
     * ZenPlay: the selection saved within the last 24 hours for this profile, plan and year, when
     * enough of it is still in the catalogue; otherwise [fresh], which is then saved.
     */
    private suspend fun stableFeatured(profileId: Long, fresh: List<FeaturedItem>): List<FeaturedItem> {
        val scope = "$profileId|${settings.defaultSourceId.first()}|$FEATURED_MOVIE_YEAR"
        val saved = featuredCache.saved(scope)
        if (saved != null) {
            val movieIds = saved.filter { it.startsWith("m:") }.mapNotNull { it.drop(2).toLongOrNull() }
            val seriesIds = saved.filter { it.startsWith("s:") }.mapNotNull { it.drop(2).toLongOrNull() }
            val found = (movieDao.getByIds(movieIds).map { it.toFeatured() } +
                seriesDao.getSeriesByIds(seriesIds).map { it.toFeatured() }).associateBy { it.key }
            val kept = saved.mapNotNull { found[it] }
            if (kept.size >= MIN_FEATURED) return kept
        }
        if (fresh.size >= MIN_FEATURED) featuredCache.save(scope, fresh)
        return fresh
    }

    private data class MovieDiscoveryRows(
        val recent: List<MovieEntity> = emptyList(),
        val featured: List<MovieEntity> = emptyList(),
    )

    fun refresh() {
        reloads.tryEmit(Unit)
    }

    private companion object {
        /** Fewer than this and the saved selection is chosen again. */
        const val MIN_FEATURED = 3
    }

    /** The weather chip, when the setting is on and there is a network to ask over. */
    val weather: StateFlow<WeatherInfo?> =
        combine(connectivity.isOnline, settings.weatherEnabled, settings.weatherLocation) { online, enabled, location ->
            Triple(online, enabled, location)
        }.flatMapLatest { (online, enabled, location) ->
            if (!online || !enabled) flowOf<WeatherInfo?>(null) else flow { emit(weatherRepository.get(location)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val fahrenheit: StateFlow<Boolean> = settings.weatherFahrenheit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Switch a live row between logo cards and what-is-on-now. Stored where the TV app reads it. */
    fun toggleLiveMode(row: HomeRow) {
        val pid = profileId.value.takeIf { it >= 0 } ?: return
        viewModelScope.launch {
            settings.updateHomeConfig(pid) { config ->
                when (row) {
                    HomeRow.RECENT_CHANNELS -> config.copy(recentLiveMode = config.recentLiveMode.toggled())
                    HomeRow.FAVORITE_CHANNELS -> config.copy(favoriteLiveMode = config.favoriteLiveMode.toggled())
                    else -> config
                }
            }
            // The guide slice is only read for a row that is in "on now" mode, so the feed has to be
            // rebuilt for the row that just switched into it.
            refresh()
        }
    }

    fun modeOf(row: HomeRow, state: HomeFeed): HomeLiveRowMode = when (row) {
        HomeRow.FAVORITE_CHANNELS -> state.config.favoriteLiveMode
        else -> state.config.recentLiveMode
    }

    /** Play a film, or an episode, from where it was left. [onStarted] runs only if the picture is
     *  opening here rather than in another app — the same rule the detail screen plays by. */
    fun playMovie(movieId: Long, positionMs: Long, onStarted: () -> Unit) {
        viewModelScope.launch { if (tuner.playMovie(movieId, positionMs)) onStarted() }
    }

    fun playEpisode(episodeId: Long, positionMs: Long, onStarted: () -> Unit) {
        viewModelScope.launch { if (tuner.playEpisode(episodeId, positionMs)) onStarted() }
    }

    /** Revalidate the actual provider row if a sync or visibility change happened after rendering. */
    fun activateFeaturedMovie(
        item: MovieEntity,
        onPlayerOpened: () -> Unit,
        onUnavailable: () -> Unit,
    ) {
        viewModelScope.launch {
            val pid = profileId.value
            val current = withContext(Dispatchers.IO) {
                if (pid < 0) return@withContext null
                val ids = activeSourceIds(settings, sourceDao, pid, MediaType.MOVIE)
                val movie = movieDao.getById(item.id)?.takeIf {
                    it.sourceId == item.sourceId && it.sourceId in ids &&
                        it.featuredReleaseYear() == FEATURED_MOVIE_YEAR
                } ?: return@withContext null
                val hidden = customize.observe(pid, MediaType.MOVIE).first()
                val hiddenCategories = AdultCategoryClassifier.hiddenCategoryIds(
                    categoryDao.observe(ids, MediaType.MOVIE).first(), hidden.hiddenCategories,
                    profileDao.getById(pid)?.isKids == true,
                )
                movie.takeIf {
                    it.categoryId?.let(hiddenCategories::contains) != true &&
                        CustomizeKeys.movie(it) !in hidden.hiddenItems
                }
            }
            if (current == null || profileId.value != pid) {
                onUnavailable()
                refresh()
            } else if (tuner.playMovie(current.id, 0L)) {
                // External-player handover also returns false; it is not a missing catalog item.
                onPlayerOpened()
            }
        }
    }

    // --- The long-press menu ------------------------------------------------------------------------

    val favoriteChannels: StateFlow<Set<Long>> = favoriteIds(MediaType.LIVE)
    val favoriteMovies: StateFlow<Set<Long>> = favoriteIds(MediaType.MOVIE)
    val favoriteSeries: StateFlow<Set<Long>> = favoriteIds(MediaType.SERIES)

    private fun favoriteIds(type: MediaType): StateFlow<Set<Long>> = actions.favoriteIds(type)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Favouriting or hiding changes what the rails hold, so the feed is rebuilt after either. */
    fun toggleFavorite(type: MediaType, itemId: Long) {
        viewModelScope.launch {
            actions.toggleFavorite(type, itemId)
            refresh()
        }
    }

    fun hide(type: MediaType, itemId: Long) {
        viewModelScope.launch {
            actions.hide(type, itemId)
            refresh()
        }
    }

    fun download(type: MediaType, itemId: Long) {
        viewModelScope.launch { actions.download(type, itemId) }
    }
}
