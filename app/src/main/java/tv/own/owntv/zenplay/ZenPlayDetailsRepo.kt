package tv.own.owntv.zenplay

import androidx.compose.runtime.Immutable
import androidx.paging.PagingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import java.net.URLEncoder

/** What the provider's panel knows about a film or a show. Every field is optional. */
@Immutable
data class ZenPlayProviderInfo(
    val plot: String? = null,
    val cast: List<String> = emptyList(),
    val director: String? = null,
    val genres: List<String> = emptyList(),
    val year: String? = null,
    val rating: Double? = null,
    val durationText: String? = null,
    val age: String? = null,
    val backdrop: String? = null,
    val poster: String? = null,
    /** Episode image and length by the episode's provider id (`remoteId`). */
    val episodeImages: Map<String, String> = emptyMap(),
    val episodeDurations: Map<String, String> = emptyMap(),
)

/** A suggestion under "Te puede gustar". */
@Immutable
data class ZenPlayRecommendation(
    val id: Long,
    val isSeries: Boolean,
    val name: String,
    val posterUrl: String?,
    val rating: Double?,
)

/**
 * ZenPlay's film/show page data: the provider's own description (`get_vod_info` /
 * `get_series_info`) and five similar titles from the customer's own catalogue — the same category,
 * best rated first — so every suggestion can actually be played.
 */
class ZenPlayDetailsRepo(
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val http: HttpClient,
) {
    suspend fun movieInfo(movie: MovieEntity): ZenPlayProviderInfo? =
        providerInfo(movie.sourceId, movie.remoteId, "get_vod_info", "vod_id")

    suspend fun seriesInfo(show: SeriesEntity): ZenPlayProviderInfo? =
        providerInfo(show.sourceId, show.remoteId, "get_series_info", "series_id")

    private suspend fun providerInfo(sourceId: Long, remoteId: String?, action: String, idParam: String): ZenPlayProviderInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = remoteId?.takeIf { it.isNotBlank() } ?: return@runCatching null
                val source = sourceDao.getById(sourceId) ?: return@runCatching null
                if (source.type != SourceType.XTREAM) return@runCatching null
                val root = JSONObject(http.getText(apiUrl(source, action, idParam, id), source.userAgent))
                parse(root)
            }.getOrNull()
        }

    private fun apiUrl(source: SourceEntity, action: String, idParam: String, id: String): String {
        fun enc(v: String?) = URLEncoder.encode(v.orEmpty(), "UTF-8")
        return source.url.trimEnd('/') + "/player_api.php?username=${enc(source.username)}" +
            "&password=${enc(source.password)}&action=$action&$idParam=${enc(id)}"
    }

    private fun parse(root: JSONObject): ZenPlayProviderInfo? {
        val info = root.optJSONObject("info") ?: return null
        fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            info.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" }
        }
        fun names(raw: String?): List<String> =
            raw?.split(',', '/', '|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val backdrop = info.optJSONArray("backdrop_path")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.firstOrNull { it.startsWith("http") }
        } ?: str("backdrop_path")?.takeIf { it.startsWith("http") }
        val images = HashMap<String, String>()
        val durations = HashMap<String, String>()
        root.optJSONObject("episodes")?.let { seasons ->
            seasons.keys().forEach { key ->
                val list: JSONArray = seasons.optJSONArray(key) ?: return@forEach
                for (i in 0 until list.length()) {
                    val ep = list.optJSONObject(i) ?: continue
                    val epId = ep.optString("id", "").trim().takeIf { it.isNotEmpty() } ?: continue
                    val epInfo = ep.optJSONObject("info")
                    epInfo?.optString("movie_image", "")?.trim()?.takeIf { it.startsWith("http") }?.let { images[epId] = it }
                    epInfo?.optString("duration", "")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { durations[epId] = it }
                }
            }
        }
        return ZenPlayProviderInfo(
            plot = str("plot", "description"),
            cast = names(str("cast", "actors")).take(12),
            director = str("director"),
            genres = names(str("genre")),
            year = str("releasedate", "releaseDate", "release_date", "year")?.take(4),
            rating = str("rating")?.toDoubleOrNull()?.takeIf { it > 0 },
            durationText = str("duration"),
            age = str("age", "mpaa_rating", "certification"),
            backdrop = backdrop,
            poster = str("movie_image", "cover_big", "cover")?.takeIf { it.startsWith("http") },
            episodeImages = images,
            episodeDurations = durations,
        )
    }

    suspend fun recommendations(movie: MovieEntity): List<ZenPlayRecommendation> = withContext(Dispatchers.IO) {
        val category = movie.categoryId ?: return@withContext emptyList()
        val candidates = readPage { movieDao.pagingByCategoryDateAdded(category) }
        pick(candidates.filter { it.id != movie.id }, movie.name, movie.year ?: movie.parsedYear, { it.name }, { it.year ?: it.parsedYear }, { it.rating })
            .map { ZenPlayRecommendation(it.id, false, it.name, it.posterUrl, it.rating) }
    }

    suspend fun recommendations(show: SeriesEntity): List<ZenPlayRecommendation> = withContext(Dispatchers.IO) {
        val category = show.categoryId ?: return@withContext emptyList()
        val candidates = readPage { seriesDao.pagingByCategoryDateAdded(category) }
        pick(candidates.filter { it.id != show.id }, show.name, show.year ?: show.parsedYear, { it.name }, { it.year ?: it.parsedYear }, { it.rating })
            .map { ZenPlayRecommendation(it.id, true, it.name, it.posterUrl, it.rating) }
    }

    /** Best rated first, then closest in year; one entry per title and never the title itself. */
    private fun <T> pick(
        items: List<T>,
        ownName: String,
        ownYear: Int?,
        name: (T) -> String,
        year: (T) -> Int?,
        rating: (T) -> Double?,
    ): List<T> {
        val own = titleKey(ownName)
        val seen = HashSet<String>().apply { add(own) }
        return items
            .sortedWith(
                compareByDescending<T> { rating(it) ?: 0.0 }
                    .thenBy { if (ownYear != null && year(it) != null) kotlin.math.abs(ownYear - year(it)!!) else 99 },
            )
            .filter { seen.add(titleKey(name(it))) }
            .take(LIMIT)
    }

    private fun titleKey(name: String): String =
        name.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private suspend fun <T : Any> readPage(factory: () -> PagingSource<Int, T>): List<T> {
        val source = factory()
        return try {
            when (val page = source.load(PagingSource.LoadParams.Refresh(null, SCAN, false))) {
                is PagingSource.LoadResult.Page -> page.data
                else -> emptyList()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            emptyList()
        } finally {
            source.invalidate()
        }
    }

    companion object {
        private const val LIMIT = 5
        private const val SCAN = 60

        /** The app-wide instance, built from Koin's singletons. */
        val shared: ZenPlayDetailsRepo by lazy {
            val k = org.koin.core.context.GlobalContext.get()
            ZenPlayDetailsRepo(k.get(), k.get(), k.get(), k.get())
        }
    }
}
