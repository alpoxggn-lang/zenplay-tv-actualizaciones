package tv.own.owntv.mobile.ui.nav

import tv.own.owntv.mobile.ui.components.MobileIcons
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.mobile.R

/**
 * Where the phone can navigate to.
 *
 * [sections] is what ties a destination to core's visibility rule: a destination shows only when at
 * least one of its sections is in `NavVisibility.visibleSections()`. Movies and Series are separate
 * destinations on phones and tablets. Library remains only to restore older navigation state.
 *
 * MORE is the fifth primary destination on phones and tablets.
 */
enum class MobileDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val sections: Set<MainSection>,
) {
    HOME("home", R.string.common_nav_home, MobileIcons.Home, setOf(MainSection.HOME)),
    LIVE("live", R.string.zenplay_nav_live, MobileIcons.LiveTv, setOf(MainSection.LIVE_TV)),
    LIBRARY(
        "library",
        R.string.common_nav_library,
        MobileIcons.VideoLibrary,
        setOf(MainSection.MOVIES, MainSection.SERIES),
    ),
    // ZenPlay: the Guide slot is the sports agenda now ("Agenda deportiva de hoy").
    GUIDE("guide", R.string.zenplay_agenda_short, MobileIcons.CalendarMonth, setOf(MainSection.LIVE_TV)),
    MORE("more", R.string.common_nav_more, MobileIcons.MoreHoriz, emptySet()),

    // Movies and Series each have their own content tab. Downloads stays inside the gear menu.
    MOVIES("movies", R.string.common_nav_movies, MobileIcons.Movie, setOf(MainSection.MOVIES)),
    SERIES("series", R.string.common_nav_series, MobileIcons.Theaters, setOf(MainSection.SERIES)),
    DOWNLOADS(
        "downloads",
        R.string.zenplay_nav_downloads,
        MobileIcons.Download,
        setOf(MainSection.DOWNLOADS),
    ),
    SETTINGS("settings", R.string.common_nav_settings, MobileIcons.Settings, emptySet()),

    /** ZenPlay: everything marked as favourite (channels, films, shows), as a tab of its own. */
    FAVORITES("zen_favorites", R.string.zenplay_nav_favorites, MobileIcons.FavoriteBorder, emptySet());

    companion object {
        /** The five primary ZenPlay destinations. Guide opens from Live TV. */
        val bottomBar: List<MobileDestination> = listOf(HOME, LIVE, MOVIES, SERIES, MORE)

        /**
         * Tablets use the same content destinations as phones, and the same gear menu.
         */
        val rail: List<MobileDestination> =
            bottomBar

        /**
         * Filters a bar or rail to what the active playlist actually offers. A destination with no
         * sections is always kept; the rest survive if core says any of their sections is visible.
         */
        fun List<MobileDestination>.visible(sections: Set<MainSection>): List<MobileDestination> =
            filter { it.sections.isEmpty() || it.sections.any { section -> section in sections } }
    }
}
