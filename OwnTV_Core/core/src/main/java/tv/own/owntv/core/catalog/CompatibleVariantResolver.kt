package tv.own.owntv.core.catalog

import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.repository.SeriesRepository

/** A provider copy of the same episode which is more likely to decode on the current device. */
data class CompatibleEpisodeVariant(
    val replacementShow: SeriesEntity,
    val replacementEpisode: EpisodeEntity,
    val replacementQueue: List<EpisodeEntity>,
)

/**
 * Resolves a conservative fallback after the player has identified an unsupported video format.
 *
 * This class intentionally does not perform fuzzy title search. A false negative merely leaves the
 * existing playback error visible; a false positive could play a remake, another episode, or a
 * different-language copy without the user asking for it. DAO lookups therefore stay on the
 * `(sourceId, titleSignature)` index and every result is checked again before it can be returned.
 */
class CompatibleVariantResolver(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val seriesRepository: SeriesRepository,
) {
    suspend fun findMovie(original: MovieEntity, excludedIds: Set<Long>): MovieEntity? {
        val signature = original.titleSignature.takeIf(::usableSignature) ?: return null
        val candidateWindow = movieDao.compatibleVariantCandidates(
            sourceId = original.sourceId,
            titleSignature = signature,
            limit = CANDIDATE_QUERY_LIMIT,
        )
        if (!original.hasKnownYear() && candidateWindow.size >= CANDIDATE_QUERY_LIMIT) return null
        val candidates = candidateWindow.take(MAX_EQUIVALENCE_CANDIDATES)
        return CompatibleVariantSelector.rankMovies(original, candidates, excludedIds).firstOrNull()
    }

    suspend fun findEpisode(
        originalShow: SeriesEntity,
        originalEpisode: EpisodeEntity,
        excludedEpisodeIds: Set<Long>,
    ): CompatibleEpisodeVariant? {
        val signature = originalShow.titleSignature.takeIf(::usableSignature) ?: return null
        val candidateWindow = seriesDao.compatibleVariantCandidates(
            sourceId = originalShow.sourceId,
            titleSignature = signature,
            limit = CANDIDATE_QUERY_LIMIT,
        )
        if (!originalShow.hasKnownYear() && candidateWindow.size >= CANDIDATE_QUERY_LIMIT) return null
        val showCandidates = candidateWindow.take(MAX_EQUIVALENCE_CANDIDATES)
        val eligibleShows = CompatibleVariantSelector.rankSeries(originalShow, showCandidates)
        if (eligibleShows.isEmpty()) return null

        suspend fun storedVariant(show: SeriesEntity): CompatibleEpisodeVariant? =
            seriesDao.findEpisodeByNumber(
                seriesId = show.id,
                season = originalEpisode.seasonNumber,
                episode = originalEpisode.episodeNumber,
            )?.let { episode -> CompatibleEpisodeVariant(show, episode, emptyList()) }

        suspend fun withQueue(candidate: CompatibleEpisodeVariant): CompatibleEpisodeVariant =
            candidate.copy(
                replacementQueue = seriesDao.episodesBySeriesOnce(candidate.replacementShow.id),
            )

        // Most alternatives already have episodes from catalogue sync or an earlier open. Prefer
        // those zero-network rows and rank all of them before asking the provider for anything.
        val cachedCandidates = eligibleShows.mapNotNull { storedVariant(it) }
        val inspectedCandidates = cachedCandidates.toMutableList()
        CompatibleVariantSelector.rankEpisodes(
            originalShow = originalShow,
            originalEpisode = originalEpisode,
            candidates = inspectedCandidates,
            excludedEpisodeIds = excludedEpisodeIds,
        ).firstOrNull()?.let { return withQueue(it) }

        // A title signature can legally have dozens of provider copies. Never turn one decoder
        // error into 64 sequential Xtream/Stalker calls; inspect the best-ranked few and stop as soon
        // as an exact, safe S/E exists.
        val cachedShowIds = cachedCandidates.mapTo(mutableSetOf()) { it.replacementShow.id }
        for (show in eligibleShows.filterNot { it.id in cachedShowIds }.take(MAX_REMOTE_EPISODE_LOOKUPS)) {
            val loaded = try {
                seriesRepository.loadEpisodes(show)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!loaded) continue
            val candidate = storedVariant(show) ?: continue
            // Keep every inspected physical URL in the ranking set. Excluding a failed episode id
            // must also exclude another provider row that aliases that exact same stream URL.
            inspectedCandidates += candidate
            CompatibleVariantSelector.rankEpisodes(
                originalShow = originalShow,
                originalEpisode = originalEpisode,
                candidates = inspectedCandidates,
                excludedEpisodeIds = excludedEpisodeIds,
            ).firstOrNull()?.let { return withQueue(it) }
        }
        return null
    }

    private fun usableSignature(value: String): Boolean =
        value.isNotBlank() && !value.startsWith("__unmatchable_")

    private fun MovieEntity.hasKnownYear(): Boolean =
        year?.let { it > 0 } == true || parsedYear?.let { it > 0 } == true

    private fun SeriesEntity.hasKnownYear(): Boolean =
        year?.let { it > 0 } == true || parsedYear?.let { it > 0 } == true

    private companion object {
        /** A corrupt provider cannot turn one playback failure into an unbounded catalogue read. */
        const val MAX_EQUIVALENCE_CANDIDATES = 64
        /** One extra row tells an undated identity check that its candidate window was truncated. */
        const val CANDIDATE_QUERY_LIMIT = MAX_EQUIVALENCE_CANDIDATES + 1
        const val MAX_REMOTE_EPISODE_LOOKUPS = 8
    }
}

