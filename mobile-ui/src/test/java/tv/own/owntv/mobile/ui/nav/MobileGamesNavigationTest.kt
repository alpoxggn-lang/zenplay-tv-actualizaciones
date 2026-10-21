package tv.own.owntv.mobile.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.nav.MainSection

class MobileGamesNavigationTest {
    @Test
    fun `games is a primary destination backed by the shared section`() {
        assertTrue(MobileDestination.GAMES in MobileDestination.bottomBar)
        assertTrue(MobileDestination.GAMES in MobileDestination.rail)
        assertEquals(setOf(MainSection.GAMES), MobileDestination.GAMES.sections)
        assertEquals("games", MobileDestination.GAMES.route)
    }
}
