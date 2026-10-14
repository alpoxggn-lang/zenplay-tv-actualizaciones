package tv.own.owntv.features.content

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the final TV playback boundaries against bypassing profile/customization policy. */
class TvContentAccessGateContractTest {
    private fun source(path: String) = File("src/main/java/$path").readText()

    @Test
    fun `movie and episode playback use the shared fail closed policy`() {
        val movies = source("tv/own/owntv/features/movies/MovieViewModel.kt")
        val series = source("tv/own/owntv/features/series/SeriesViewModel.kt")

        assertTrue(movies.contains("private val contentAccess: ContentAccessPolicy"))
        assertTrue(movies.contains("if (!canPlay(movie, pid)) return@launch"))
        assertTrue(movies.contains("if (!canPlay(movie, currentProfileId())) return false"))
        assertTrue(movies.contains("movieDao.getById(movie.id)?.takeIf { it.sourceId == movie.sourceId }"))

        assertTrue(series.contains("private val contentAccess: ContentAccessPolicy"))
        assertTrue(series.contains("if (!canPlay(show, episode, pid)) return@launch"))
        assertTrue(series.contains("episode.seriesId != show.id"))
        assertTrue(series.contains("queue.filter { it.seriesId == show.id }"))
        assertTrue(series.contains("return contentAccess.allows(profileId, storedEpisode)"))

        assertFalse(movies.contains("AdultCategoryClassifier.allows"))
        assertFalse(series.contains("AdultCategoryClassifier.allows"))
    }

    @Test
    fun `live playback preview multiview archive and recording share one final gate`() {
        val live = source("tv/own/owntv/features/live/LiveViewModel.kt")

        assertTrue(live.contains("private val contentAccess: ContentAccessPolicy"))
        assertTrue(live.contains("if (!canAccess(channel, profileId)) return null"))
        assertTrue(live.contains("if (!canAccess(channel))"))
        assertTrue(live.contains("engine.stop()"))
        assertTrue(live.contains("if (!canAccess(channel, pid)) return@launch"))
        assertTrue(live.contains("if (!canAccess(ch, pid)) return@launch"))
        assertTrue(live.contains("if (!canAccess(ch)) return false"))
        assertTrue(live.contains("channelDao.getById(channel.id)?.takeIf { it.sourceId == channel.sourceId }"))
        assertFalse(live.contains("AdultCategoryClassifier.allows"))
    }

    @Test
    fun `search and guide direct actions cannot bypass final policy`() {
        val search = source("tv/own/owntv/features/search/SearchViewModel.kt")
        val guide = source("tv/own/owntv/features/epg/EpgViewModel.kt")

        assertTrue(search.contains("private val contentAccess: ContentAccessPolicy"))
        assertTrue(search.contains("movieDao.getById(movie.id)?.takeIf { it.sourceId == movie.sourceId }"))
        assertTrue(search.contains("CustomizeKeys.movie(stored)"))
        assertFalse(search.contains("AdultCategoryClassifier.allows"))

        assertTrue(guide.contains("private val contentAccess: ContentAccessPolicy"))
        assertTrue(guide.split("authorizedStoredChannel(channel, pid) ?: return@launch").size - 1 >= 2)
        assertTrue(guide.contains("authorizedStoredChannel(channel, profileId) ?: return@launch"))
        assertTrue(guide.contains("recordings.addSeriesRule(pid, storedChannel, programme.title)"))
        assertTrue(guide.contains("channel = storedChannel"))
        assertTrue(guide.contains("channelDao.getById(channel.id)?.takeIf { it.sourceId == channel.sourceId }"))
        assertFalse(guide.contains("AdultCategoryClassifier.allows"))
    }
}
