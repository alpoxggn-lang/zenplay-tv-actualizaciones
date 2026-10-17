package tv.own.owntv.core.recommendation

import java.text.Normalizer
import java.util.Locale
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity

/** A language-independent genre understood by the local recommendation ranker. */
enum class SemanticGenre {
    ACTION,
    ADVENTURE,
    ANIMATION,
    BIOGRAPHY,
    COMEDY,
    CRIME,
    DOCUMENTARY,
    DRAMA,
    FAMILY,
    FANTASY,
    HISTORY,
    HORROR,
    KIDS,
    MUSIC,
    MYSTERY,
    NEWS,
    REALITY,
    ROMANCE,
    SCIENCE_FICTION,
    SOAP,
    SPORT,
    TALK,
    THRILLER,
    TV_MOVIE,
    WAR,
    WESTERN,
}

/**
 * The small, UI-neutral projection the ranker needs. [value] remains owned by the caller, so the same
 * utility can rank movies, series, or a future catalogue model without depending on a screen module.
 *
 * [genres] may come from Xtream details, TMDB's cache, or both. [categoryName] is deliberately kept:
 * providers often put a title in a broad technical folder (for example 4K), but also expose semantic
 * folders such as Drama or Ciencia ficcion. Those semantic names are useful when per-item genre data is
 * unavailable.
 */
data class RecommendationItem<T>(
    val value: T,
    val stableKey: String,
    val title: String,
    val year: Int? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val qualityRank: Int = 0,
)

/**
 * Pure, deterministic recommendation ranking. It performs no database or network work.
 *
 * Selection is tiered rather than expressed as one fragile score:
 *  1. candidates whose genre or semantic category overlaps the current title;
 *  2. candidates in the current provider category;
 *  3. the remaining catalogue supplied by the caller.
 *
 * Within each tier a bounded top-quality window is shuffled from the current title's stable key. This
 * keeps results reproducible while preventing every title in one large folder from showing the exact
 * same five globally top-rated entries.
 */
object ContentRecommendationRanker {
    const val DEFAULT_LIMIT = 5

    fun <T> rank(
        current: RecommendationItem<*>,
        candidates: Iterable<RecommendationItem<T>>,
        limit: Int = DEFAULT_LIMIT,
    ): List<RecommendationItem<T>> {
        if (limit <= 0) return emptyList()

        val currentGenres = semanticGenres(current.genres, current.categoryName)
        val currentCategoryName = fold(current.categoryName.orEmpty())

        val ranked = candidates.asSequence()
            .filterNot { it.stableKey == current.stableKey }
            .map { candidate ->
                val candidateGenres = semanticGenres(candidate.genres, candidate.categoryName)
                val overlap = currentGenres.intersect(candidateGenres).size
                val sameCategory = when {
                    current.categoryId != null && candidate.categoryId != null ->
                        current.categoryId == candidate.categoryId
                    currentCategoryName.isNotEmpty() && candidate.categoryName != null ->
                        currentCategoryName == fold(candidate.categoryName)
                    else -> false
                }
                val tier = when {
                    overlap > 0 -> 0
                    sameCategory -> 1
                    else -> 2
                }
                Ranked(
                    item = candidate,
                    identity = candidate.stableKey,
                    tier = tier,
                    genreOverlap = overlap,
                    yearDistance = yearDistance(current.year, candidate.year),
                )
            }
            .sortedWith(baseOrder())
            .toList()

        // Only collapse the same provider item repeated by a query. A provider may intentionally expose
        // several playable rows with the same title/year (different audio, quality, source or edition),
        // so title metadata must never be used as catalogue identity.
        val unique = LinkedHashMap<String, Ranked<T>>()
        ranked.forEach { unique.putIfAbsent(it.identity, it) }

        val out = ArrayList<RecommendationItem<T>>(minOf(limit, unique.size))
        for (tier in 0..2) {
            if (out.size == limit) break
            val tierItems = unique.values.filter { it.tier == tier }
            val diversified = diversify(
                items = tierItems,
                currentStableKey = current.stableKey,
                tier = tier,
                limit = limit,
            )
            diversified.take(limit - out.size).forEach { out += it.item }
        }
        return out
    }

