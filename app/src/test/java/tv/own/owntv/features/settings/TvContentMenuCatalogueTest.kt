package tv.own.owntv.features.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.menu.catalogue
import tv.own.owntv.core.model.ContentMenu

class TvContentMenuCatalogueTest {
    @Test
    fun `television menus never advertise offline content downloads`() {
        listOf(ContentMenu.MOVIE, ContentMenu.SERIES, ContentMenu.EPISODE).forEach { menu ->
            assertFalse(tvContentMenuCatalogue(menu).any { it.key == "download" })
        }
    }

    @Test
    fun `shared touch catalogue keeps offline content downloads`() {
        listOf(ContentMenu.MOVIE, ContentMenu.SERIES, ContentMenu.EPISODE).forEach { menu ->
            assertTrue(catalogue(menu).any { it.key == "download" })
        }
    }

    @Test
    fun `television live menu keeps recording`() {
        val keys = tvContentMenuCatalogue(ContentMenu.LIVE).map { it.key }
        assertTrue("record" in keys)
        assertFalse("download" in keys)
    }
}