/** Pure selection rules kept separate so their safety invariants can be exhaustively unit-tested. */
internal object CompatibleVariantSelector {
    fun rankMovies(
        original: MovieEntity,
        candidates: List<MovieEntity>,
        excludedIds: Set<Long> = emptySet(),
    ): List<MovieEntity> {
        val blockedUrls = buildSet {
            add(original.streamUrl)
            candidates.filter { it.id in excludedIds }.mapTo(this) { it.streamUrl }
        }
        val identityMatches = candidates.filter { candidate ->
            candidate.sourceId == original.sourceId &&
                candidate.titleSignature == original.titleSignature &&
                candidate.id != original.id &&
                candidate.id !in excludedIds &&
                candidate.streamUrl !in blockedUrls &&
                languagesAreCompatible(original.providerLanguage, candidate.providerLanguage)
        }
        val equivalent = safeYearMatches(
            originalYear = original.effectiveYear(),
            originalCanonicalTitle = original.canonicalTitle,
            candidates = identityMatches,
            year = { it.effectiveYear() },
            canonicalTitle = { it.canonicalTitle },
        )
        return equivalent
            .asSequence()
            .filter { it.isProbablyDeviceCompatible() }
            .filter { original.hasDrm() || !it.hasDrm() }
            .filter { durationsAreCompatible(original.durationSecs, it.durationSecs) }
            .sortedWith(movieComparator(original))
            .toList()
    }

    fun rankSeries(
        original: SeriesEntity,
        candidates: List<SeriesEntity>,
    ): List<SeriesEntity> {
        val identityMatches = candidates.filter { candidate ->
            candidate.sourceId == original.sourceId &&
                candidate.titleSignature == original.titleSignature &&
                candidate.id != original.id &&
                languagesAreCompatible(original.providerLanguage, candidate.providerLanguage)
        }
        return safeYearMatches(
            originalYear = original.effectiveYear(),
            originalCanonicalTitle = original.canonicalTitle,
            candidates = identityMatches,
            year = { it.effectiveYear() },
            canonicalTitle = { it.canonicalTitle },
        ).asSequence()
            .filter { it.isProbablyDeviceCompatible() }
            .sortedWith(seriesComparator(original))
            .toList()
    }

    fun rankEpisodes(
        originalShow: SeriesEntity,
        originalEpisode: EpisodeEntity,
        candidates: List<CompatibleEpisodeVariant>,
        excludedEpisodeIds: Set<Long> = emptySet(),
    ): List<CompatibleEpisodeVariant> {
        val blockedUrls = buildSet {
            add(originalEpisode.streamUrl)
            candidates
                .map(CompatibleEpisodeVariant::replacementEpisode)
                .filter { it.id in excludedEpisodeIds }
                .mapTo(this) { it.streamUrl }
        }
        return candidates.asSequence()
            .filter { it.replacementShow.id != originalShow.id }
            .filter {
                it.replacementEpisode.seriesId == it.replacementShow.id &&
                    it.replacementEpisode.seasonNumber == originalEpisode.seasonNumber &&
                    it.replacementEpisode.episodeNumber == originalEpisode.episodeNumber
            }
            .filter {
                it.replacementEpisode.id != originalEpisode.id &&
                    it.replacementEpisode.id !in excludedEpisodeIds &&
                    it.replacementEpisode.streamUrl !in blockedUrls
            }
            .filter { it.replacementShow.isProbablyDeviceCompatible() }
            .filter {
                originalEpisode.hasDrm() || !it.replacementEpisode.hasDrm()
            }
            .filter {
                durationsAreCompatible(
                    originalEpisode.durationSecs,
                    it.replacementEpisode.durationSecs,
                )
            }
            .sortedWith(episodeComparator(originalShow, originalEpisode))
            .toList()
    }

    private fun movieComparator(original: MovieEntity): Comparator<MovieEntity> =
        compareByDescending<MovieEntity> { it.qualityRank }
            .thenByDescending { sameExplicitLanguage(original.providerLanguage, it.providerLanguage) }
            .thenByDescending { it.containerExt.isMp4() }
            .thenBy { durationDistance(original.durationSecs, it.durationSecs) }
            .thenBy { it.sortOrder }
            .thenBy { it.id }

