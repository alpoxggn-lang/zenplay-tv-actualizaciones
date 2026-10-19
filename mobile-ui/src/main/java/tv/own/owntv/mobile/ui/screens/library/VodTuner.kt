package tv.own.owntv.mobile.ui.screens.library

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.catalog.CompatibleEpisodeVariant
import tv.own.owntv.core.catalog.CompatibleVariantResolver
import tv.own.owntv.core.catalog.qualityLabel
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.content.ContentAccessPolicy
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.HistoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.WatchHistoryEntity
import tv.own.owntv.core.download.secure.ProtectedDownloadStore
import tv.own.owntv.core.download.secure.SecureDownloadOwner
import tv.own.owntv.core.download.DownloadItemResolver
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.player.ExternalPlayerLauncher
import tv.own.owntv.core.player.enginePinKey
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.SourceOverrides
import tv.own.owntv.core.stalker.ReconnectUrlProvider
import tv.own.owntv.core.stalker.StreamUrlResolver
import tv.own.owntv.core.subtitles.SubtitleController
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.cast.CastController
import tv.own.owntv.mobile.cast.CastHandoff
import tv.own.owntv.mobile.cast.CastRequest
import tv.own.owntv.mobile.playback.DataSaverGate
import tv.own.owntv.mobile.playback.PlaybackService
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.mobile.zenplay.ZenPlayAccount
import tv.own.owntv.mobile.zenplay.ZenPlayExpiryState
import tv.own.owntv.mobile.zenplay.allowsProtectedPlayback
import tv.own.owntv.player.MediaMeta
import tv.own.owntv.player.MpvPlaybackEngine
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlaybackSession
import tv.own.owntv.player.PlaylistItem
import tv.own.owntv.player.isCompatibleVariantRecoveryFailure

/**
 * The `itemId` a playing recording carries.
 *
 * A recording has no catalogue row, and [VodPlayback.itemId] is not nullable — so it gets a sentinel
 * rather than a real channel id. A real one would make the mini-player's favourite and "open detail"
 * affordances act on the channel the recording came from, which is not what is on screen.
 */
const val RECORDING_ITEM_ID = -1L

/** A film or an episode, playing. [mediaType] and [itemId] are what the resume position is written for. */
data class VodPlayback(
    val mediaType: MediaType,
    val itemId: Long,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    /** The show an episode belongs to. An episode is favourited by its series, never on its own. */
    val seriesId: Long? = null,
    /**
     * Which playlist this is streaming from, or null for a file already downloaded to the phone.
     *
     * Only used to tell core's `WatchSession` that the playlist is in use, so its background
     * catalogue drain steps aside. A local file spends no provider connection, so it holds no
     * session. Last in the list because every other caller passes these positionally.
     */
    val sourceId: Long? = null,
)

/**
 * A catalogue copy which can replace a VOD rendition the device cannot decode.
 *
 * The original rows remain part of the value on purpose: playback changes only the provider's
 * technical stream. Resume progress, history, subtitles and the title shown in the UI continue to
 * belong to the movie/episode the user selected.
 */
sealed interface CompatibleVodAlternative {
    val label: String

    data class Movie(
        val original: MovieEntity,
        val replacement: MovieEntity,
        override val label: String,
    ) : CompatibleVodAlternative

    data class Episode(
        val originalShow: SeriesEntity,
        val originalEpisode: EpisodeEntity,
        val replacement: CompatibleEpisodeVariant,
        override val label: String,
    ) : CompatibleVodAlternative
}

/**
 * The VOD half of what is playing — [LiveTuner]'s twin, and for the same reason: a film outlives the
 * screen that started it, so the full screen player and the mini player are two views of one stream.
 *
 * There is one engine and one surface, so the two tuners cannot both be playing. This one holds the
 * live tuner and stops it before it starts, and clears itself when the live tuner starts something —
 * one direction only, which is what keeps the pair from chasing each other.
 *
 * Resume positions are written on a timer as well as at the end, because a phone's way of leaving a
 * film is to be taken away by a phone call, not to press Stop.
 */
