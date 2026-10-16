package tv.own.owntv.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.EpisodeEntity

class EpisodeOrderTest {
    private fun ep(season: Int, number: Int) = EpisodeEntity(
        seriesId = 1, seasonNumber = season, episodeNumber = number, name = "", streamUrl = "u$season-$number",
    )

    @Test fun specialsComeAfterRegularSeasons() {
        val sorted = listOf(ep(0, 1), ep(2, 1), ep(1, 2), ep(0, 2), ep(1, 1)).sortedWith(EpisodeOrder.episodes)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1, 0 to 1, 0 to 2), sorted.map { it.seasonNumber to it.episodeNumber })
    }

    @Test fun openingAndFollowingSeasonSkipGapsAndPutSpecialsLast() {
        val seasons = listOf(0, 1, 2, 4)
        assertEquals(1, EpisodeOrder.openingSeason(seasons))
        assertEquals(4, EpisodeOrder.seasonAfter(seasons, 2))
        assertEquals(0, EpisodeOrder.seasonAfter(seasons, 4))
        assertNull(EpisodeOrder.seasonAfter(seasons, 0))
    }
}
