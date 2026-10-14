package tv.own.owntv.core.model

import androidx.compose.runtime.Immutable

import org.json.JSONArray
import org.json.JSONObject

enum class HomeRow {
    TRENDING,
    HERO,
    RECENT_MOVIES,
    RECENT_SERIES,
    RECENT_CHANNELS,
    FAVORITE_CHANNELS,
    CONTINUE_MOVIES,
    CONTINUE_SERIES;

    /**
     * The two media-specific Continue rows are kept only so old settings/backups still parse. The
     * mixed HERO row is ZenPlay's single Continue watching section; exposing these again would let
     * a restored or edited config duplicate the same progress on Home.
     */
    val implemented: Boolean
        get() = this != CONTINUE_MOVIES && this != CONTINUE_SERIES
}

enum class HomeLiveRowMode {
    CARDS,
    ON_NOW;

    fun toggled(): HomeLiveRowMode = when (this) {
        CARDS -> ON_NOW
        ON_NOW -> CARDS
    }
}

/**
 * How the Trending row is drawn: the full card with the artwork, badges and reasons, or the plain
 * strip of posters. [HERO] is the default, so nothing changes for anyone who never opens the setting.
 */
enum class HomeTrendingStyle {
    HERO,
    POSTERS,
}

enum class HeroKind {
    LIVE, MOVIES, SERIES,
}

@Immutable
data class HomeConfig(
    val order: List<HomeRow> = HomeRow.entries.toList(),
    // HERO is the single mixed "Continuar viendo" row. The two media-specific legacy rows remain
    // in persisted data only; `implemented` keeps them out of both Home and its settings.
    val hidden: Set<HomeRow> = setOf(HomeRow.CONTINUE_MOVIES, HomeRow.CONTINUE_SERIES),
    val heroIncludeLive: Boolean = true,
    val heroIncludeMovies: Boolean = true,
    val heroIncludeSeries: Boolean = true,
    val recentLiveMode: HomeLiveRowMode = HomeLiveRowMode.CARDS,
    val favoriteLiveMode: HomeLiveRowMode = HomeLiveRowMode.ON_NOW,
    val trendingStyle: HomeTrendingStyle = HomeTrendingStyle.HERO,
) {
    val visibleOrder: List<HomeRow>
        get() = buildList {
            // Trending is a fixed, optional Home feature rather than a reorderable content row.
            // When enabled it always owns the top position, regardless of any previously saved order.
            if (HomeRow.TRENDING !in hidden) add(HomeRow.TRENDING)
            addAll(order.filter { it != HomeRow.TRENDING && it.implemented && it !in hidden })
        }

    val settingsRows: List<HomeRow>
        get() = order.filter { it != HomeRow.TRENDING && it.implemented }

    fun toJson(): JSONObject = JSONObject().apply {
        put("order", JSONArray(order.map { it.name }))
        put("hidden", JSONArray(hidden.map { it.name }))
        put("heroLive", heroIncludeLive)
        put("heroMovies", heroIncludeMovies)
        put("heroSeries", heroIncludeSeries)
        put("recentLiveMode", recentLiveMode.name)
        put("favoriteLiveMode", favoriteLiveMode.name)
        put("trendingStyle", trendingStyle.name)
    }

    companion object {
        fun fromJson(raw: String?): HomeConfig {
            if (raw.isNullOrBlank()) return HomeConfig()
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return HomeConfig()
            val storedOrder = obj.optJSONArray("order").toHomeRows()
            val legacySchema = storedOrder.isNotEmpty() &&
                HomeRow.RECENT_MOVIES !in storedOrder && HomeRow.RECENT_SERIES !in storedOrder
            val storedHidden = if (obj.has("hidden")) obj.optJSONArray("hidden").toHomeRows().toSet()
                else HomeConfig().hidden
            val hidden = if (legacySchema) {
                // Old defaults hid recent channels and showed two extra Continue rows. Migrate to
                // the one mixed Continue section followed by recents and TV, while preserving any
                // other explicit hidden choices.
                (storedHidden - HomeRow.RECENT_CHANNELS) +
                    setOf(HomeRow.CONTINUE_MOVIES, HomeRow.CONTINUE_SERIES)
            } else {
                storedHidden
            }
            return HomeConfig(
                order = mergeOrder(storedOrder),
                hidden = hidden,
                heroIncludeLive = readBool(obj, "heroLive", "heroIncludeLive", default = true),
                heroIncludeMovies = readBool(obj, "heroMovies", "heroIncludeMovies", default = true),
                heroIncludeSeries = readBool(obj, "heroSeries", "heroIncludeSeries", default = true),
                recentLiveMode = readLiveMode(obj, "recentLiveMode", HomeLiveRowMode.CARDS),
                favoriteLiveMode = readLiveMode(obj, "favoriteLiveMode", HomeLiveRowMode.ON_NOW),
                // Absent in every config written before the setting existed, and in every backup
                // taken then — those all read as the hero, which is what they were showing.
                trendingStyle = runCatching { HomeTrendingStyle.valueOf(obj.optString("trendingStyle")) }
                    .getOrDefault(HomeTrendingStyle.HERO),
            )
        }

        private fun readBool(obj: JSONObject, primary: String, fallback: String, default: Boolean): Boolean =
            when {
                obj.has(primary) -> obj.optBoolean(primary, default)
                obj.has(fallback) -> obj.optBoolean(fallback, default)
                else -> default
            }

        private fun readLiveMode(obj: JSONObject, key: String, default: HomeLiveRowMode): HomeLiveRowMode =
            runCatching { HomeLiveRowMode.valueOf(obj.optString(key)) }.getOrDefault(default)
    }
}

fun mergeOrder(stored: List<HomeRow>): List<HomeRow> {
    // Before the ZenPlay editorial rows existed, a persisted default put both live rows before the
    // two continuation rows. Merely inserting the new values by enum index would preserve that old
    // grouping and yield TV → recently added → Continue watching after an app update. Old-schema
    // configurations are migrated once to the new product order; configurations already containing
    // either new row retain the order the user chose in Settings.
    if (stored.isNotEmpty() &&
        HomeRow.RECENT_MOVIES !in stored &&
        HomeRow.RECENT_SERIES !in stored
    ) {
        return HomeRow.entries.toList()
    }
    val result = LinkedHashSet<HomeRow>()
    stored.forEach { result += it }
    HomeRow.entries.forEachIndexed { index, row ->
        if (row !in result) {
            val current = result.toMutableList()
            current.add(index.coerceAtMost(current.size), row)
            result.clear()
            result += current
        }
    }
    return result.toList()
}

private fun JSONArray?.toHomeRows(): List<HomeRow> {
    if (this == null) return emptyList()
    val out = ArrayList<HomeRow>(length())
    for (i in 0 until length()) {
        val row = runCatching { HomeRow.valueOf(optString(i)) }.getOrNull() ?: continue
        if (row !in out) out += row
    }
    return out
}
