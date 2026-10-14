package tv.own.owntv.features.shell

import tv.own.owntv.core.nav.MainSection

/** Browse destinations that can actually be rendered by the TV/Fire shell. */
internal fun tvRailBrowseSections(visibleSections: Set<MainSection>): List<MainSection> =
    MainSection.browseOrder.filter {
        it in visibleSections && it != MainSection.EPG && it != MainSection.DOWNLOADS
    }

/** Sections offered by the TV/Fire navigation customiser. */
internal fun tvCustomizableBrowseSections(): List<MainSection> =
    MainSection.browseOrder.filterNot { it == MainSection.DOWNLOADS }

