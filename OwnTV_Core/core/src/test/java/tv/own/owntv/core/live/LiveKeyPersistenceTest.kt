package tv.own.owntv.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LiveKeyPersistenceTest {
    @Test
    fun recentlyAddedRoundTripsWithoutCollidingWithWatchHistory() {
        val stored = LiveKey.RecentlyAdded.serialize()

        assertEquals("RECENTLY_ADDED", stored)
        assertEquals(LiveKey.RecentlyAdded, parseLiveKey(stored))
        assertNotEquals(LiveKey.History.serialize(), stored)
    }
}
