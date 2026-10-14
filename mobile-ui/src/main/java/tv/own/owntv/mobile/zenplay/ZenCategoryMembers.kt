package tv.own.owntv.mobile.zenplay

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.settings.SettingsRepository
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * ZenPlay: the full contents of a provider folder.
 *
 * Xtream panels put one film or show in several folders at once ("Series Anime" *and* "Series
 * Crunchyroll"; "Acción" *and* "UCM Marvel"), but the catalogue stores each title once, under its
 * main folder — so the other folders looked almost empty. This asks the panel for the folder's own
 * list (`get_vod_streams` / `get_series` with `category_id`) and shows every title in it that is in
 * the catalogue. Cached for a few hours; null when the folder is not from an Xtream panel or the
 * panel cannot be reached, so the caller falls back to the stored folder.
 */
class ZenCategoryMembers(
    private val categoryDao: CategoryDao,
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val http: HttpClient,
) {
    private class Entry(val at: Long, val ids: List<String>)
    private val cache = ConcurrentHashMap<String, Entry>()

    private suspend fun remoteIds(type: MediaType, categoryId: Long): Pair<Long, List<String>>? = withContext(Dispatchers.IO) {
        runCatching {
            val cat = categoryDao.getById(categoryId) ?: return@runCatching null
            val remote = cat.remoteId?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val key = "${type.name}|$categoryId"
            cache[key]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return@runCatching cat.sourceId to it.ids }
            val src = sourceDao.getById(cat.sourceId) ?: return@runCatching null
            if (src.type != SourceType.XTREAM) return@runCatching null
            val action = if (type == MediaType.MOVIE) "get_vod_streams" else "get_series"
            val idField = if (type == MediaType.MOVIE) "stream_id" else "series_id"
            fun enc(v: String?) = URLEncoder.encode(v.orEmpty(), "UTF-8")
            val url = src.url.trimEnd('/') + "/player_api.php?username=${enc(src.username)}" +
                "&password=${enc(src.password)}&action=$action&category_id=${enc(remote)}"
            val arr = JSONArray(http.getText(url, src.userAgent))
            val ids = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.opt(idField)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            }.distinct()
            cache[key] = Entry(System.currentTimeMillis(), ids)
            cat.sourceId to ids
        }.getOrNull()
    }

    /** Every film in the folder, in the panel's order; null to fall back to the stored folder. */
    suspend fun movies(categoryId: Long): List<MovieEntity>? {
        val (sourceId, ids) = remoteIds(MediaType.MOVIE, categoryId) ?: return null
        if (ids.isEmpty()) return null
        val rows = withContext(Dispatchers.IO) { ids.chunked(CHUNK).flatMap { movieDao.findByRemoteIds(sourceId, it) } }
        val order = ids.withIndex().associate { it.value to it.index }
        return rows.distinctBy { it.id }.sortedBy { order[it.remoteId] ?: Int.MAX_VALUE }
    }

    /** Every show in the folder, in the panel's order; null to fall back to the stored folder. */
    suspend fun series(categoryId: Long): List<SeriesEntity>? {
        val (sourceId, ids) = remoteIds(MediaType.SERIES, categoryId) ?: return null
        if (ids.isEmpty()) return null
        val rows = withContext(Dispatchers.IO) { ids.chunked(CHUNK).flatMap { seriesDao.findSeriesByRemoteIds(sourceId, it) } }
        val order = ids.withIndex().associate { it.value to it.index }
        return rows.distinctBy { it.id }.sortedBy { order[it.remoteId] ?: Int.MAX_VALUE }
    }

    companion object {
        private const val TTL_MS = 6 * 3_600_000L
        private const val CHUNK = 500

        val shared: ZenCategoryMembers by lazy {
            val k = org.koin.core.context.GlobalContext.get()
            ZenCategoryMembers(k.get(), k.get(), k.get(), k.get(), k.get())
        }

        /** The user's sort applied to a folder list (the panel's own order for "playlist"). */
        fun <T> sorted(
            list: List<T>,
            sort: SettingsRepository.SortMode,
            alphaByDefault: Boolean,
            name: (T) -> String,
            rating: (T) -> Double?,
            added: (T) -> Long?,
        ): List<T> = when (sort) {
            SettingsRepository.SortMode.RATING -> list.sortedByDescending { rating(it) ?: 0.0 }
            SettingsRepository.SortMode.DATE_ADDED -> list.sortedByDescending { added(it) ?: 0L }
            SettingsRepository.SortMode.ALPHA -> list.sortedBy { name(it).lowercase() }
            SettingsRepository.SortMode.PLAYLIST -> list
            else -> if (alphaByDefault) list.sortedBy { name(it).lowercase() } else list
        }
    }
}

/** A folder list held in memory, paged in slices — for [ZenCategoryMembers]. */
class ZenListPagingSource<T : Any>(private val loader: suspend () -> List<T>) : PagingSource<Int, T>() {
    private var all: List<T>? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = try {
        val list = all ?: loader().also { all = it }
        val offset = (params.key ?: 0).coerceIn(0, list.size)
        val end = (offset + params.loadSize).coerceAtMost(list.size)
        LoadResult.Page(
            data = list.subList(offset, end),
            prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
            nextKey = if (end >= list.size) null else end,
        )
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<Int, T>): Int? = null
}
