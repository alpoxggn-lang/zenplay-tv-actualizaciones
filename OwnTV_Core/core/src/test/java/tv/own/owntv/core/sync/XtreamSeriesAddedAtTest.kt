package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamSeriesAddedAtTest {
    @Test
    fun zeroLastModifiedFallsBackToAdded() {
        assertEquals(
            1_700_000_000_000L,
            XtreamSyncer.preferredSeriesAddedAt(lastModified = 0L, added = 1_700_000_000L),
        )
    }

    @Test
    fun validLastModifiedWinsAndMillisecondsArePreserved() {
        assertEquals(
            1_710_000_000_000L,
            XtreamSyncer.preferredSeriesAddedAt(
                lastModified = 1_710_000_000_000L,
                added = 1_700_000_000L,
            ),
        )
    }

    @Test
    fun nonPositiveDatesStayUnknown() {
        assertNull(XtreamSyncer.preferredSeriesAddedAt(lastModified = 0L, added = -1L))
    }
}
