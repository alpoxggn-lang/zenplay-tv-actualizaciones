package tv.own.owntv.core.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType

class DownloadItemResolverTest {
    @Test
    fun `movie relink candidate must match exact source and stable key`() {
        val current = MovieEntity(id = 45, sourceId = 8, name = "Arrival", streamUrl = "https://a/movie", remoteId = "m-1")
        val download = DownloadEntity(
            profileId = 3,
            sourceId = 8,
            contentKey = DownloadIdentity.movie(current),
            mediaType = MediaType.MOVIE,
            itemId = 2,
            title = current.name,
            streamUrl = current.streamUrl,
        )

        assertTrue(movieMatchesDownload(download, current))
        assertFalse(movieMatchesDownload(download, current.copy(sourceId = 9)))
        assertFalse(movieMatchesDownload(download, current.copy(remoteId = "different")))
    }

    @Test
    fun `legacy movie id is accepted only with its exact stored stream URL`() {
        val movie = MovieEntity(id = 45, sourceId = 8, name = "Arrival", streamUrl = "https://a/movie")
        val legacyDownload = DownloadEntity(
            profileId = 3,
            sourceId = 8,
            contentKey = "legacy:movie:45",
            mediaType = MediaType.MOVIE,
            itemId = movie.id,
            title = movie.name,
            streamUrl = movie.streamUrl,
        )

        assertTrue(movieMatchesDownload(legacyDownload, movie))
        assertFalse(movieMatchesDownload(legacyDownload, movie.copy(streamUrl = "https://a/reused-id")))
    }

    @Test
    fun `episode relink candidate must match exact source and show plus episode key`() {
        val show = SeriesEntity(id = 20, sourceId = 8, name = "Example", remoteId = "s-1")
        val episode = EpisodeEntity(
            id = 45,
            seriesId = show.id,
            seasonNumber = 1,
            episodeNumber = 2,
            name = "Second",
            streamUrl = "https://a/episode",
            remoteId = "e-2",
        )
        val download = DownloadEntity(
            profileId = 3,
            sourceId = 8,
            contentKey = DownloadIdentity.episode(show, episode),
            mediaType = MediaType.EPISODE,
            itemId = 2,
            title = episode.name,
            streamUrl = episode.streamUrl,
        )

        assertTrue(episodeMatchesDownload(download, show, episode))
        assertFalse(episodeMatchesDownload(download, show.copy(sourceId = 9), episode))
        assertFalse(episodeMatchesDownload(download, show.copy(remoteId = "different"), episode))
        assertFalse(episodeMatchesDownload(download, show, episode.copy(remoteId = "different")))
    }

    @Test
    fun `legacy episode id is accepted only with exact source show and stored stream URL`() {
        val show = SeriesEntity(id = 20, sourceId = 8, name = "Example")
        val episode = EpisodeEntity(
            id = 45,
            seriesId = show.id,
            seasonNumber = 1,
            episodeNumber = 2,
            name = "Second",
            streamUrl = "https://a/episode",
        )
        val legacyDownload = DownloadEntity(
            profileId = 3,
            sourceId = 8,
            contentKey = "legacy:episode:45",
            mediaType = MediaType.EPISODE,
            itemId = episode.id,
            title = episode.name,
            streamUrl = episode.streamUrl,
        )

        assertTrue(episodeMatchesDownload(legacyDownload, show, episode))
        assertFalse(episodeMatchesDownload(legacyDownload, show, episode.copy(streamUrl = "https://a/reused-id")))
        assertFalse(episodeMatchesDownload(legacyDownload, show.copy(sourceId = 9), episode))
        assertFalse(episodeMatchesDownload(legacyDownload, show.copy(id = 21), episode))
    }
}
