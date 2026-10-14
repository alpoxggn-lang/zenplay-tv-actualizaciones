package tv.own.owntv.core.sync.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * A tiny WorkManager heartbeat that hands the real work to [CatalogSyncWorker].
 *
 * Keeping the heartbeat separate from the sync's unique work name means a periodic schedule is not
 * destroyed when the viewer taps Refresh. The actual enqueue uses KEEP, so an already-running
 * manual, startup or resume refresh is never duplicated.
 */
class PeriodicCatalogRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val sourceId = inputData.getLong(KEY_SOURCE_ID, -1L)
        if (sourceId < 0L) return Result.failure()

        CatalogSyncScheduler(applicationContext).enqueueSync(
            sourceId = sourceId,
            reason = "periodic_2h",
            policy = ExistingWorkPolicy.KEEP,
        )
        return Result.success()
    }

    companion object {
        const val KEY_SOURCE_ID = "sourceId"
        const val TAG = "catalog-periodic-refresh"
        const val PERIOD_HOURS = 2L
    }
}