    /** Normalizes Spanish/English genre labels and accents into typed semantic values. */
    fun normalizeGenres(values: Iterable<String>): Set<SemanticGenre> = buildSet {
        values.forEach { value ->
            val folded = fold(value)
            if (folded.isEmpty()) return@forEach
            genrePatterns.forEach { (genre, pattern) ->
                if (pattern.containsMatchIn(folded)) add(genre)
            }
        }
    }

    private fun semanticGenres(values: Iterable<String>, categoryName: String?): Set<SemanticGenre> =
        normalizeGenres(
            if (categoryName.isNullOrBlank()) values else values + categoryName,
        )

    private fun <T> baseOrder(): Comparator<Ranked<T>> =
        compareBy<Ranked<T>> { it.tier }
            .thenByDescending { it.genreOverlap }
            .thenByDescending { it.item.qualityRank }
            .thenByDescending { it.item.rating ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.yearDistance }
            .thenBy { it.item.stableKey }

    private fun <T> diversify(
        items: List<Ranked<T>>,
        currentStableKey: String,
        tier: Int,
        limit: Int,
    ): List<Ranked<T>> {
        if (items.size <= 1) return items
        // Only rotate a quality-ranked head. Entries far below it cannot jump ahead merely because a
        // hash happens to favour them, while a normal five-card row still has enough room to vary.
        val windowSize = minOf(items.size, maxOf(limit + 1, limit * 3))
        val seed = "$currentStableKey|$tier"
        val head = items.take(windowSize).sortedWith(
            compareBy<Ranked<T>> { mixedHash(seed, it.item.stableKey) }
                .thenBy { it.item.stableKey },
        )
        return buildList(items.size) {
            addAll(head)
            addAll(items.drop(windowSize))
        }
    }

    /** SplitMix64 avalanche over two stable JVM string hashes; unlike prefix rotation it also changes
     * relative candidate order when two subjects happen to share the same modulo offset. */
    private fun mixedHash(seed: String, candidate: String): Long {
        var value = (seed.hashCode().toLong() shl 32) xor (candidate.hashCode().toLong() and 0xffff_ffffL)
        value += -7046029254386353131L
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    private fun yearDistance(currentYear: Int?, candidateYear: Int?): Int =
        if (currentYear == null || candidateYear == null) Int.MAX_VALUE
        else kotlin.math.abs(currentYear - candidateYear)

    private fun fold(value: String): String {
        if (value.isBlank()) return ""
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_ALPHANUMERIC, " ")
            .replace(MULTIPLE_SPACES, " ")
            .trim()
    }

    private data class Ranked<T>(
        val item: RecommendationItem<T>,
        val identity: String,
        val tier: Int,
        val genreOverlap: Int,
        val yearDistance: Int,
    )

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val MULTIPLE_SPACES = Regex("\\s+")

