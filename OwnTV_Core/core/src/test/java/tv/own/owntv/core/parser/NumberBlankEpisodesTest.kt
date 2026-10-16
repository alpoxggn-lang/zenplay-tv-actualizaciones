package tv.own.owntv.core.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberBlankEpisodesTest {
    private fun ep(id: String, season: Int, number: Int) = XtEpisode(id, season, number, "", "mkv")

    @Test fun blankNumbersTakeTheirPositionWithinTheirSeason() {
        val out = numberBlankEpisodes(listOf(ep("a", 1, 0), ep("b", 1, 0), ep("c", 2, 0), ep("d", 1, 0)))
        assertEquals(listOf(1, 2, 1, 3), out.map { it.episodeNumber })
    }

    @Test fun numberedEpisodesStayUnchanged() {
        val out = numberBlankEpisodes(listOf(ep("a", 1, 1), ep("b", 1, 2), ep("c", 1, 0), ep("d", 1, 4)))
        assertEquals(listOf(1, 2, 3, 4), out.map { it.episodeNumber })
    }
}
