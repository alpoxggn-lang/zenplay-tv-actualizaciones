package tv.own.owntv.core.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.MediaType

class SearchScopeTest {
    @Test
    fun allIncludesEverySearchableCatalogueSection() {
        assertTrue(SearchScope.ALL.includes(MediaType.LIVE))
        assertTrue(SearchScope.ALL.includes(MediaType.MOVIE))
        assertTrue(SearchScope.ALL.includes(MediaType.SERIES))
        assertFalse(SearchScope.ALL.includes(MediaType.EPISODE))
    }

    @Test
    fun eachSpecificScopeIncludesOnlyItsOwnSection() {
        assertTrue(SearchScope.LIVE.includes(MediaType.LIVE))
        assertFalse(SearchScope.LIVE.includes(MediaType.MOVIE))
        assertFalse(SearchScope.LIVE.includes(MediaType.SERIES))

        assertFalse(SearchScope.MOVIES.includes(MediaType.LIVE))
        assertTrue(SearchScope.MOVIES.includes(MediaType.MOVIE))
        assertFalse(SearchScope.MOVIES.includes(MediaType.SERIES))

        assertFalse(SearchScope.SERIES.includes(MediaType.LIVE))
        assertFalse(SearchScope.SERIES.includes(MediaType.MOVIE))
        assertTrue(SearchScope.SERIES.includes(MediaType.SERIES))
    }

    @Test
    fun typedSearchRequiresTwoTrimmedCharacters() {
        assertFalse(searchQueryCanRun(""))
        assertFalse(searchQueryCanRun(" a "))
        assertTrue(searchQueryCanRun(" ab "))
    }
}
