package tv.own.owntv.core.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendingInteractionPolicyTest {
    @Test
    fun `auto advance pauses for active touch or remote focus`() {
        assertFalse(shouldAutoAdvanceTrending(itemCount = 6, userInteracting = true))
        assertTrue(shouldAutoAdvanceTrending(itemCount = 6, userInteracting = false))
    }

    @Test
    fun `single item never schedules carousel movement`() {
        assertFalse(shouldAutoAdvanceTrending(itemCount = 1, userInteracting = false))
        assertFalse(shouldAutoAdvanceTrending(itemCount = 0, userInteracting = false))
    }

    @Test
    fun `swipe and dpad navigation wraps in both directions`() {
        assertEquals(0, wrappedTrendingIndex(currentIndex = 5, delta = 1, itemCount = 6))
        assertEquals(5, wrappedTrendingIndex(currentIndex = 0, delta = -1, itemCount = 6))
        assertEquals(0, wrappedTrendingIndex(currentIndex = 0, delta = 1, itemCount = 0))
    }
}

