package tv.own.owntv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity

class CompatibleVariantResolverTest {
    @Test
    fun movieNeverCrossesAReleaseYear() {
        val original = movie(1, "Dune (2021) 4K", durationSecs = 9_300)
        val remake = movie(2, "Dune (1984) FHD", durationSecs = 8_200)
        val sameRelease = movie(3, "Dune (2021) FHD", durationSecs = 9_200)

        val result = CompatibleVariantSelector.rankMovies(original, listOf(remake, sameRelease))

        assertEquals(listOf(3L), result.map(MovieEntity::id))
    }

    @Test
    fun movieNeverCrossesACanonicalTitleCollisionEvenInTheSameYear() {
        val original = movie(1, "A+ (2024) 4K")
        val colliding = movie(2, "A (2024) FHD").copy(
            titleSignature = original.titleSignature,
        )

        assertTrue(
            CompatibleVariantSelector.rankMovies(original, listOf(colliding)).isEmpty(),
        )
    }

    @Test
    fun replacementMustKeepTheExactProviderLanguage() {
        val original = movie(1, "|ES| Arrival (2016) 4K")
        val english = movie(2, "|EN| Arrival (2016) FHD")
        val unlabelled = movie(3, "Arrival (2016) HD")
        val spanish = movie(4, "|ES| Arrival (2016) HD")

        val result = CompatibleVariantSelector.rankMovies(
            original,
            listOf(english, unlabelled, spanish),
        )

        assertEquals(listOf(4L), result.map(MovieEntity::id))
    }

    @Test
    fun compatibleQualityExcludes4kHdrAndKeepsUnlabelledAfterSd() {
        val original = movie(1, "Movie (2024) 4K HDR")
        val hdr1080 = movie(2, "Movie (2024) FHD HDR")
        val eightK = movie(3, "Movie (2024) 8K")
        val uhd = movie(4, "Movie (2024) 4K")
        val sd = movie(5, "Movie (2024) SD")
        val hd = movie(6, "Movie (2024) HD")
        val fullHd = movie(7, "Movie (2024) FHD")
        val unlabelled = movie(8, "Movie (2024)")

        val result = CompatibleVariantSelector.rankMovies(
            original,
            listOf(hdr1080, eightK, uhd, sd, unlabelled, hd, fullHd),
        )

        assertEquals(listOf(7L, 6L, 5L, 8L), result.map(MovieEntity::id))
    }

    @Test
    fun sameIdentitySameUrlExcludedAttemptAndUnexpectedDrmAreRejected() {
        val original = movie(1, "Movie (2024) 4K", streamUrl = "https://cdn/original.mkv")
        val sameId = movie(1, "Movie (2024) FHD", streamUrl = "https://cdn/id.mp4")
        val sameUrl = movie(2, "Movie (2024) FHD", streamUrl = original.streamUrl)
        val attempted = movie(3, "Movie (2024) FHD", streamUrl = "https://cdn/attempted.mp4")
        val duplicateAttemptUrl = movie(4, "Movie (2024) FHD", streamUrl = attempted.streamUrl)
        val drm = movie(5, "Movie (2024) FHD", drmConfig = "widevine")
        val valid = movie(6, "Movie (2024) HD")

        val result = CompatibleVariantSelector.rankMovies(
            original,
            listOf(sameId, sameUrl, attempted, duplicateAttemptUrl, drm, valid),
            excludedIds = setOf(3),
        )

        assertEquals(listOf(6L), result.map(MovieEntity::id))
    }

    @Test
    fun durationMustStayWithinTwentyPercentWhenBothAreKnown() {
        val original = movie(1, "Movie (2024) 4K", durationSecs = 6_000)
        val tooShort = movie(2, "Movie (2024) FHD", durationSecs = 4_799)
        val boundary = movie(3, "Movie (2024) FHD", durationSecs = 4_800)
        val unknown = movie(4, "Movie (2024) FHD", durationSecs = null)

        val result = CompatibleVariantSelector.rankMovies(
            original,
            listOf(tooShort, unknown, boundary),
        )

        assertEquals(listOf(3L, 4L), result.map(MovieEntity::id))
    }

    @Test
    fun missingYearFailsClosedWhenMoreThanOneCandidateCouldMatch() {
        val original = movie(1, "The Thing 4K")
        val first = movie(2, "The Thing FHD")
        val second = movie(3, "THE THING HD")

        assertTrue(
            CompatibleVariantSelector.rankMovies(original, listOf(first, second)).isEmpty(),
        )
        assertEquals(
            2L,
            CompatibleVariantSelector.rankMovies(original, listOf(first)).single().id,
        )
    }

    @Test
    fun missingYearFailsClosedWhenAnUndatedRowCompetesWithADatedRemake() {
        val original = movie(1, "Dune 4K")
        val undated = movie(2, "Dune FHD")
        val dated = movie(3, "Dune (2021) HD")

        assertTrue(
            CompatibleVariantSelector.rankMovies(original, listOf(undated, dated)).isEmpty(),
        )
    }

    @Test
    fun mp4BreaksATieWithoutBeatingSaferResolutionOrder() {
        val original = movie(1, "Movie (2024) 4K")
        val mkv1080 = movie(2, "Movie (2024) FHD", containerExt = "mkv")
        val mp41080 = movie(3, "Movie (2024) FHD", containerExt = ".MP4")
        val mp4720 = movie(4, "Movie (2024) HD", containerExt = "mp4")

        val result = CompatibleVariantSelector.rankMovies(
            original,
            listOf(mkv1080, mp4720, mp41080),
        )

        assertEquals(listOf(3L, 2L, 4L), result.map(MovieEntity::id))
    }

