package tv.own.owntv.mobile.ui.screens.home

import android.content.Context

/**
 * ZenPlay: keeps the Tendencias selection steady for 24 hours, so the carousel does not reshuffle
 * every time Home is opened. A new day, a new year, another profile or plan, or "Actualizar" picks it
 * again from the newest titles.
 */
internal class ZenPlayFeaturedCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved keys ("m:12", "s:34") for [scope] while younger than a day; null otherwise. */
    fun saved(scope: String): List<String>? {
        if (prefs.getString(KEY_SCOPE, null) != scope) return null
        val age = System.currentTimeMillis() - prefs.getLong(KEY_AT, 0L)
        if (age !in 0 until TTL_MS) return null
        return prefs.getString(KEY_ITEMS, null)?.split(',')?.filter { it.isNotBlank() }
    }

    fun save(scope: String, items: List<FeaturedItem>) {
        prefs.edit()
            .putString(KEY_SCOPE, scope)
            .putLong(KEY_AT, System.currentTimeMillis())
            .putString(KEY_ITEMS, items.joinToString(",") { it.key })
            .apply()
    }

    companion object {
        private const val PREFS = "zenplay_featured"
        private const val KEY_SCOPE = "scope"
        private const val KEY_AT = "saved_at"
        private const val KEY_ITEMS = "items"
        private const val TTL_MS = 24L * 60 * 60 * 1000

        /** "Actualizar": choose Tendencias again on the next Home load. */
        fun invalidate(context: Context) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}

internal val FeaturedItem.key: String get() = (if (isSeries) "s:" else "m:") + id

/** Alternate films and shows, so both kinds share the carousel. */
internal fun interleaveFeatured(movies: List<FeaturedItem>, series: List<FeaturedItem>): List<FeaturedItem> = buildList {
    for (i in 0 until maxOf(movies.size, series.size)) {
        movies.getOrNull(i)?.let(::add)
        series.getOrNull(i)?.let(::add)
    }
}
