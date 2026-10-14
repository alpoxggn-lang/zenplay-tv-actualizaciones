package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrendingHeroPolicyTest {
    @Test
    fun `locally selected catalogue titles do not claim a real trend rank`() {
        assertNull(displayableTrendingRank(rank = 1, generationId = ZenPlayHomeRows.GENERATION))
    }

    @Test
    fun `real positive ranks remain visible`() {
        assertEquals(7, displayableTrendingRank(rank = 7, generationId = "tmdb-2026-10-14"))
        assertNull(displayableTrendingRank(rank = 0, generationId = "tmdb-2026-10-14"))
    }

    @Test
    fun `hero height is bounded on compact and large televisions`() {
        assertEquals(320, tvTrendingHeroHeightDp(screenHeightDp = 480))
        assertEquals(417, tvTrendingHeroHeightDp(screenHeightDp = 720))
        assertEquals(440, tvTrendingHeroHeightDp(screenHeightDp = 1080))
    }
}
