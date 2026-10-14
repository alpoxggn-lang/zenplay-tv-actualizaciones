package tv.own.owntv.core.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.EpisodeEntity

class LauncherLaunchResolverTest {
    @Test
    fun `explicit episode deep link rejects an episode from another series`() {
        val deepLink = LauncherDeepLink.Episode(
            seriesItemId = 10L,
            episodeItemId = 77L,
        )
        val crossedEpisode = episode(id = 77L, seriesId = 11L)

        assertFalse(isAuthorizedEpisodeItem(deepLink, authorizedSeriesId = 10L, episode = crossedEpisode))
    }

    @Test
    fun `explicit episode deep link accepts an episode from its authorized series`() {
        val deepLink = LauncherDeepLink.Episode(
            seriesItemId = 10L,
            episodeItemId = 77L,
        )
        val matchingEpisode = episode(id = 77L, seriesId = 10L)

        assertTrue(isAuthorizedEpisodeItem(deepLink, authorizedSeriesId = 10L, episode = matchingEpisode))
    }

    private fun episode(id: Long, seriesId: Long) = EpisodeEntity(
        id = id,
        seriesId = seriesId,
        seasonNumber = 1,
        episodeNumber = 1,
        name = "Episode $id",
        streamUrl = "https://example.invalid/$id",
    )
}
