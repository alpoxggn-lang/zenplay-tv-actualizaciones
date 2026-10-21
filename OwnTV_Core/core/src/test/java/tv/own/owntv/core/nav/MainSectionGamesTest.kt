package tv.own.owntv.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSectionGamesTest {
    @Test
    fun `games is a browse destination independent of provider content`() {
        assertTrue(MainSection.GAMES in MainSection.browseOrder)
        assertTrue(MainSection.GAMES in MainSection.allBrowse)
        assertEquals(
            setOf(MainSection.HOME, MainSection.GAMES),
            MainSection.dynamicVisible(hasLive = false, hasMovies = false, hasSeries = false),
        )
    }
}
