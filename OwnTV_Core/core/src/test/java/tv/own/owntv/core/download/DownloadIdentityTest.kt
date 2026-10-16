package tv.own.owntv.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity

class DownloadIdentityTest {
    @Test
    fun `movie key survives local id and title changes`() {
        val first = movie(id = 7, name = "Arrival")
        val refreshed = movie(id = 900, name = "Arrival 4K")

        assertEquals(DownloadIdentity.movie(first), DownloadIdentity.movie(refreshed))
        assertEquals("movie:42", DownloadIdentity.movieRemoteId(DownloadIdentity.movie(first)))
    }

    @Test
    fun `same title from two sources cannot share identity or path`() {
        val a = movie(sourceId = 10, remoteId = "42")
        val b = movie(sourceId = 11, remoteId = "42")

        assertNotEquals(DownloadIdentity.movie(a), DownloadIdentity.movie(b))
        assertNotEquals(
            DownloadIdentity.scopedDirectory(1, a.sourceId, "Movies"),
            DownloadIdentity.scopedDirectory(1, b.sourceId, "Movies"),
        )
    }

    @Test
    fun `same content from two profiles is stored in different directories`() {
        assertNotEquals(
            DownloadIdentity.scopedDirectory(1, 10, "Movies"),
            DownloadIdentity.scopedDirectory(2, 10, "Movies"),
        )
    }

    @Test
    fun `movie without remote id can be matched by stable source scoped stream`() {
        val before = movie(id = 7, sourceId = 10, name = "Arrival", remoteId = null)
        val afterRefresh = movie(id = 900, sourceId = 10, name = "Arrival remastered", remoteId = null)
        val otherSource = movie(id = 901, sourceId = 11, name = "Arrival", remoteId = null)
        val replacedStream = afterRefresh.copy(streamUrl = "https://example.test/replaced.mp4")

        assertEquals(DownloadIdentity.movie(before), DownloadIdentity.movie(afterRefresh))
        assertNotEquals(DownloadIdentity.movie(before), DownloadIdentity.movie(otherSource))
        assertNotEquals(DownloadIdentity.movie(before), DownloadIdentity.movie(replacedStream))
        assertEquals(null, DownloadIdentity.movieRemoteId(DownloadIdentity.movie(before)))
    }

    @Test
    fun `episode identity includes its show`() {
        val episode = episode(remoteId = "1")
        val firstShow = series(remoteId = "show-a")
        val otherShow = series(remoteId = "show-b")

        val first = DownloadIdentity.episode(firstShow, episode)
        val other = DownloadIdentity.episode(otherShow, episode)

        assertNotEquals(first, other)
        assertEquals("show-a" to "1", DownloadIdentity.episodeRemoteIds(first))
    }

    @Test
    fun `episode without remote ids survives local row replacement through stream identity`() {
        val beforeShow = series(id = 20, sourceId = 10, remoteId = null)
        val beforeEpisode = episode(id = 30, seriesId = 20, remoteId = null)
        val refreshedShow = series(id = 200, sourceId = 10, remoteId = null)
        val refreshedEpisode = episode(id = 300, seriesId = 200, remoteId = null)

        assertEquals(
            DownloadIdentity.episode(beforeShow, beforeEpisode),
            DownloadIdentity.episode(refreshedShow, refreshedEpisode),
        )
        assertEquals(
            null,
            DownloadIdentity.episodeRemoteIds(DownloadIdentity.episode(beforeShow, beforeEpisode)),
        )
    }

    @Test
    fun `provider ids containing separators round trip`() {
        val key = DownloadIdentity.episode(
            series(remoteId = "show:alpha/2026"),
            episode(remoteId = "s01:e02?lang=es"),
        )

        assertEquals("show:alpha/2026" to "s01:e02?lang=es", DownloadIdentity.episodeRemoteIds(key))
    }

    @Test
    fun `filename keeps extension and gains a deterministic collision suffix`() {
        val key = DownloadIdentity.movie(movie())
        val first = DownloadIdentity.scopedFileName("Arrival.mp4", key)
        val second = DownloadIdentity.scopedFileName("Arrival.mp4", key)

        assertEquals(first, second)
        assertTrue(first.startsWith("Arrival--"))
        assertTrue(first.endsWith(".mp4"))
        assertFalse(first.contains("movie:42"))
    }

    private fun movie(
        id: Long = 1,
        sourceId: Long = 10,
        name: String = "Arrival",
        remoteId: String? = "movie:42",
    ) = MovieEntity(
        id = id,
        sourceId = sourceId,
        name = name,
        streamUrl = "https://example.test/movie.mp4",
        remoteId = remoteId,
    )

    private fun series(
        id: Long = 20,
        sourceId: Long = 10,
        remoteId: String?,
    ) = SeriesEntity(
        id = id,
        sourceId = sourceId,
        name = "Show",
        remoteId = remoteId,
    )

    private fun episode(
        id: Long = 30,
        seriesId: Long = 20,
        remoteId: String?,
    ) = EpisodeEntity(
        id = id,
        seriesId = seriesId,
        seasonNumber = 1,
        episodeNumber = 2,
        name = "Episode",
        streamUrl = "https://example.test/episode.mp4",
        remoteId = remoteId,
    )
}
