package tv.own.owntv.core.sync.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CatalogSyncSchedulerTest {

    @Test
    fun `periodic refresh is fixed to two hours`() {
        assertEquals(2L, PeriodicCatalogRefreshWorker.PERIOD_HOURS)
    }

    @Test
    fun `periodic and immediate work use distinct unique names`() {
        val sourceId = 42L

        assertNotEquals(
            CatalogSyncScheduler.workName(sourceId),
            CatalogSyncScheduler.periodicWorkName(sourceId),
        )
        assertEquals(
            "catalog-refresh-periodic-source-42",
            CatalogSyncScheduler.periodicWorkName(sourceId),
        )
    }
}
