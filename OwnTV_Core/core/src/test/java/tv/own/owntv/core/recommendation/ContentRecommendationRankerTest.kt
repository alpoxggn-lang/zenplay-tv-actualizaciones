package tv.own.owntv.core.recommendation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.MovieEntity
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
    fun excludesCurrentAndQualityVariantsButKeepsRemakes() {
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
        assertFalse("dune-fhd" in keys)
        assertTrue("dune-remake" in keys)
        assertTrue("matrix-4k" in keys)
        assertFalse("matrix-hd" in keys)
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
