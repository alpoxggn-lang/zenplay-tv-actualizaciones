package tv.own.owntv.features.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.nav.MainSection

class TvSectionPolicyTest {
    @Test
    fun `tv rail excludes downloads even when an old preference marks it visible`() {
        val sections = tvRailBrowseSections(MainSection.allBrowse)

        assertFalse(MainSection.DOWNLOADS in sections)
        assertFalse(MainSection.EPG in sections)
        assertTrue(MainSection.MOVIES in sections)
        assertTrue(MainSection.SERIES in sections)
    }

    @Test
    fun `tv navigation customiser cannot re-enable downloads`() {
        val sections = tvCustomizableBrowseSections()

        assertFalse(MainSection.DOWNLOADS in sections)
        assertTrue(MainSection.EPG in sections)
        assertEquals(sections.distinct(), sections)
    }
}
