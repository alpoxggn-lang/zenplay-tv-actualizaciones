package tv.own.owntv.mobile.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.live.LiveKey

class CatalogSearchKeyTest {
    @Test
    fun blankQueryKeepsSelectedCategory() {
        val selected = LiveKey.Folder(42L)
        assertEquals(selected, catalogSearchKey(selected, "  "))
    }

    @Test
    fun typedQuerySearchesWholeSection() {
        assertEquals(LiveKey.All, catalogSearchKey(LiveKey.Folder(42L), "matrix"))
        assertEquals(LiveKey.All, catalogSearchKey(LiveKey.Favorites, "matrix"))
    }

    @Test
    fun oneCharacterDoesNotStartCatalogueQuery() {
        val selected = LiveKey.Folder(42L)
        assertEquals(false, catalogQueryCanRun("m"))
        assertEquals(selected, catalogSearchKey(selected, "m"))
    }

    @Test
    fun twoTrimmedCharactersStartCatalogueQuery() {
        assertEquals(true, catalogQueryCanRun("  ma  "))
        assertEquals(LiveKey.All, catalogSearchKey(LiveKey.Folder(42L), "  ma  "))
    }
}