    private fun seriesComparator(original: SeriesEntity): Comparator<SeriesEntity> =
        compareByDescending<SeriesEntity> { it.qualityRank }
            .thenByDescending { sameExplicitLanguage(original.providerLanguage, it.providerLanguage) }
            .thenBy { it.sortOrder }
            .thenBy { it.id }

    private fun episodeComparator(
        originalShow: SeriesEntity,
        originalEpisode: EpisodeEntity,
    ): Comparator<CompatibleEpisodeVariant> =
        compareByDescending<CompatibleEpisodeVariant> { it.replacementShow.qualityRank }
            .thenByDescending {
                sameExplicitLanguage(
                    originalShow.providerLanguage,
                    it.replacementShow.providerLanguage,
                )
            }
            .thenByDescending { it.replacementEpisode.containerExt.isMp4() }
            .thenBy {
                durationDistance(
                    originalEpisode.durationSecs,
                    it.replacementEpisode.durationSecs,
                )
            }
            .thenBy { it.replacementShow.sortOrder }
            .thenBy { it.replacementEpisode.id }

    private fun MovieEntity.isProbablyDeviceCompatible(): Boolean =
        qualityRank in 0..3 && !advertisesHighDynamicRange(advertisedCapabilities)

    private fun SeriesEntity.isProbablyDeviceCompatible(): Boolean =
        qualityRank in 0..3 && !advertisesHighDynamicRange(advertisedCapabilities)

    private fun MovieEntity.effectiveYear(): Int? = year.validYear() ?: parsedYear.validYear()

    private fun SeriesEntity.effectiveYear(): Int? = year.validYear() ?: parsedYear.validYear()

    private fun MovieEntity.hasDrm(): Boolean = !drmConfig.isNullOrBlank()

    private fun EpisodeEntity.hasDrm(): Boolean = !drmConfig.isNullOrBlank()

    private fun Int?.validYear(): Int? = this?.takeIf { it > 0 }

    private fun String?.isMp4(): Boolean =
        this?.trim()?.removePrefix(".")?.equals("mp4", ignoreCase = true) == true

    private fun languagesAreCompatible(original: String?, candidate: String?): Boolean =
        original.normalizedLanguage() == candidate.normalizedLanguage()

    private fun sameExplicitLanguage(original: String?, candidate: String?): Boolean {
        val left = original.normalizedLanguage() ?: return false
        return left == candidate.normalizedLanguage()
    }

    private fun String?.normalizedLanguage(): String? =
        this?.trim()?.uppercase()?.takeIf(String::isNotEmpty)

    private fun advertisesHighDynamicRange(capabilities: String?): Boolean {
        val value = capabilities?.lowercase() ?: return false
        return "hdr" in value || "dolby vision" in value || "dovi" in value ||
            Regex("""(?:^|[^a-z])dv(?:[^a-z]|$)""").containsMatchIn(value)
    }

    private fun durationsAreCompatible(originalSecs: Int?, candidateSecs: Int?): Boolean {
        if (originalSecs == null || originalSecs <= 0 || candidateSecs == null || candidateSecs <= 0) {
            return true
        }
        return abs(candidateSecs.toLong() - originalSecs.toLong()) * 100L <= originalSecs.toLong() * 20L
    }

    private fun durationDistance(originalSecs: Int?, candidateSecs: Int?): Long {
        if (originalSecs == null || originalSecs <= 0 || candidateSecs == null || candidateSecs <= 0) {
            return Long.MAX_VALUE
        }
        return abs(candidateSecs.toLong() - originalSecs.toLong())
    }

    private fun <T> safeYearMatches(
        originalYear: Int?,
        originalCanonicalTitle: String,
        candidates: List<T>,
        year: (T) -> Int?,
        canonicalTitle: (T) -> String,
    ): List<T> {
        if (originalYear != null) {
            return candidates.filter {
                year(it) == originalYear &&
                    canonicalTitlesEqual(originalCanonicalTitle, canonicalTitle(it))
            }
        }

        val exactTitleCandidates = candidates.filter {
            canonicalTitlesEqual(originalCanonicalTitle, canonicalTitle(it))
        }
        // Without a year there is no safe way to distinguish an undated row from a dated remake,
        // even if only one of the candidates is itself undated. Require the whole exact-title set
        // to contain one unambiguous row; otherwise fail closed instead of guessing.
        return exactTitleCandidates
            .takeIf { matches -> matches.size == 1 && year(matches.single()) == null }
            .orEmpty()
    }

    private fun canonicalTitlesEqual(left: String, right: String): Boolean =
        left.trim().isNotEmpty() && left.trim().equals(right.trim(), ignoreCase = true)
}
