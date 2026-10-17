package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity

class CatalogRemoteIdentityTest {
    @Test
    fun moviesKeepSameNameDifferentIdsAndDropRepeatedId() {
        val rows = listOf(
            MovieEntity(sourceId = 1, name = "Dune", streamUrl = "a", remoteId = "101"),
            MovieEntity(sourceId = 1, name = "Dune", streamUrl = "b", remoteId = "102"),
            MovieEntity(sourceId = 1, name = "Dune duplicate", streamUrl = "c", remoteId = "101"),
        )
        assertIdentityContract(rows, listOf("101", "102")) { it.remoteId }
    }

    @Test
    fun seriesKeepSameNameDifferentIdsAndDropRepeatedId() {
        val rows = listOf(
            SeriesEntity(sourceId = 1, name = "The Office", remoteId = "201"),
            SeriesEntity(sourceId = 1, name = "The Office", remoteId = "202"),
            SeriesEntity(sourceId = 1, name = "Other name", remoteId = "201"),
        )
        assertIdentityContract(rows, listOf("201", "202")) { it.remoteId }
    }

    @Test
    fun channelsKeepSameNameDifferentIdsAndDropRepeatedId() {
        val rows = listOf(
            ChannelEntity(sourceId = 1, name = "HBO", streamUrl = "a", remoteId = "301"),
            ChannelEntity(sourceId = 1, name = "HBO", streamUrl = "b", remoteId = "302"),
            ChannelEntity(sourceId = 1, name = "HBO East", streamUrl = "c", remoteId = "301"),
        )
        assertIdentityContract(rows, listOf("301", "302")) { it.remoteId }
    }

    private fun <T> assertIdentityContract(rows: List<T>, expectedIds: List<String>, remoteId: (T) -> String?) {
        val pending = mutableListOf<String>()
        val accepted = filterNewCatalogItems(rows, emptySet(), remoteId, pending)
        assertEquals(2, accepted.size)
        assertEquals(expectedIds, pending)
    }
}
