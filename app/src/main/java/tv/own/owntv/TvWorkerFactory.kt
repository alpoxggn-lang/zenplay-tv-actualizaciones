package tv.own.owntv

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import tv.own.owntv.core.sync.work.KoinWorkerFactory

/**
 * TV-specific WorkManager gate.
 *
 * Older ZenPlay TV releases could leave an offline movie/episode transfer persisted in
 * WorkManager. Removing the buttons is not enough: Android may restore that job after an update.
 * Intercept that one legacy worker before core can construct its transfer engine, while delegating
 * guide, catalog, Trending and recording workers unchanged.
 */
class TvWorkerFactory : WorkerFactory() {
    private val coreFactory = KoinWorkerFactory()

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == LEGACY_CONTENT_DOWNLOAD_WORKER) {
        DisabledContentDownloadWorker(appContext, workerParameters)
    } else {
        coreFactory.createWorker(appContext, workerClassName, workerParameters)
    }
}

/** Cancels persisted content-transfer work without touching APK updates, subtitles or recordings. */
internal fun cancelLegacyContentDownloads(context: Context) {
    WorkManager.getInstance(context).run {
        cancelUniqueWork(LEGACY_CONTENT_DOWNLOAD_WORK_NAME)
        cancelAllWorkByTag(LEGACY_CONTENT_DOWNLOAD_WORK_NAME)
    }
}

private class DisabledContentDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result = Result.success()
}

private const val LEGACY_CONTENT_DOWNLOAD_WORKER = "tv.own.owntv.core.download.DownloadWorker"
private const val LEGACY_CONTENT_DOWNLOAD_WORK_NAME = "owntv-downloads"
