package tv.own.owntv.core.recommendation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType

class ContentRecommendationRankerTest {
    @Test
    fun normalizesSpanishEnglishAndAccents() {
        assertEquals(
            setOf(
                SemanticGenre.ACTION,
                SemanticGenre.SCIENCE_FICTION,
                SemanticGenre.THRILLER,
                SemanticGenre.KIDS,
            ),
            ContentRecommendationRanker.normalizeGenres(
                listOf("ACCIÓN", "Science-Fiction / Ciencia ficción", "Suspenso", "Niños"),
            ),
        )
    }

    @Test
    fun semanticCategoryWinsThenCurrentCategoryThenCatalog() {
        val current = item(
            key = "current",
            title = "Main",
            year = 2020,
            categoryId = 10,
            categoryName = "4K estrenos",
            genres = listOf("Drama"),
        )
        val catalog = item("catalog", "Catalog", 2020, 30, "Estrenos", rating = 10.0)
        val categoryFallback = item("same-category", "Same category", 2020, 10, "4K estrenos", rating = 10.0)
        val semantic = item("drama", "Semantic", 2020, 20, "DRAMA | Películas", rating = 1.0)

        val ranked = ContentRecommendationRanker.rank(
            current,
            listOf(catalog, categoryFallback, semantic),
            limit = 3,
        )

        assertEquals(listOf("drama", "same-category", "catalog"), ranked.map { it.stableKey })
    }

    @Test
    fun currentSemanticCategoryWorksWhenExplicitGenresAreMissing() {
        val current = item("current", "Main", 2020, 1, "Animación")
        val animation = item("animation", "Animated", 2019, 2, "Anime")
        val sameCategory = item("fallback", "Fallback", 2019, 1, "Animación")

        val ranked = ContentRecommendationRanker.rank(current, listOf(sameCategory, animation), 2)

        assertEquals(setOf("animation", "fallback"), ranked.mapTo(linkedSetOf()) { it.stableKey })
        // Both are semantic matches, not merely an accent-sensitive category string comparison.
        assertEquals(SemanticGenre.ANIMATION, ContentRecommendationRanker.normalizeGenres(listOf("Animación")).single())
    }

    @Test
    fun excludesOnlyCurrentItemAndKeepsSameTitleProviderVariants() {
        val current = item("movie-current", "(4K) Dune (2021)", 2021, 1, "Sci-Fi", listOf("Science Fiction"))
        val candidates = listOf(
            item("movie-current", "Dune", 2021, 1, "Sci-Fi"),
            item("dune-fhd", "Dune FHD", 2021, 1, "Sci-Fi", qualityRank = 3),
            item("dune-remake", "Dune", 1984, 1, "Sci-Fi"),
            item("matrix-hd", "The Matrix HD", 1999, 1, "Sci-Fi", qualityRank = 2),
            item("matrix-4k", "The Matrix 4K", 1999, 1, "Sci-Fi", qualityRank = 4),
        )

        val ranked = ContentRecommendationRanker.rank(current, candidates, 10)
        val keys = ranked.map { it.stableKey }

        assertFalse("movie-current" in keys)
        assertTrue("dune-fhd" in keys)
        assertTrue("dune-remake" in keys)
        assertTrue("matrix-4k" in keys)
        assertTrue("matrix-hd" in keys)
    }

    @Test
    fun repeatedStableIdentityIsTheOnlyCandidateThatCollapses() {
        val current = item("current", "Main", 2020, 1, "Drama")
        val first = item("provider:42", "Same title", 2020, 1, "Drama")
        val repeated = first.copy(value = "same provider row repeated", qualityRank = 99)
        val separateVariant = item("provider:43", "Same title", 2020, 1, "Drama")

        val ranked = ContentRecommendationRanker.rank(
            current,
            listOf(first, repeated, separateVariant),
            limit = 10,
        )

        assertEquals(listOf("provider:42", "provider:43"), ranked.map { it.stableKey }.sorted())
    }

    @Test
    fun sameSubjectIsStableAndDifferentSubjectsVary() {
        val candidates = (1..18).map { index ->
            item(
                key = "candidate-$index",
                title = "Candidate $index",
                year = 2020,
                categoryId = 7,
                categoryName = "4K",
                rating = 8.0,
            )
        }
        val first = item("subject-alpha", "Alpha", 2020, 7, "4K")
        val second = item("subject-omega", "Omega", 2020, 7, "4K")

        val firstRun = ContentRecommendationRanker.rank(first, candidates, 5).map { it.stableKey }
        val repeated = ContentRecommendationRanker.rank(first, candidates, 5).map { it.stableKey }
        val otherTitle = ContentRecommendationRanker.rank(second, candidates, 5).map { it.stableKey }

        assertEquals(firstRun, repeated)
        assertNotEquals(firstRun, otherTitle)
    }

