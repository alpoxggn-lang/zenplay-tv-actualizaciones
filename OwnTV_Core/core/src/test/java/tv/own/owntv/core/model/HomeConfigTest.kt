package tv.own.owntv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeConfigTest {

    @Test
    fun `default home follows the ZenPlay editorial order`() {
        assertEquals(
            listOf(
                HomeRow.TRENDING,
                HomeRow.HERO,
                HomeRow.RECENT_MOVIES,
                HomeRow.RECENT_SERIES,
                HomeRow.RECENT_CHANNELS,
                HomeRow.FAVORITE_CHANNELS,
            ),
            HomeConfig().visibleOrder,
        )
    }

    @Test
    fun `live television is visible by default`() {
        assertTrue(HomeRow.RECENT_CHANNELS in HomeConfig().visibleOrder)
    }

    @Test
    fun `legacy home order migrates to the ZenPlay editorial order`() {
        val legacy = listOf(
            HomeRow.TRENDING,
            HomeRow.HERO,
            HomeRow.RECENT_CHANNELS,
            HomeRow.FAVORITE_CHANNELS,
            HomeRow.CONTINUE_MOVIES,
            HomeRow.CONTINUE_SERIES,
        )

        assertEquals(HomeConfig().order, mergeOrder(legacy))
    }

    @Test
    fun `legacy home config keeps one Continue section and restores live television`() {
        val legacyJson = """{
            "order":["TRENDING","HERO","RECENT_CHANNELS","FAVORITE_CHANNELS","CONTINUE_MOVIES","CONTINUE_SERIES"],
            "hidden":["RECENT_CHANNELS"]
        }"""

        val migrated = HomeConfig.fromJson(legacyJson)

        assertEquals(HomeConfig().visibleOrder, migrated.visibleOrder)
        assertTrue(HomeRow.CONTINUE_MOVIES in migrated.hidden)
        assertTrue(HomeRow.CONTINUE_SERIES in migrated.hidden)
        assertTrue(HomeRow.RECENT_CHANNELS !in migrated.hidden)
    }

    @Test
    fun `modern config cannot re-enable legacy Continue rows`() {
        val modernJson = """{
            "order":["TRENDING","HERO","CONTINUE_MOVIES","RECENT_MOVIES","CONTINUE_SERIES","RECENT_SERIES","RECENT_CHANNELS","FAVORITE_CHANNELS"],
            "hidden":[]
        }"""

        val config = HomeConfig.fromJson(modernJson)

        assertEquals(
            listOf(
                HomeRow.TRENDING,
                HomeRow.HERO,
                HomeRow.RECENT_MOVIES,
                HomeRow.RECENT_SERIES,
                HomeRow.RECENT_CHANNELS,
                HomeRow.FAVORITE_CHANNELS,
            ),
            config.visibleOrder,
        )
        assertTrue(HomeRow.CONTINUE_MOVIES !in config.settingsRows)
        assertTrue(HomeRow.CONTINUE_SERIES !in config.settingsRows)
    }
}
