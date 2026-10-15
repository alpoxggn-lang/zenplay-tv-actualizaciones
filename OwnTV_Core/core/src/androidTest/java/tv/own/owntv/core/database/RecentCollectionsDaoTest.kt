package tv.own.owntv.core.database

import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType

@RunWith(AndroidJUnit4::class)
class RecentCollectionsDaoTest {
    private lateinit var db: OwnTVDatabase

    @Before
    fun setUp() = runBlocking {
        db = ownTVTestDatabase()
        db.sourceDao().insert(
            SourceEntity(
                id = SOURCE_ID,
                name = "Xtream test",
                type = SourceType.XTREAM,
                url = "https://provider.test",
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun moviesArePagedNewestFirstWithoutCollapsingProviderVariants() = runBlocking {
        val now = System.currentTimeMillis()
        db.movieDao().insertAllNormalized(
            listOf(
                movie(1, "Example 4K", "example", 2024, now - 2_000, quality = 4),
                movie(2, "Example HD", "example", 2024, now - 2_000, quality = 2),
                movie(3, "Newest", "newest", 2025, now - 1_000, quality = 1),
                // The collection faithfully follows the provider timestamp; sync validation can
                // normalize malformed dates separately without making every page scan the catalog.
                movie(4, "Broken future", "brokenfuture", 2026, now + YEAR_MS, quality = 5, sort = 999),
            ),
        )

        val page = db.movieDao().pagingAllDateAdded(listOf(SOURCE_ID)).firstPage()

        assertEquals(
            listOf("Broken future", "Newest", "Example HD", "Example 4K"),
            page.map { it.name },
        )
        assertEquals(4, db.movieDao().countAll(listOf(SOURCE_ID)).first())
    }

    @Test
    fun seriesUsesLatestUpdateBeforeQualityAndKeepsFallbackStable() = runBlocking {
        val now = System.currentTimeMillis()
        db.seriesDao().insertSeriesNormalized(
            listOf(
                series(10, "Show 4K old", "show", 2024, now - 20_000, quality = 4),
                // Mirrors Xtream's last_modified mapping: the updated variant must represent the show.
                series(11, "Show HD updated", "show", 2024, now - 1_000, quality = 2),
                series(12, "Other show", "other", 2025, now - 5_000, quality = 3),
                series(13, "Unknown older", "unknownolder", 2023, null, quality = 1, sort = 3),
                series(14, "Unknown newer", "unknownnewer", 2023, null, quality = 1, sort = 8),
            ),
        )

        val page = db.seriesDao().pagingAllDateAdded(listOf(SOURCE_ID)).firstPage()

        assertEquals(
            listOf("Show HD updated", "Other show", "Show 4K old", "Unknown newer", "Unknown older"),
            page.map { it.name },
        )
        assertEquals(5, db.seriesDao().countAll(listOf(SOURCE_ID)).first())
    }

    @Test
    fun hiddenMovieAndSeriesVariantsAreExcludedInsideThePagedQuery() = runBlocking {
        val now = System.currentTimeMillis()
        db.categoryDao().upsertAll(
            listOf(
                CategoryEntity(
                    id = HIDDEN_MOVIE_CATEGORY_ID,
                    sourceId = SOURCE_ID,
                    mediaType = MediaType.MOVIE,
                    name = "Adult movies",
                ),
                CategoryEntity(
                    id = HIDDEN_SERIES_CATEGORY_ID,
                    sourceId = SOURCE_ID,
                    mediaType = MediaType.SERIES,
                    name = "Adult series",
                ),
            ),
        )
        db.movieDao().insertAllNormalized(
            listOf(
                movie(20, "Visible film", "sharedfilm", 2025, now - 2_000, quality = 2),
                movie(
                    21,
                    "Hidden film 4K",
                    "sharedfilm",
                    2025,
                    now - 1_000,
                    quality = 4,
                    categoryId = HIDDEN_MOVIE_CATEGORY_ID,
                ),
            ),
        )
        db.seriesDao().insertSeriesNormalized(
            listOf(
                series(30, "Visible show", "sharedshow", 2025, now - 2_000, quality = 2),
                series(
                    31,
                    "Hidden show 4K",
                    "sharedshow",
                    2025,
                    now - 1_000,
                    quality = 4,
                    categoryId = HIDDEN_SERIES_CATEGORY_ID,
                ),
            ),
        )

        val movies = db.movieDao().pagingAllDateAddedExcluding(
            listOf(SOURCE_ID),
            listOf(HIDDEN_MOVIE_CATEGORY_ID),
        ).firstPage()
        val searchedMovies = db.movieDao().searchAllDateAddedExcluding(
            "film",
            listOf(SOURCE_ID),
            listOf(HIDDEN_MOVIE_CATEGORY_ID),
        ).firstPage()
        val series = db.seriesDao().pagingAllDateAddedExcluding(
            listOf(SOURCE_ID),
            listOf(HIDDEN_SERIES_CATEGORY_ID),
        ).firstPage()
        val searchedSeries = db.seriesDao().searchAllDateAddedExcluding(
            "show",
            listOf(SOURCE_ID),
            listOf(HIDDEN_SERIES_CATEGORY_ID),
        ).firstPage()

        assertEquals(listOf("Visible film"), movies.map { it.name })
        assertEquals(listOf("Visible film"), searchedMovies.map { it.name })
        assertEquals(
            movies.size,
            db.movieDao().countAllExcluding(
                listOf(SOURCE_ID),
                listOf(HIDDEN_MOVIE_CATEGORY_ID),
            ).first(),
        )
        assertEquals(listOf("Visible show"), series.map { it.name })
        assertEquals(listOf("Visible show"), searchedSeries.map { it.name })
        assertEquals(
            series.size,
            db.seriesDao().countAllExcluding(
                listOf(SOURCE_ID),
                listOf(HIDDEN_SERIES_CATEGORY_ID),
            ).first(),
        )
    }

    private fun movie(
        id: Long,
        name: String,
        signature: String,
        year: Int,
        addedAt: Long?,
        quality: Int,
        sort: Int = 0,
        categoryId: Long? = null,
    ) = MovieEntity(
        id = id,
        sourceId = SOURCE_ID,
        categoryId = categoryId,
        name = name,
        streamUrl = "https://provider.test/movie/$id",
        remoteId = id.toString(),
        year = year,
        parsedYear = year,
        canonicalTitle = signature,
        titleSignature = signature,
        qualityRank = quality,
        addedAt = addedAt,
        sortOrder = sort,
    )

    private fun series(
        id: Long,
        name: String,
        signature: String,
        year: Int,
        addedAt: Long?,
        quality: Int,
        sort: Int = 0,
        categoryId: Long? = null,
    ) = SeriesEntity(
        id = id,
        sourceId = SOURCE_ID,
        categoryId = categoryId,
        name = name,
        remoteId = id.toString(),
        year = year,
        parsedYear = year,
        canonicalTitle = signature,
        titleSignature = signature,
        qualityRank = quality,
        addedAt = addedAt,
        sortOrder = sort,
    )

    private suspend fun <T : Any> PagingSource<Int, T>.firstPage(): List<T> =
        when (
            val result = load(
                PagingSource.LoadParams.Refresh(
                    key = null,
                    loadSize = 50,
                    placeholdersEnabled = false,
                ),
            )
        ) {
            is PagingSource.LoadResult.Page -> result.data
            is PagingSource.LoadResult.Error -> throw result.throwable
            is PagingSource.LoadResult.Invalid -> error("Paging source invalidated before its first load")
        }

    private companion object {
        const val SOURCE_ID = 1L
        const val HIDDEN_MOVIE_CATEGORY_ID = 101L
        const val HIDDEN_SERIES_CATEGORY_ID = 102L
        const val YEAR_MS = 365L * 24 * 60 * 60 * 1_000
    }
}