    @Test
    fun configurableMaximumIsHonoured() {
        val current = item("current", "Main", 2020, 1, "Drama", listOf("Drama"))
        val candidates = (1..10).map { item("$it", "Title $it", 2020, 1, "Drama") }

        assertEquals(3, ContentRecommendationRanker.rank(current, candidates, 3).size)
        assertTrue(ContentRecommendationRanker.rank(current, candidates, 0).isEmpty())
    }

    @Test
    fun movieAdapterUsesEntityAndCategoryMetadata() {
        val category = CategoryEntity(
            id = 44,
            sourceId = 8,
            mediaType = MediaType.MOVIE,
            name = "Ciencia ficción",
        )
        val movie = MovieEntity(
            id = 9,
            sourceId = 8,
            categoryId = 44,
            name = "Example 4K (2024)",
            streamUrl = "https://example.invalid/movie",
            remoteId = "remote-9",
            parsedYear = 2024,
            qualityRank = 4,
        )

        val item = movie.toRecommendationItem(category, listOf("Science Fiction"))

        assertEquals(movie, item.value)
        assertEquals("movie:8:remote-9", item.stableKey)
        assertEquals(2024, item.year)
        assertEquals("Ciencia ficción", item.categoryName)
        assertEquals(4, item.qualityRank)
    }

    @Test
    fun movieAndSeriesAdaptersPreserveSameTitleVariantsByProviderIdentity() {
        val currentMovie = MovieEntity(
            id = 10,
            sourceId = 8,
            name = "Same title (2024)",
            streamUrl = "https://example.invalid/movie/10",
            remoteId = "100",
            titleSignature = "same-title",
        )
        val firstMovie = MovieEntity(
            id = 1,
            sourceId = 8,
            name = "Same title (2024)",
            streamUrl = "https://example.invalid/movie/1",
            remoteId = "101",
            titleSignature = "same-title",
        )
        val secondMovie = firstMovie.copy(id = 2, streamUrl = "https://example.invalid/movie/2", remoteId = "102")
        val repeatedMovie = firstMovie.copy(id = 99)
        val firstSeries = SeriesEntity(
            id = 3,
            sourceId = 8,
            name = "Same title (2024)",
            remoteId = "201",
            titleSignature = "same-title",
        )
        val secondSeries = firstSeries.copy(id = 4, remoteId = "202")
        val currentSeries = firstSeries.copy(id = 5, remoteId = "200")

        assertNotEquals(firstMovie.toRecommendationItem(null).stableKey, secondMovie.toRecommendationItem(null).stableKey)
        assertEquals(firstMovie.toRecommendationItem(null).stableKey, repeatedMovie.toRecommendationItem(null).stableKey)
        assertNotEquals(firstSeries.toRecommendationItem(null).stableKey, secondSeries.toRecommendationItem(null).stableKey)
        assertEquals(
            setOf("101", "102"),
            ContentRecommendationRanker.rank(
                currentMovie.toRecommendationItem(null),
                listOf(firstMovie, repeatedMovie, secondMovie).map { it.toRecommendationItem(null) },
                limit = 10,
            ).mapTo(linkedSetOf()) { it.value.remoteId },
        )
        assertEquals(
            setOf("201", "202"),
            ContentRecommendationRanker.rank(
                currentSeries.toRecommendationItem(null),
                listOf(firstSeries, firstSeries.copy(id = 99), secondSeries).map { it.toRecommendationItem(null) },
                limit = 10,
            ).mapTo(linkedSetOf()) { it.value.remoteId },
        )
    }

    @Test
    fun adapterFallsBackToLocalIdInsteadOfTitleWhenProviderIdIsMissing() {
        val first = MovieEntity(
            id = 31,
            sourceId = 8,
            name = "Same title",
            streamUrl = "https://example.invalid/movie/31",
            titleSignature = "same-title",
        )
        val second = first.copy(id = 32, streamUrl = "https://example.invalid/movie/32")

        assertEquals("movie:8:local:31", first.toRecommendationItem(null).stableKey)
        assertEquals("movie:8:local:32", second.toRecommendationItem(null).stableKey)
    }

    private fun item(
        key: String,
        title: String,
        year: Int?,
        categoryId: Long?,
        categoryName: String?,
        genres: List<String> = emptyList(),
        rating: Double? = null,
        qualityRank: Int = 0,
    ): RecommendationItem<String> = RecommendationItem(
        value = key,
        stableKey = key,
        title = title,
        year = year,
        categoryId = categoryId,
        categoryName = categoryName,
        genres = genres,
        rating = rating,
        qualityRank = qualityRank,
    )
}