    @Test
    fun episodeRequiresEquivalentShowAndExactSeasonAndEpisode() {
        val originalShow = series(1, "|ES| Show (2020) 4K")
        val replacementShow = series(2, "|ES| Show (2020) FHD")
        val otherYear = series(3, "|ES| Show (2010) FHD")
        val otherLanguage = series(4, "|EN| Show (2020) FHD")
        assertEquals(
            listOf(2L),
            CompatibleVariantSelector.rankSeries(
                originalShow,
                listOf(replacementShow, otherYear, otherLanguage),
            ).map(SeriesEntity::id),
        )

        val originalEpisode = episode(10, originalShow.id, 2, 7, durationSecs = 3_000)
        val wrongEpisode = episode(20, replacementShow.id, 2, 8, durationSecs = 3_000)
        val exactEpisode = episode(
            21,
            replacementShow.id,
            2,
            7,
            durationSecs = 2_950,
            containerExt = "mp4",
        )
        val queue = listOf(exactEpisode, wrongEpisode)
        val result = CompatibleVariantSelector.rankEpisodes(
            originalShow = originalShow,
            originalEpisode = originalEpisode,
            candidates = listOf(
                CompatibleEpisodeVariant(replacementShow, wrongEpisode, queue),
                CompatibleEpisodeVariant(replacementShow, exactEpisode, queue),
            ),
        )

        assertEquals(21L, result.single().replacementEpisode.id)
        assertEquals(queue, result.single().replacementQueue)
    }

    @Test
    fun episodeExcludesAttemptedUrlDrmAndDurationMismatch() {
        val originalShow = series(1, "Show (2020) 4K")
        val showA = series(2, "Show (2020) FHD")
        val showB = series(3, "Show (2020) FHD")
        val showC = series(4, "Show (2020) FHD")
        val originalEpisode = episode(10, originalShow.id, 1, 1, durationSecs = 3_000)
        val attempted = episode(20, showA.id, 1, 1, durationSecs = 3_000)
        val sameAttemptUrl = episode(
            21,
            showB.id,
            1,
            1,
            durationSecs = 3_000,
            streamUrl = attempted.streamUrl,
        )
        val drm = episode(22, showC.id, 1, 1, durationSecs = 3_000, drmConfig = "widevine")
        val tooLong = episode(23, showC.id, 1, 1, durationSecs = 3_601)
        val valid = episode(24, showC.id, 1, 1, durationSecs = 3_600)

        val result = CompatibleVariantSelector.rankEpisodes(
            originalShow = originalShow,
            originalEpisode = originalEpisode,
            candidates = listOf(attempted, sameAttemptUrl, drm, tooLong, valid).map { candidate ->
                val show = when (candidate.seriesId) {
                    showA.id -> showA
                    showB.id -> showB
                    else -> showC
                }
                CompatibleEpisodeVariant(show, candidate, listOf(candidate))
            },
            excludedEpisodeIds = setOf(attempted.id),
        )

        assertEquals(listOf(24L), result.map { it.replacementEpisode.id })
    }

    private fun movie(
        id: Long,
        name: String,
        durationSecs: Int? = null,
        streamUrl: String = "https://cdn/$id.mkv",
        containerExt: String? = "mkv",
        drmConfig: String? = null,
    ): MovieEntity {
        val parsed = ProviderCatalogMetadataParser.parse(name, null)
        return MovieEntity(
            id = id,
            sourceId = SOURCE_ID,
            name = name,
            durationSecs = durationSecs,
            streamUrl = streamUrl,
            containerExt = containerExt,
            drmConfig = drmConfig,
            canonicalTitle = parsed.canonicalTitle,
            titleSignature = parsed.titleSignature,
            parsedYear = parsed.parsedYear,
            providerLanguage = parsed.language,
            qualityRank = parsed.qualityRank,
            advertisedCapabilities = parsed.capabilities,
        )
    }

    private fun series(id: Long, name: String): SeriesEntity {
        val parsed = ProviderCatalogMetadataParser.parse(name, null)
        return SeriesEntity(
            id = id,
            sourceId = SOURCE_ID,
            name = name,
            canonicalTitle = parsed.canonicalTitle,
            titleSignature = parsed.titleSignature,
            parsedYear = parsed.parsedYear,
            providerLanguage = parsed.language,
            qualityRank = parsed.qualityRank,
            advertisedCapabilities = parsed.capabilities,
        )
    }

    private fun episode(
        id: Long,
        seriesId: Long,
        season: Int,
        number: Int,
        durationSecs: Int?,
        streamUrl: String = "https://cdn/episode-$id.mkv",
        containerExt: String? = "mkv",
        drmConfig: String? = null,
    ): EpisodeEntity = EpisodeEntity(
        id = id,
        seriesId = seriesId,
        seasonNumber = season,
        episodeNumber = number,
        name = "Episode $number",
        streamUrl = streamUrl,
        durationSecs = durationSecs,
        containerExt = containerExt,
        drmConfig = drmConfig,
    )

    private companion object {
        const val SOURCE_ID = 10L
    }
}
