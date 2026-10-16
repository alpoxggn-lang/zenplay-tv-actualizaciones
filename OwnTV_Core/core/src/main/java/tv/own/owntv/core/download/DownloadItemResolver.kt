package tv.own.owntv.core.download

import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType

/**
 * Resolves a downloaded item's volatile catalogue id from its exact stable ownership identity.
 *
 * A catalogue sync may replace movie/episode row ids while the protected file remains valid. A
 * download is only relinked when the current row belongs to the same source and recreates the
 * stored content key. Remote ids are preferred; URL recovery is accepted only when unique within
 * the source. The conditional update also pins the repair to the original profile/source/key.
 */
class DownloadItemResolver(
    private val downloadDao: DownloadDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
) {
    suspend fun resolve(download: DownloadEntity): DownloadEntity? {
        if (download.profileId < 0L || download.sourceId < 0L || download.contentKey.isBlank()) return null

        val resolved = when (download.mediaType) {
            MediaType.MOVIE -> {
                val movie = movieDao.getById(download.itemId)?.takeIf { current ->
                    movieMatchesDownload(download, current)
                }
                    ?: DownloadIdentity.movieRemoteId(download.contentKey)
                        ?.let { movieDao.findByRemote(download.sourceId, it) }
                        ?.takeIf { movieMatchesDownload(download, it) }
                    ?: movieDao.findByStreamUrl(download.sourceId, download.streamUrl)
                        .singleOrNull()
                        ?.takeIf { movieMatchesDownload(download, it) }
                    ?: return null
                download.copy(itemId = movie.id, streamUrl = movie.streamUrl)
            }
            MediaType.EPISODE -> {
                val byId = seriesDao.getEpisodeById(download.itemId)?.let { episode ->
                    seriesDao.getSeriesById(episode.seriesId)?.takeIf { show ->
                        episodeMatchesDownload(download, show, episode)
                    }?.let { it to episode }
                }
                val pair = byId
                    ?: DownloadIdentity.episodeRemoteIds(download.contentKey)?.let { (showRemote, episodeRemote) ->
                        seriesDao.findSeriesByRemote(download.sourceId, showRemote)?.let { show ->
                            seriesDao.findEpisodeByRemote(show.id, episodeRemote)
                                ?.takeIf { episodeMatchesDownload(download, show, it) }
                                ?.let { show to it }
                        }
                    }
                    ?: seriesDao.findEpisodesByStreamUrl(download.sourceId, download.streamUrl)
                        .singleOrNull()
                        ?.let { episode ->
                            seriesDao.getSeriesById(episode.seriesId)?.takeIf { show ->
                                episodeMatchesDownload(download, show, episode)
                            }?.let { it to episode }
                        }
                    ?: return null
                download.copy(itemId = pair.second.id, streamUrl = pair.second.streamUrl)
            }
            else -> return null
        }

        if (resolved.itemId != download.itemId || resolved.streamUrl != download.streamUrl) {
            val updated = downloadDao.relink(
                id = download.id,
                profileId = download.profileId,
                sourceId = download.sourceId,
                contentKey = download.contentKey,
                itemId = resolved.itemId,
                streamUrl = resolved.streamUrl,
                timestamp = System.currentTimeMillis(),
            )
            if (updated == 0) return null
        }
        return resolved
    }

}

internal fun movieMatchesDownload(download: DownloadEntity, movie: MovieEntity): Boolean =
    download.sourceId == movie.sourceId &&
        if (DownloadIdentity.isLegacy(download.contentKey)) {
            movie.streamUrl == download.streamUrl
        } else {
            DownloadIdentity.movie(movie) == download.contentKey
        }

internal fun episodeMatchesDownload(download: DownloadEntity, show: SeriesEntity, episode: EpisodeEntity): Boolean =
    download.sourceId == show.sourceId && episode.seriesId == show.id &&
        if (DownloadIdentity.isLegacy(download.contentKey)) {
            episode.streamUrl == download.streamUrl
        } else {
            DownloadIdentity.episode(show, episode) == download.contentKey
        }
