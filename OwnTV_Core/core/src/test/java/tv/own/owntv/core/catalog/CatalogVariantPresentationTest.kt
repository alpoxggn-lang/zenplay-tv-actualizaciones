package tv.own.owntv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity

class CatalogVariantPresentationTest {
    @Test
    fun moviesWithSameNameAndDifferentIdsKeepBothAndShowUsefulVariants() {
        val movies = listOf(
            MovieEntity(
                id = 1, sourceId = 9, name = "Dune", streamUrl = "https://example.invalid/101.mp4",
                remoteId = "101", providerLanguage = "ES", qualityRank = 4,
            ),
            MovieEntity(
                id = 2, sourceId = 9, name = "Dune", streamUrl = "https://example.invalid/102.mkv",
                remoteId = "102", providerLanguage = "EN", qualityRank = 3,
            ),
        )

        val names = catalogVariantDisplayNames(movies.map { it.catalogVariantCandidate() })

        assertEquals(2, names.size)
        assertNotEquals(names[1], names[2])
        assertTrue(names.getValue(1).contains("ES"))
        assertTrue(names.getValue(1).contains("4K"))
        assertTrue(names.getValue(2).contains("EN"))
        assertTrue(names.getValue(2).contains("1080p"))
    }

    @Test
    fun seriesWithSameNameAndDifferentIdsFallBackToRemoteIdentityWhenMetadataMatches() {
        val series = listOf(
            SeriesEntity(id = 11, sourceId = 3, name = "The Office", remoteId = "501", qualityRank = 2),
            SeriesEntity(id = 12, sourceId = 3, name = "The Office", remoteId = "502", qualityRank = 2),
        )

        val names = catalogVariantDisplayNames(series.map { it.catalogVariantCandidate() })

        assertEquals(2, names.size)
        assertNotEquals(names[11], names[12])
        assertTrue(names.getValue(11).contains("ID 501"))
        assertTrue(names.getValue(12).contains("ID 502"))
    }

    @Test
    fun channelsWithSameNameAndDifferentIdsUseNumberAndStreamWithoutExposingUrl() {
        val channels = listOf(
            ChannelEntity(
                id = 21, sourceId = 4, name = "HBO", streamUrl = "https://user:secret@example.invalid/live/601.m3u8",
                remoteId = "601", number = 10,
            ),
            ChannelEntity(
                id = 22, sourceId = 4, name = "HBO", streamUrl = "https://user:secret@example.invalid/live/602.ts",
                remoteId = "602", number = 11,
            ),
        )

        val names = catalogVariantDisplayNames(channels.map { it.catalogVariantCandidate() })

        assertEquals(2, names.size)
        assertTrue(names.getValue(21).contains("#10"))
        assertTrue(names.getValue(21).contains("HLS"))
        assertTrue(names.getValue(22).contains("#11"))
        assertTrue(names.values.none { it.contains("secret") || it.contains("example.invalid") })
    }

    @Test
    fun aUniqueTitleIsNotDecorated() {
        val movie = MovieEntity(
            id = 31, sourceId = 1, name = "Arrival", streamUrl = "https://example.invalid/31.mp4",
            remoteId = "31", providerLanguage = "ES", qualityRank = 4,
        )
        val names = catalogVariantDisplayNames(listOf(movie.catalogVariantCandidate()))
        assertEquals("Arrival", names[31])
    }
}
