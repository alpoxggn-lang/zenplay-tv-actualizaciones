package tv.own.owntv.features.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.content.SearchIntent

class SearchUiStateTest {
    @Test
    fun emptyCuratedIntentRestoresFocusToSearchField() {
        assertTrue(shouldRestoreSearchFocus(SearchIntent.CHANNELS, resultsEmpty = true))
    }

    @Test
    fun launcherAndNonEmptyIntentDoNotStealResultFocus() {
        assertFalse(shouldRestoreSearchFocus(null, resultsEmpty = true))
        assertFalse(shouldRestoreSearchFocus(SearchIntent.CONTINUE, resultsEmpty = false))
    }
}
