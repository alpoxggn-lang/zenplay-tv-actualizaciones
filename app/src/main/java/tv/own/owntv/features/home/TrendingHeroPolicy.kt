package tv.own.owntv.features.home

/** Keeps the wide-screen hero compact enough for short Fire TV / Android TV viewports. */
internal fun tvTrendingHeroHeightDp(screenHeightDp: Int): Int =
    (screenHeightDp * 0.58f).toInt().coerceIn(320, 440)

/**
 * A ZenPlay catalogue selection has an ordering position, not an externally verified trend rank.
 * Only snapshots produced by the real trending pipeline may advertise a numbered ranking.
 */
internal fun displayableTrendingRank(rank: Int, generationId: String): Int? =
    rank.takeIf { it > 0 && generationId != ZenPlayHomeRows.GENERATION }
