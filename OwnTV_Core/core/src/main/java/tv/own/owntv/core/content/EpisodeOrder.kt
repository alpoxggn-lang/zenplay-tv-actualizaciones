package tv.own.owntv.core.content

import tv.own.owntv.core.database.entity.EpisodeEntity

/** Canonical watch order: regular seasons first and Specials (season 0) after the finale. */
object EpisodeOrder {
    private fun rank(season: Int): Int = if (season == 0) Int.MAX_VALUE else season

    val episodes: Comparator<EpisodeEntity> =
        compareBy<EpisodeEntity> { rank(it.seasonNumber) }.thenBy { it.episodeNumber }

    fun openingSeason(seasons: Iterable<Int>): Int? = seasons.minByOrNull(::rank)

    fun seasonAfter(seasons: Iterable<Int>, season: Int): Int? =
        seasons.filter { rank(it) > rank(season) }.minByOrNull(::rank)
}