class VodTuner(
    private val context: Context,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val categoryDao: CategoryDao,
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val historyDao: HistoryDao,
    private val progressDao: ProgressDao,
    private val settings: SettingsRepository,
    private val streamUrlResolver: StreamUrlResolver,
    private val externalPlayerLauncher: ExternalPlayerLauncher,
    private val subtitleController: SubtitleController,
    private val metadata: MetadataRepository,
    private val session: PlaybackSession,
    private val liveTuner: LiveTuner,
    private val dataSaver: DataSaverGate,
    private val cast: CastController,
    private val contentAccess: ContentAccessPolicy,
    private val compatibleVariants: CompatibleVariantResolver,
    private val downloadDao: DownloadDao,
    private val downloadItemResolver: DownloadItemResolver,
    private val protectedDownloads: ProtectedDownloadStore,
    private val zenPlayAccount: ZenPlayAccount,
    val player: OwnTVPlayer,
    /** Lets core's background catalogue drain know a playlist is in use. */
    private val watchSession: tv.own.owntv.core.live.WatchSession,
) : CastHandoff {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val engine by lazy { MpvPlaybackEngine(player) }

    private val _playing = MutableStateFlow<VodPlayback?>(null)

    private val _compatibleAlternative = MutableStateFlow<CompatibleVodAlternative?>(null)

    /** A safer provider copy, exposed only after both playback engines terminally reject VOD video. */
    val compatibleAlternative: StateFlow<CompatibleVodAlternative?> = _compatibleAlternative

    /** Failed catalogue rows remain excluded for this logical title so Retry cannot form a loop. */
    private val attemptedMovieIds = linkedSetOf<Long>()
    private val attemptedEpisodeIds = linkedSetOf<Long>()

    /**
     * Invalidates suspended playback work as soon as another user/player intent begins. The logical
     * row alone is insufficient: restarting the same title or changing its queued rendition can
     * produce an equal [VodPlayback] while the old fallback coroutine is still resolving a URL.
     */
    private var playbackIntentGeneration = 0L

    private data class CompatiblePlaybackGuard(
        val generation: Long,
        val playback: VodPlayback,
        val profileId: Long,
        val mediaUrl: String?,
        val mediaContentKey: String?,
    )

    /**
     * [stop] saves through Room before it tears down the shared player. A replacement title can be
     * opened while that write is suspended, so the destructive half is guarded by the session that
     * requested it rather than by coroutine completion order.
     */
    private val playbackExitGate = VodPlaybackExitGate()

    /** The film or episode on screen, or null when nothing of ours is playing. */
    val playing: StateFlow<VodPlayback?> = _playing

    init {
        // The VOD twin of LiveTuner's watch session — same reason, same shape. A downloaded file
        // carries no sourceId and holds no session: it spends no provider connection.
        scope.launch {
            var held: Long? = null
            playing.collect { item ->
                val next = item?.sourceId
                if (next != held) {
                    held?.let { watchSession.close(it) }
                    next?.let { watchSession.open(it) }
                    held = next
                }
            }
        }
    }


    /** The profile the current stream was started for — a mid-film profile switch must not write its
     *  resume position into the new profile's list. */
    private var playingProfileId = -1L

    /** True only while the current player URI is the private decrypted-download provider. */
    private var playingProtectedDownload = false

    /** Entitlement of the exact profile/source that owns the open protected file. */
    private var protectedDownloadEntitlement: ZenPlayExpiryState? = null

    /** Makes the collector's profile observation and the final player hand-off one atomic decision. */
    private val protectedPlaybackProfileGate = ProtectedPlaybackProfileGate()

    /** The season queue handed to the player, kept so a player-driven advance can be followed. */
    private data class PlayingQueue(
        val show: SeriesEntity,
        val episodes: List<EpisodeEntity>,
        val profileId: Long,
        val parentTmdbId: Long?,
        /** Engine keys belong to the technical copies, while [episodes] remain the logical queue. */
        val playbackContentKeys: List<String?>,
        /** Used to exclude the exact replacement row if a queued replacement also cannot decode. */
        val playbackEpisodeIds: List<Long>,
    )

    private var playingQueue: PlayingQueue? = null

    init {
        // A protected file belongs to the profile that opened it. The provider also revokes open
        // descriptors when keys/files are destroyed, but an ordinary profile switch intentionally
        // keeps that profile's downloads. Stop just this local playback before the new profile can
        // continue consuming the old profile's already-open descriptor.
        scope.launch {
            var observedProfileId: Long? = null
            settings.activeProfileId.distinctUntilChanged().collect { activeProfileId ->
                if (observedProfileId != null && observedProfileId != activeProfileId) {
                    playbackIntentGeneration += 1L
                    _compatibleAlternative.value = null
                }
                observedProfileId = activeProfileId
                if (protectedPlaybackProfileGate.activeProfileChanged(activeProfileId)) {
                    revokeProtectedPlayback()
                }
            }
        }
        scope.launch {
            liveTuner.channel.collect { if (it != null) clearPlaying() }
        }
        // Catalogue lookup is deliberately armed only for a terminal unsupported-video failure.
        // Network, DRM, decoder-busy, recordings, protected downloads and every Live TV path keep
        // the normal Retry action and never scan for a different title.
        scope.launch {
            combine(player.error, player.errorInfo, playing) { failure, info, item ->
                Triple(failure, info, item)
            }.collectLatest { (failure, info, item) ->
                if (
                    item == null ||
                    playingProtectedDownload ||
                    item.mediaType !in setOf(MediaType.MOVIE, MediaType.EPISODE) ||
                    !isCompatibleVariantRecoveryFailure(failure, info, player.isLiveContent)
                ) {
                    _compatibleAlternative.value = null
                    return@collectLatest
                }

                val profileId = currentProfileId()
                if (profileId == null) {
                    _compatibleAlternative.value = null
                    return@collectLatest
                }

                _compatibleAlternative.value = when (item.mediaType) {
                    MediaType.MOVIE -> {
                        val original = withContext(Dispatchers.IO) { movieDao.getById(item.itemId) }
                        val replacement = original?.let {
                            compatibleVariants.findMovie(it, attemptedMovieIds + it.id)
                        }
                        if (
                            original != null && replacement != null &&
                            contentAccess.allows(profileId, original) &&
                            contentAccess.allows(profileId, replacement)
                        ) {
                            CompatibleVodAlternative.Movie(
                                original,
                                replacement,
                                compatibleMovieLabel(replacement),
                            )
                        } else null
                    }

                    MediaType.EPISODE -> {
                        val originalEpisode = withContext(Dispatchers.IO) {
                            seriesDao.getEpisodeById(item.itemId)
                        }
                        if (originalEpisode == null) {
                            null
                        } else {
                            val originalShow = withContext(Dispatchers.IO) {
                                seriesDao.getSeriesById(originalEpisode.seriesId)
                            }
                            val replacement = originalShow?.let { show ->
                                compatibleVariants.findEpisode(
                                    show,
                                    originalEpisode,
                                    attemptedEpisodeIds + originalEpisode.id,
                                )
                            }
                            if (
                                originalShow != null && replacement != null &&
                                contentAccess.allows(profileId, originalShow) &&
                                contentAccess.allows(profileId, originalEpisode) &&
                                contentAccess.allows(profileId, replacement.replacementShow) &&
                                contentAccess.allows(profileId, replacement.replacementEpisode)
                            ) {
                                CompatibleVodAlternative.Episode(
                                    originalShow,
                                    originalEpisode,
                                    replacement,
                                    compatibleEpisodeLabel(replacement),
                                )
                            } else null
                        }
                    }

                    else -> null
                }
            }
        }
        // The player advances a queue by itself (auto-next, and the HUD's prev/next), so everything
        // keyed to "the episode playing" — the title on screen, the resume position, the subtitle
        // search context — has to be re-pointed by whoever owns it. Nobody tells this class otherwise.
        scope.launch {
            player.queueItemChanged.collect { index ->
                val q = playingQueue ?: return@collect
                val episode = q.episodes.getOrNull(index) ?: return@collect
                playbackIntentGeneration += 1L
                _compatibleAlternative.value = null
                attemptedEpisodeIds.clear()
                attemptedEpisodeIds += episode.id
                q.playbackEpisodeIds.getOrNull(index)?.let(attemptedEpisodeIds::add)
                // Deliberately no saveProgress() here: by the time this arrives the player is already
                // on the new episode at position ~0, so a write would stamp that over the finished
                // one's real position. The five-second timer saved it while it was still playing.
                _playing.value = VodPlayback(
                    MediaType.EPISODE,
                    episode.id,
                    q.show.name,
                    episodeLabel(episode),
                    q.show.posterUrl,
                    q.show.id,
                    q.show.sourceId,
                )
                playingProfileId = q.profileId
                subtitleController.setEpisode(q.profileId, q.show, episode, q.parentTmdbId)
                recordHistory(q.profileId, MediaType.EPISODE, episode.id)
            }
        }
        scope.launch {
            while (true) {
                delay(PROGRESS_INTERVAL_MS)
                // A download opened just before the plan deadline must not remain readable for the
                // rest of a two-hour film. The cached dated entitlement is evaluated against the
                // current clock, so this also closes playback shortly after the exact expiry time.
                val entitlement = protectedDownloadEntitlement
                if (
                    playingProtectedDownload &&
                    (entitlement == null || !allowsProtectedPlayback(entitlement, System.currentTimeMillis()))
                ) {
                    revokeProtectedPlayback()
                    continue
                }
                saveProgress()
            }
        }
    }

    /**
     * Play a film. False means nothing opened here — it went to an external player, or the item could
     * not be resolved — so the caller must not navigate to the player screen.
     */
    suspend fun playMovie(movieId: Long, startPositionMs: Long = 0): Boolean {
        resetCompatibleAlternatives()
        attemptedMovieIds += movieId
        val movie = withContext(Dispatchers.IO) { movieDao.getById(movieId) } ?: return false
        val pid = currentProfileId() ?: return false
        if (!contentAccess.allows(pid, movie)) return false
        val source = withContext(Dispatchers.IO) { sourceDao.getById(movie.sourceId) }

        // #115 — a protected item cannot go to an external player: no intent extra carries a licence
        // URL, so the other app would open it and fail on the first segment.
        if (settings.externalPlayerMovies.first() && movie.drmConfig == null) {
            return handOver(movie.name, source, movie.streamUrl, movie.httpHeaders, pid, MediaType.MOVIE, movie.id)
        }

        if (!dataSaver.allowsStreaming()) return false
        val url = resolve(source, movie.streamUrl) ?: return false
        saveProgress()
        liveTuner.stop()
        val handedOver = cast.offer(
            this,
            CastRequest(
                url = url,
                title = movie.name,
                logoUrl = movie.posterUrl,
                isLive = false,
                startPositionMs = startPositionMs,
                httpHeaders = SourceOverrides.headersWithReferer(movie.httpHeaders, source),
                drm = movie.drmConfig != null,
            ),
        )
        if (!handedOver) {
            player.play(
                url = url,
                title = movie.name,
                year = movie.year?.toString(),
                isLive = false,
                startPositionMs = startPositionMs,
                userAgent = source?.userAgent,
                httpHeaders = SourceOverrides.headersWithReferer(movie.httpHeaders, source),
                drmConfig = movie.drmConfig,
                manifestType = movie.manifestType,
                contentKey = enginePinKey(movie.sourceId, "MOVIE", movie.remoteId),
                reconnectProvider = reconnectFor(source, movie.streamUrl),
                vodEngineOverride = SourceOverrides.vodEngineOf(source),
            )
        }
        began(pid, VodPlayback(MediaType.MOVIE, movie.id, movie.name, posterUrl = movie.posterUrl, sourceId = movie.sourceId), handedOver)
        playingQueue = null
        // Turns on the player's "Add subtitles" search for this film. The TMDB id is a precision
        // boost when the metadata cache has one, never a requirement.
        subtitleController.setMovie(pid, movie, runCatching { metadata.resolveMovie(movie)?.tmdbId?.toLong() }.getOrNull())
        return true
    }

    /**
     * Play one episode — and queue the rest of the series behind it, so the player's Next/Previous
     * and its automatic advance at the end of an episode have somewhere to go. Watching a series is
     * watching episode after episode; stopping dead at the end of each one is not the same feature.
     */
    suspend fun playEpisode(episodeId: Long, startPositionMs: Long = 0): Boolean {
        resetCompatibleAlternatives()
        attemptedEpisodeIds += episodeId
        val episode = withContext(Dispatchers.IO) { seriesDao.getEpisodeById(episodeId) } ?: return false
        val show = withContext(Dispatchers.IO) { seriesDao.getSeriesById(episode.seriesId) } ?: return false
        val pid = currentProfileId() ?: return false
        if (!contentAccess.allows(pid, episode)) return false
        val source = withContext(Dispatchers.IO) { sourceDao.getById(show.sourceId) }
        val title = show.name
        val subtitle = episodeLabel(episode)

        if (settings.externalPlayerSeries.first() && episode.drmConfig == null) {
            return handOver(title, source, episode.streamUrl, episode.httpHeaders, pid, MediaType.EPISODE, episode.id)
        }

        if (!dataSaver.allowsStreaming()) return false
        saveProgress()
        liveTuner.stop()

        val episodes = withContext(Dispatchers.IO) { seriesDao.episodesBySeriesOnce(show.id) }
            .ifEmpty { listOf(episode) }
        val startIndex = episodes.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
        val needsResolve = streamUrlResolver.needsResolve(source)

        // The receiver takes one item, not the season. Auto-advance to the next episode is one of the
        // things casting costs, along with the fallback ladder and the subtitle rendering — there is
        // no queue on a default receiver to hand the rest of the series to.
        val castUrl = resolve(source, episode.streamUrl)
        val handedOver = castUrl != null && cast.offer(
            this,
            CastRequest(
                url = castUrl,
                title = title,
                subtitle = subtitle,
                logoUrl = show.posterUrl,
                isLive = false,
                startPositionMs = startPositionMs,
                httpHeaders = SourceOverrides.headersWithReferer(episode.httpHeaders, source),
                drm = episode.drmConfig != null,
            ),
        )
        if (!handedOver) player.playEpisodes(
            items = episodes.map { ep ->
                PlaylistItem(
                    url = ep.streamUrl,
                    meta = MediaMeta(
                        title = title,
                        subtitle = episodeLabel(ep),
                        logoUrl = show.posterUrl,
                        contentKey = enginePinKey(show.sourceId, "EPISODE", ep.remoteId),
                        // v44 — audio/subtitle choices are remembered per series (owner decision 11).
                        trackKey = enginePinKey(show.sourceId, "SERIES", show.remoteId),
                        seasonNumber = ep.seasonNumber,
                        episodeNumber = ep.episodeNumber,
                    ),
                    // Stalker mints a link per episode, and it is short-lived — so it is minted as
                    // that episode loads, not once for the whole queue.
                    resolveUrl = if (needsResolve && source != null) {
                        { streamUrlResolver.resolve(source, ep.streamUrl, vod = true, episode = ep.episodeNumber) }
                    } else {
                        null
                    },
                    httpHeaders = SourceOverrides.headersWithReferer(ep.httpHeaders, source),
                    drmConfig = ep.drmConfig,
                    manifestType = ep.manifestType,
                )
            },
            startIndex = startIndex,
            startPositionMs = startPositionMs,
            userAgent = source?.userAgent,
            vodEngineOverride = SourceOverrides.vodEngineOf(source),
        )
        began(pid, VodPlayback(MediaType.EPISODE, episode.id, title, subtitle, show.posterUrl, show.id, show.sourceId), handedOver)
        val parentTmdbId = runCatching { metadata.resolveSeries(show)?.tmdbId?.toLong() }.getOrNull()
        subtitleController.setEpisode(pid, show, episode, parentTmdbId)
        // Nothing follows a cast episode, so there is no queue to follow either — and a queue kept
        // here would have the player's advance callback re-pointing a stream it is not driving.
        playingQueue = if (handedOver) {
            null
        } else {
            PlayingQueue(
                show = show,
                episodes = episodes,
                profileId = pid,
                parentTmdbId = parentTmdbId,
                playbackContentKeys = episodes.map { ep ->
                    enginePinKey(show.sourceId, "EPISODE", ep.remoteId)
                },
                playbackEpisodeIds = episodes.map(EpisodeEntity::id),
            )
        }
        return true
    }

    /**
     * Replace the failed provider rendition after the user confirms the player prompt.
     *
     * This never calls [playMovie] or [playEpisode]: those are new logical plays and deliberately
     * reset retry state. Here only the stream, headers and engine key change; the original catalogue
     * ids stay in [playing], progress, history and subtitle context.
     */
    suspend fun playCompatibleVersion(): Boolean {
        val selected = _compatibleAlternative.value ?: return false
        val current = _playing.value ?: return false
        val guard = captureCompatiblePlaybackGuard(current) ?: return false
        val replacementDurationMs = when (selected) {
            is CompatibleVodAlternative.Movie -> selected.replacement.durationSecs
            is CompatibleVodAlternative.Episode -> selected.replacement.replacementEpisode.durationSecs
        }?.toLong()?.times(1_000L)
        val startPositionMs = player.replacementResumePositionMs(replacementDurationMs)
        _compatibleAlternative.value = null
        saveProgress()
        return when (selected) {
            is CompatibleVodAlternative.Movie -> {
                playCompatibleMovie(selected, current, guard, startPositionMs)
            }
            is CompatibleVodAlternative.Episode -> {
                playCompatibleEpisode(selected, current, guard, startPositionMs)
            }
        }
    }

    private suspend fun playCompatibleMovie(
        selected: CompatibleVodAlternative.Movie,
        current: VodPlayback,
        guard: CompatiblePlaybackGuard,
        startPositionMs: Long,
    ): Boolean {
        if (current.mediaType != MediaType.MOVIE || current.itemId != selected.original.id) return false
        val profileId = guard.profileId
        if (
            !contentAccess.allows(profileId, selected.original) ||
            !contentAccess.allows(profileId, selected.replacement) ||
            !dataSaver.allowsStreaming()
        ) return false

        val replacement = selected.replacement
        val source = withContext(Dispatchers.IO) { sourceDao.getById(replacement.sourceId) }
        val url = resolve(source, replacement.streamUrl) ?: return false
        val replacementKey = enginePinKey(replacement.sourceId, "MOVIE", replacement.remoteId)
        val logicalTrackKey = enginePinKey(
            selected.original.sourceId,
            "MOVIE",
            selected.original.remoteId,
        )
        // Last operation before the synchronous hand-off. Every DAO/access/URL suspension above can
        // resume after Stop, a profile switch, a queue advance or another title has already begun.
        if (!compatiblePlaybackStillCurrent(guard)) return false
        attemptedMovieIds += selected.replacement.id
        player.play(
            url = url,
            title = selected.original.name,
            year = selected.original.year?.toString(),
            isLive = false,
            startPositionMs = startPositionMs,
            userAgent = source?.userAgent,
            httpHeaders = SourceOverrides.headersWithReferer(replacement.httpHeaders, source),
            drmConfig = replacement.drmConfig,
            manifestType = replacement.manifestType,
            contentKey = replacementKey,
            reconnectProvider = reconnectFor(source, replacement.streamUrl),
            vodEngineOverride = SourceOverrides.vodEngineOf(source),
            trackKey = logicalTrackKey,
        )
        val logicalPlayback = VodPlayback(
            mediaType = MediaType.MOVIE,
            itemId = selected.original.id,
            title = selected.original.name,
            posterUrl = selected.original.posterUrl,
            sourceId = selected.original.sourceId,
        )
        beginPlaybackState(profileId, logicalPlayback)
        playingQueue = null
        val tmdbId = runCatching { metadata.resolveMovie(selected.original)?.tmdbId?.toLong() }
            .getOrNull()
        subtitleController.setMovie(profileId, selected.original, tmdbId)
        recordHistory(profileId, MediaType.MOVIE, selected.original.id)
        return true
    }

    private suspend fun playCompatibleEpisode(
        selected: CompatibleVodAlternative.Episode,
        current: VodPlayback,
        guard: CompatiblePlaybackGuard,
        startPositionMs: Long,
    ): Boolean {
        if (current.mediaType != MediaType.EPISODE || current.itemId != selected.originalEpisode.id) {
            return false
        }
        val profileId = guard.profileId
        if (
            !contentAccess.allows(profileId, selected.originalShow) ||
            !contentAccess.allows(profileId, selected.originalEpisode) ||
            !contentAccess.allows(profileId, selected.replacement.replacementShow) ||
            !contentAccess.allows(profileId, selected.replacement.replacementEpisode) ||
            !dataSaver.allowsStreaming()
        ) return false

        // Pair the COMPLETE logical queue by season + episode. When the safer rendition does not
        // contain a future episode/season, keep that exact logical episode's original stream in the
        // queue. Omitting it would make auto-next stop at the last replacement; borrowing a nearby
        // replacement would be worse because it could play the wrong episode.
        val logicalQueue = playingQueue?.episodes
            ?.takeIf { queue -> queue.any { it.id == selected.originalEpisode.id } }
            ?: listOf(selected.originalEpisode)
        val currentNumber = selected.originalEpisode.seasonNumber to
            selected.originalEpisode.episodeNumber
        val replacementsByNumber = selected.replacement.replacementQueue
            .associateBy { replacement ->
                replacement.seasonNumber to replacement.episodeNumber
            }
            .toMutableMap()
            .apply {
                // The resolver selected this exact current episode after ranking duplicates; do not
                // let another duplicate with the same S/E replace that decision.
                put(currentNumber, selected.replacement.replacementEpisode)
            }
        val paired = logicalQueue.map { logical ->
            val number = logical.seasonNumber to logical.episodeNumber
            logical to (replacementsByNumber[number] ?: logical)
        }
        val startIndex = paired.indexOfFirst { (logical, _) ->
            logical.id == selected.originalEpisode.id
        }
        if (startIndex < 0) return false
        val replacementShow = selected.replacement.replacementShow
        val source = withContext(Dispatchers.IO) { sourceDao.getById(replacementShow.sourceId) }
        val needsResolve = streamUrlResolver.needsResolve(source)
        val logicalTrackKey = enginePinKey(
            selected.originalShow.sourceId,
            "SERIES",
            selected.originalShow.remoteId,
        )
        val playbackKeys = paired.map { (_, replacement) ->
            enginePinKey(replacementShow.sourceId, "EPISODE", replacement.remoteId)
        }
        // See the movie path: no suspended operation is allowed between this stable-snapshot guard
        // and playEpisodes(), so stale fallback work cannot replace a newer playback.
        if (!compatiblePlaybackStillCurrent(guard)) return false
        attemptedEpisodeIds += selected.replacement.replacementEpisode.id
        player.playEpisodes(
            items = paired.mapIndexed { index, (logical, replacement) ->
                PlaylistItem(
                    url = replacement.streamUrl,
                    meta = MediaMeta(
                        title = selected.originalShow.name,
                        subtitle = episodeLabel(logical),
                        logoUrl = selected.originalShow.posterUrl,
                        contentKey = playbackKeys[index],
                        trackKey = logicalTrackKey,
                        seasonNumber = logical.seasonNumber,
                        episodeNumber = logical.episodeNumber,
                    ),
                    resolveUrl = if (needsResolve && source != null) {
                        {
                            streamUrlResolver.resolve(
                                source,
                                replacement.streamUrl,
                                vod = true,
                                episode = replacement.episodeNumber,
                            )
                        }
                    } else null,
                    httpHeaders = SourceOverrides.headersWithReferer(replacement.httpHeaders, source),
                    drmConfig = replacement.drmConfig,
                    manifestType = replacement.manifestType,
                )
            },
            startIndex = startIndex,
            startPositionMs = startPositionMs,
            userAgent = source?.userAgent,
            vodEngineOverride = SourceOverrides.vodEngineOf(source),
        )
        val logicalPlayback = VodPlayback(
            mediaType = MediaType.EPISODE,
            itemId = selected.originalEpisode.id,
            title = selected.originalShow.name,
            subtitle = episodeLabel(selected.originalEpisode),
            posterUrl = selected.originalShow.posterUrl,
            seriesId = selected.originalShow.id,
            sourceId = selected.originalShow.sourceId,
        )
        beginPlaybackState(profileId, logicalPlayback)
        val parentTmdbId = runCatching {
            metadata.resolveSeries(selected.originalShow)?.tmdbId?.toLong()
        }.getOrNull()
        subtitleController.setEpisode(
            profileId,
            selected.originalShow,
            selected.originalEpisode,
            parentTmdbId,
        )
        playingQueue = PlayingQueue(
            show = selected.originalShow,
            episodes = paired.map { it.first },
            profileId = profileId,
            parentTmdbId = parentTmdbId,
            playbackContentKeys = playbackKeys,
            playbackEpisodeIds = paired.map { it.second.id },
        )
        recordHistory(profileId, MediaType.EPISODE, selected.originalEpisode.id)
        return true
    }

    /**
     * Play a file that is already on this phone.
     *
     * The file is decrypted only through ZenPlay's private provider. The cached subscription expiry
     * is checked locally first; a network refresh is attempted only when that entitlement does not
     * currently allow playback. No media URL, headers or reconnect path are exposed.
     */
    suspend fun playDownload(downloadId: Long): Boolean {
        resetCompatibleAlternatives()
        val pid = currentProfileId() ?: return false
        val storedDownload = withContext(Dispatchers.IO) {
            downloadDao.getByIdForProfile(downloadId, pid)
        } ?: return false
        if (storedDownload.status != DownloadStatus.COMPLETED) return false
        val download = withContext(Dispatchers.IO) {
            downloadItemResolver.resolve(storedDownload)
        } ?: return false
        // The source must still exist and still be linked to the row's owning profile. Never use the
        // active/default source here: a valid plan on source B must not unlock source A's file.
        val source = withContext(Dispatchers.IO) {
            sourceDao.observeForProfile(pid).first().firstOrNull { it.id == download.sourceId }
        } ?: return false
        val entitlement = zenPlayAccount.protectedPlaybackEntitlement(pid, source)
        if (!allowsProtectedPlayback(entitlement, System.currentTimeMillis())) return false
        if (!downloadAllowed(download.mediaType, download.itemId, pid)) return false
        val fileId = download.filePath
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.let(protectedDownloads::fileId)
            ?: return false
        val owner = runCatching {
            SecureDownloadOwner.fromContentKey(pid, download.sourceId, download.contentKey)
        }.getOrNull() ?: return false
        // Re-open against the owner immediately before playback. This makes a stale callback from a
        // previous profile/account harmless even though it still knows the opaque content URI.
        val valid = withContext(Dispatchers.IO) {
            runCatching { protectedDownloads.open(fileId, owner).use { it.plaintextLength > 0L } }
                .getOrDefault(false)
        }
        if (!valid) return false
        val filePath = protectedDownloads.contentUri(fileId).toString()
        saveProgress()
        liveTuner.stop()
        // A file on this phone is not reachable from a receiver — it would be asked for a path that
        // exists on the handset and nowhere else — so starting a download ends the cast rather than
        // leaving the television on the previous item.
        cast.release(this)
        val resume = withContext(Dispatchers.IO) {
            progressDao.get(pid, download.mediaType, download.itemId)
        }
        val seriesId = if (download.mediaType == MediaType.EPISODE) {
            withContext(Dispatchers.IO) { seriesDao.getEpisodeById(download.itemId)?.seriesId }
        } else null
        val playback = VodPlayback(
            download.mediaType,
            download.itemId,
            download.title,
            posterUrl = download.posterUrl,
            seriesId = seriesId,
        )
        // This is the last suspension before handing the file to the player. It deliberately runs on the same Main
        // dispatcher as the active-profile collector. The small synchronized gate then checks both
        // this fresh DataStore value and the collector's latest value, records protected ownership,
        // starts the player and exposes its flags without an interleaving window.
        val started = withContext(Dispatchers.Main.immediate) {
            val freshlyReadProfileId = settings.activeProfileId.first()
            protectedPlaybackProfileGate.startIfCurrent(pid, freshlyReadProfileId) {
                player.play(
                    url = filePath,
                    title = download.title,
                    isLive = false,
                    startPositionMs = resume?.positionMs ?: 0L,
                )
                protectedDownloadEntitlement = entitlement
                beginPlaybackState(pid, playback, protectedDownload = true)
                playingQueue = null
            }
        }
        if (!started) return false
        recordHistory(pid, playback.mediaType, playback.itemId)
        if (protectedPlaybackProfileGate.owns(pid)) {
            setDownloadSubtitleContext(pid, download.mediaType, download.itemId, filePath)
            // A profile switch while subtitle metadata was being resolved must not restore the old
            // profile's subtitle context after the collector already cleared playback.
            if (!protectedPlaybackProfileGate.owns(pid)) subtitleController.clear()
        }
        return true
    }

    /**
     * Play a finished live recording from the file on disk.
     *
     * Close to [playDownload] but deliberately thinner in three ways, all because a recording is not
     * a catalogue item:
     *
     * - **No watch history and no resume position.** Those are keyed by `(mediaType, itemId)`, and a
     *   recording's ids belong to the *channel* it came from. Writing them would put last night's
     *   programme into the history as though the channel itself had been watched, and would give the
     *   channel a resume position into a file.
     * - **No subtitle context.** OpenSubtitles matches films and episodes; a Tuesday news bulletin is
     *   neither, and the stream's own subtitle tracks are inside the `.ts` anyway.
     * - **`isLive = false`**, even though what it holds is live television. The flag describes the
     *   source, and a file on disk seeks, pauses and ends — treating it as live would take away the
     *   scrub bar on the one recording a user most wants to skip through.
     */
    suspend fun playRecording(filePath: String, title: String, posterUrl: String?): Boolean {
        resetCompatibleAlternatives()
        val pid = currentProfileId() ?: return false
        if (settings.externalPlayerFor(MediaType.LIVE).first()) {
            externalPlayerLauncher.launch(filePath, title)
            return false
        }
        saveProgress()
        liveTuner.stop()
        // A file on this phone is not reachable from a receiver, so starting one ends the cast rather
        // than leaving the television on whatever was playing before.
        cast.release(this)
        playbackExitGate.playbackStarted()
        player.play(url = filePath, title = title, isLive = false)
        player.exitAudioOnly()
        session.attach(engine)
        PlaybackService.start(context)
        playingProfileId = pid
        _playing.value = VodPlayback(MediaType.LIVE, RECORDING_ITEM_ID, title, posterUrl = posterUrl)
        playingQueue = null
        return true
    }

    /**
     * A downloaded film or episode can search for subtitles too — and better than a stream can: the
     * file is on disk, so OpenSubtitles can be matched on the file's own hash rather than on its name.
     */
    private suspend fun setDownloadSubtitleContext(pid: Long, mediaType: MediaType, itemId: Long, filePath: String) {
        runCatching {
            if (mediaType == MediaType.MOVIE) {
                val movie = withContext(Dispatchers.IO) { movieDao.getById(itemId) } ?: return
                subtitleController.setMovie(pid, movie, metadata.resolveMovie(movie)?.tmdbId?.toLong(), filePath)
            } else {
                val ep = withContext(Dispatchers.IO) { seriesDao.getEpisodeById(itemId) } ?: return
                val show = withContext(Dispatchers.IO) { seriesDao.getSeriesById(ep.seriesId) } ?: return
                subtitleController.setEpisode(pid, show, ep, metadata.resolveSeries(show)?.tmdbId?.toLong(), filePath)
            }
        }
    }

    /**
     * A kids profile must not reach an adult title just because its file is already on disk. A
     * download whose playlist has since been deleted cannot be classified at all, so a kids profile
     * is refused it — the same call the television makes.
     */
    private suspend fun downloadAllowed(mediaType: MediaType, itemId: Long, profileId: Long): Boolean {
        return when (mediaType) {
            MediaType.MOVIE -> movieDao.getById(itemId)?.let { contentAccess.allows(profileId, it) } == true
            MediaType.EPISODE -> {
                val episode = seriesDao.getEpisodeById(itemId) ?: return false
                contentAccess.allows(profileId, episode)
            }
            else -> false
        }
    }

    /** Hand the item to VLC, MX Player or whatever else is installed — the long-press action, and the
     *  "External player" setting's route. History is still recorded; a resume position cannot be. */
    suspend fun playExternal(movie: MovieEntity): Boolean {
        resetCompatibleAlternatives()
        val pid = currentProfileId() ?: return false
        if (!contentAccess.allows(pid, movie)) return false
        val source = withContext(Dispatchers.IO) { sourceDao.getById(movie.sourceId) }
        return handOver(movie.name, source, movie.streamUrl, movie.httpHeaders, pid, MediaType.MOVIE, movie.id)
    }

    /** [playExternal] for an episode of [show]. */
    suspend fun playExternal(show: SeriesEntity, episode: EpisodeEntity): Boolean {
        resetCompatibleAlternatives()
        val pid = currentProfileId() ?: return false
        if (episode.seriesId != show.id) return false
        if (!contentAccess.allows(pid, episode)) return false
        val source = withContext(Dispatchers.IO) { sourceDao.getById(show.sourceId) }
        return handOver(show.name, source, episode.streamUrl, episode.httpHeaders, pid, MediaType.EPISODE, episode.id)
    }

    /** Stop playing altogether — the mini player's close button, and nothing else. */
    fun stop() {
        resetCompatibleAlternatives()
        val ticket = playbackExitGate.requestExit() ?: return
        // Capture both the identity and clock before launching. If Room is busy and a replacement
        // title starts first, this write still belongs to the title the user actually closed.
        val progress = progressSnapshot()
        scope.launch {
            progress?.let { persistProgress(it) }
            // A newer title (or Live TV) owns the singleton player now. Its session invalidated this
            // ticket, so an old exit must not clear state, release Cast/MediaSession, or call stop().
            if (!playbackExitGate.commitExit(ticket)) return@launch
            clearPlaying(invalidateExitTickets = false)
            cast.release(this@VodTuner)
            session.attach(null)
            PlaybackService.stop(context)
            player.stop()
        }
    }

    /** Prevent duplicate expiry/profile collectors from queuing several asynchronous stop jobs. */
    private fun revokeProtectedPlayback() {
        if (!playingProtectedDownload) {
            protectedPlaybackProfileGate.clear()
            return
        }
        protectedPlaybackProfileGate.clear()
        playingProtectedDownload = false
        protectedDownloadEntitlement = null
        stop()
    }

    /** The television is taking the film; the phone keeps only where it had got to. */
    override fun releaseToCast(): Long {
        resetCompatibleAlternatives()
        val position = player.position.value
        session.attach(null)
        player.stop()
        return position
    }

    /**
     * The cast ended, so the film comes back here — at the position the receiver had reached, which
     * is the whole point of the handover being a handover rather than a restart.
     */
    override fun resumeFromCast(positionMs: Long) {
        val current = _playing.value ?: return
        scope.launch {
            when (current.mediaType) {
                MediaType.EPISODE -> playEpisode(current.itemId, positionMs)
                else -> playMovie(current.itemId, positionMs)
            }
        }
    }

    /** Write the resume position now — on the timer, when another item starts, and when the user stops. */
    suspend fun saveProgress() {
        val snapshot = progressSnapshot() ?: return
        persistProgress(snapshot)
    }

    private data class ProgressSnapshot(
        val playback: VodPlayback,
        val profileId: Long,
        val positionMs: Long,
        val durationMs: Long,
    )

    /** Snapshot synchronously: a later Room suspension must never mix title A with title B's clock. */
    private fun progressSnapshot(): ProgressSnapshot? {
        val current = _playing.value ?: return null
        // Whichever engine actually has the film. Casting for an hour and coming back to a resume
        // position of zero would be the same bug as never writing one at all.
        val remote = cast.engine.value
        val position = remote?.position?.value ?: player.position.value
        val duration = remote?.duration?.value ?: player.duration.value
        if (position <= 0 || duration <= 0 || playingProfileId < 0) return null
        return ProgressSnapshot(current, playingProfileId, position, duration)
    }

    private suspend fun persistProgress(snapshot: ProgressSnapshot) {
        if (currentProfileId() != snapshot.profileId) return
        runCatching {
            withContext(Dispatchers.IO) {
                progressDao.save(
                    PlaybackProgressEntity(
                        profileId = snapshot.profileId,
                        mediaType = snapshot.playback.mediaType,
                        itemId = snapshot.playback.itemId,
                        positionMs = snapshot.positionMs,
                        durationMs = snapshot.durationMs,
                    ),
                )
            }
        }
    }

    /** [onCast] = the receiver took it, so none of the local engine's housekeeping applies and the
     *  session is already pointed at the remote one. */
    private suspend fun began(
        profileId: Long,
        what: VodPlayback,
        onCast: Boolean = false,
        protectedDownload: Boolean = false,
    ) {
        beginPlaybackState(profileId, what, onCast, protectedDownload)
        recordHistory(profileId, what.mediaType, what.itemId)
    }

    /** Non-suspending half of [began], also used inside the protected-profile hand-off gate. */
    private fun beginPlaybackState(
        profileId: Long,
        what: VodPlayback,
        onCast: Boolean = false,
        protectedDownload: Boolean = false,
    ) {
        playbackIntentGeneration += 1L
        // Invalidates any older stop that is still waiting for its progress write to finish. This is
        // on the main playback path and runs before any suspending work below.
        playbackExitGate.playbackStarted()
        if (!onCast) {
            // A new film starts with its picture on, whatever the last thing playing was doing. The
            // engine holds the sound-only flag across a change of stream, so without this a channel
            // watched without a picture handed the next film the same fate.
            player.exitAudioOnly()
            session.attach(engine)
        }
        PlaybackService.start(context)
        playingProfileId = profileId
        playingProtectedDownload = protectedDownload
        if (!protectedDownload) {
            protectedDownloadEntitlement = null
            protectedPlaybackProfileGate.clear()
        }
        _playing.value = what
    }

    private suspend fun recordHistory(profileId: Long, mediaType: MediaType, itemId: Long) {
        runCatching {
            withContext(Dispatchers.IO) {
                historyDao.record(WatchHistoryEntity(profileId = profileId, mediaType = mediaType, itemId = itemId))
            }
        }
    }

    private fun clearPlaying(invalidateExitTickets: Boolean = true) {
        if (invalidateExitTickets) playbackExitGate.playbackCleared()
        resetCompatibleAlternatives()
        _playing.value = null
        playingProfileId = -1L
        playingProtectedDownload = false
        protectedDownloadEntitlement = null
        protectedPlaybackProfileGate.clear()
        playingQueue = null
        // The player's ADD SUBTITLES entry exists only for a film or an episode. Left set, it would
        // still be offering to search OpenSubtitles for the film while a channel is playing.
        subtitleController.clear()
    }

    private fun resetCompatibleAlternatives() {
        playbackIntentGeneration += 1L
        _compatibleAlternative.value = null
        attemptedMovieIds.clear()
        attemptedEpisodeIds.clear()
    }

    /** Snapshot both ownership layers: logical catalogue identity and exact physical player load. */
    private fun captureCompatiblePlaybackGuard(
        playback: VodPlayback,
    ): CompatiblePlaybackGuard? {
        if (_playing.value != playback || playingProfileId < 0L || player.isLiveContent) return null
        val mediaUrl = player.currentMediaUrl ?: return null
        // Hand-written M3U rows can legitimately lack remoteId/contentKey; their exact URL plus the
        // generation and logical identity still form a stable guard.
        val mediaContentKey = player.currentMediaContentKey
        return CompatiblePlaybackGuard(
            generation = playbackIntentGeneration,
            playback = playback,
            profileId = playingProfileId,
            mediaUrl = mediaUrl,
            mediaContentKey = mediaContentKey,
        )
    }

    /**
     * Re-check after every suspending lookup and immediately before replacing the player's load.
     * The second state comparison happens after DataStore returns because a profile/playback change
     * can occur while that read is suspended.
     */
    private suspend fun compatiblePlaybackStillCurrent(
        guard: CompatiblePlaybackGuard,
    ): Boolean {
        if (!compatiblePlaybackMatchesSnapshot(guard)) return false
        val activeProfileId = currentProfileId()
        return activeProfileId == guard.profileId && compatiblePlaybackMatchesSnapshot(guard)
    }

    private fun compatiblePlaybackMatchesSnapshot(guard: CompatiblePlaybackGuard): Boolean =
        playbackIntentGeneration == guard.generation &&
            _playing.value == guard.playback &&
            playingProfileId == guard.profileId &&
            !player.isLiveContent &&
            player.currentMediaUrl == guard.mediaUrl &&
            player.currentMediaContentKey == guard.mediaContentKey

    private fun compatibleMovieLabel(movie: MovieEntity): String = buildList {
        add(movie.name)
        qualityLabel(movie.qualityRank)?.let(::add)
        movie.providerLanguage?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
        movie.containerExt?.trim()?.removePrefix(".")?.uppercase()
            ?.takeIf(String::isNotEmpty)?.let(::add)
    }.joinToString(" · ")

    private fun compatibleEpisodeLabel(variant: CompatibleEpisodeVariant): String = buildList {
        add(variant.replacementShow.name)
        qualityLabel(variant.replacementShow.qualityRank)?.let(::add)
        variant.replacementShow.providerLanguage?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
        variant.replacementEpisode.containerExt?.trim()?.removePrefix(".")?.uppercase()
            ?.takeIf(String::isNotEmpty)?.let(::add)
    }.joinToString(" · ")

    private suspend fun handOver(
        title: String,
        source: SourceEntity?,
        streamUrl: String,
        httpHeaders: String?,
        profileId: Long,
        mediaType: MediaType,
        itemId: Long,
    ): Boolean {
        val url = resolve(source, streamUrl) ?: return false
        externalPlayerLauncher.launch(
            url = url,
            title = title,
            userAgent = source?.userAgent,
            httpHeaders = SourceOverrides.headersWithReferer(httpHeaders, source),
        )
        runCatching {
            withContext(Dispatchers.IO) {
                historyDao.record(WatchHistoryEntity(profileId = profileId, mediaType = mediaType, itemId = itemId))
            }
        }
        return false
    }

    /** Stalker mints a play URL per open; the stored "URL" is a portal command until then. */
    private suspend fun resolve(source: SourceEntity?, streamUrl: String): String? =
        if (streamUrlResolver.needsResolve(source)) {
            runCatching { streamUrlResolver.resolve(source!!, streamUrl, vod = true) }.getOrNull()
        } else {
            streamUrl
        }

    /** A Stalker link dies before a long film ends; give the player a way to mint a fresh one. Null
     *  for M3U and Xtream, which also clears whatever the previous item left on the player. */
    private fun reconnectFor(source: SourceEntity?, streamUrl: String): ReconnectUrlProvider? =
        if (streamUrlResolver.needsResolve(source)) {
            ReconnectUrlProvider {
                runCatching { streamUrlResolver.resolve(source!!, streamUrl, vod = true) }.getOrNull()
            }
        } else {
            null
        }

    private suspend fun currentProfileId(): Long? {
        val preferred = settings.activeProfileId.first()
        return if (preferred >= 0) profileDao.resolveExistingProfileId(preferred) else null
    }

    /** "S2 · E4 Title" — core's wording, so the player's second line reads as it does on the TV. */
    private fun episodeLabel(episode: EpisodeEntity): String =
        if (episode.name.isBlank()) {
            context.getString(R.string.content_season_episode, episode.seasonNumber, episode.episodeNumber)
        } else {
            context.getString(
                R.string.content_season_episode_title,
                episode.seasonNumber,
                episode.episodeNumber,
                episode.name,
            )
        }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 5_000L
    }
}