    private val genrePatterns: Map<SemanticGenre, Regex> = linkedMapOf(
        SemanticGenre.ACTION to Regex("\\b(?:action|accion)\\b"),
        SemanticGenre.ADVENTURE to Regex("\\b(?:adventure|aventura)\\b"),
        SemanticGenre.ANIMATION to Regex("\\b(?:animation|animated|animacion|animada|animado|anime)\\b"),
        SemanticGenre.BIOGRAPHY to Regex("\\b(?:biography|biographical|biografia|biografica|biografico)\\b"),
        SemanticGenre.COMEDY to Regex("\\b(?:comedy|comedia)\\b"),
        SemanticGenre.CRIME to Regex("\\b(?:crime|criminal|crimen|policiaca|policiaco)\\b"),
        SemanticGenre.DOCUMENTARY to Regex("\\b(?:documentary|documentaries|documental|documentales)\\b"),
        SemanticGenre.DRAMA to Regex("\\b(?:drama|dramatic|dramatica|dramatico)\\b"),
        SemanticGenre.FAMILY to Regex("\\b(?:family|familia|familiar)\\b"),
        SemanticGenre.FANTASY to Regex("\\b(?:fantasy|fantasia)\\b"),
        SemanticGenre.HISTORY to Regex("\\b(?:history|historical|historia|historica|historico)\\b"),
        SemanticGenre.HORROR to Regex("\\b(?:horror|terror)\\b"),
        SemanticGenre.KIDS to Regex("\\b(?:kids|children|childrens|infantil|infantiles|nino|ninos)\\b"),
        SemanticGenre.MUSIC to Regex("\\b(?:music|musical|musicals|musica|musicales)\\b"),
        SemanticGenre.MYSTERY to Regex("\\b(?:mystery|misterio)\\b"),
        SemanticGenre.NEWS to Regex("\\b(?:news|noticias)\\b"),
        SemanticGenre.REALITY to Regex("\\b(?:reality|realidad)\\b"),
        SemanticGenre.ROMANCE to Regex("\\b(?:romance|romantic|romantica|romantico)\\b"),
        SemanticGenre.SCIENCE_FICTION to Regex(
            "\\b(?:science fiction|sci fi|scifi|ciencia ficcion|ficcion cientifica)\\b",
        ),
        SemanticGenre.SOAP to Regex("\\b(?:soap|soap opera|telenovela|telenovelas)\\b"),
        SemanticGenre.SPORT to Regex("\\b(?:sport|sports|deporte|deportes|deportiva|deportivo)\\b"),
        SemanticGenre.TALK to Regex("\\b(?:talk|talk show|interviews|entrevistas)\\b"),
        SemanticGenre.THRILLER to Regex("\\b(?:thriller|suspense|suspenso)\\b"),
        SemanticGenre.TV_MOVIE to Regex(
            "\\b(?:tv movie|television movie|pelicula de tv|pelicula para television)\\b",
        ),
        SemanticGenre.WAR to Regex("\\b(?:war|warfare|guerra|belica|belico)\\b"),
        SemanticGenre.WESTERN to Regex("\\b(?:western|westerns|oeste)\\b"),
    )
}

/** Maps a persisted film plus its already-known provider category into the pure ranker model. */
fun MovieEntity.toRecommendationItem(
    category: CategoryEntity?,
    genres: Collection<String> = emptyList(),
): RecommendationItem<MovieEntity> = RecommendationItem(
    value = this,
    stableKey = recommendationStableKey(),
    title = name,
    year = year ?: parsedYear,
    categoryId = categoryId,
    categoryName = category?.takeIf { it.id == categoryId }?.name,
    genres = genres.toList(),
    rating = rating,
    qualityRank = qualityRank,
)

/** Maps a persisted show plus its already-known provider category into the pure ranker model. */
fun SeriesEntity.toRecommendationItem(
    category: CategoryEntity?,
    genres: Collection<String> = emptyList(),
): RecommendationItem<SeriesEntity> = RecommendationItem(
    value = this,
    stableKey = recommendationStableKey(),
    title = name,
    year = year ?: parsedYear,
    categoryId = categoryId,
    categoryName = category?.takeIf { it.id == categoryId }?.name,
    genres = genres.toList(),
    rating = rating,
    qualityRank = qualityRank,
)

/** Provider identity used by recommendation deduplication. Display metadata is intentionally absent. */
internal fun MovieEntity.recommendationStableKey(): String =
    stableRecommendationKey("movie", sourceId, remoteId, id)

/** Provider identity used by recommendation deduplication. Display metadata is intentionally absent. */
internal fun SeriesEntity.recommendationStableKey(): String =
    stableRecommendationKey("series", sourceId, remoteId, id)

private fun stableRecommendationKey(
    mediaType: String,
    sourceId: Long,
    remoteId: String?,
    localId: Long,
): String {
    val providerId = remoteId?.takeIf(String::isNotBlank)
    return if (providerId != null) "$mediaType:$sourceId:$providerId"
    else "$mediaType:$sourceId:local:$localId"
}
