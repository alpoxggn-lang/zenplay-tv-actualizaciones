package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class M3uEpisodeNameTest {
    @Test fun recognisesWordedAndLongEpisodeMarkers() {
        with(M3uSyncer.parseM3uEpisode("Show Season 2 Episode 1050 - Finale")) {
            assertEquals("Show", show)
            assertEquals(2, season)
            assertEquals(1050, episode)
            assertEquals("Finale", title)
        }
        with(M3uSyncer.parseM3uEpisode("Documentary S2019E05")) {
            assertEquals(2019, season)
            assertEquals(5, episode)
        }
    }
}
