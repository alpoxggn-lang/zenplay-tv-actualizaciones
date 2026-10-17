package tv.own.owntv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import tv.own.owntv.core.sync.work.KoinWorkerFactory

/**
 * Device-aware WorkManager gate for the universal APK.
 *
 * Older ZenPlay TV releases could leave an offline movie/episode transfer persisted in
 * WorkManager. Removing the buttons is not enough: Android may restore that job after an update.
 * Intercept that one legacy worker on television/remote-first installs before core can construct
 * its transfer engine, while delegating guide, catalog, Trending and recording workers unchanged.
 *
 * The same APK also mounts the touch shell on phones and tablets. Those devices must delegate the
 * real [tv.own.owntv.core.download.DownloadWorker]; globally swallowing it left every mobile row
 * permanently QUEUED/RUNNING with zero bytes transferred.
 */
class TvWorkerFactory : WorkerFactory() {
    private val coreFactory = KoinWorkerFactory()

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        val disableContentDownload = shouldDisableContentDownloadWorker(
            workerClassName = workerClassName,
            downloadsEnabled = contentDownloadsEnabled(appContext),
        )
        return if (disableContentDownload) {
            DisabledContentDownloadWorker(appContext, workerParameters)
        } else {
            coreFactory.createWorker(appContext, workerClassName, workerParameters)
        }
    }
}

/** Cancels persisted content-transfer work without touching APK updates, subtitles or recordings. */
internal fun cancelLegacyContentDownloads(context: Context) {
    WorkManager.getInstance(context).run {
        cancelUniqueWork(LEGACY_CONTENT_DOWNLOAD_WORK_NAME)
        cancelAllWorkByTag(LEGACY_CONTENT_DOWNLOAD_WORK_NAME)
    }
}

/**
 * Offline film/episode downloads are a touch-shell feature only.
 *
 * Keep this aligned with the adaptive-shell rule: an explicit TV declaration wins, and an
 * unclassified box with no touchscreen is treated as remote-first. Keeping the decision here
 * synchronous is important because WorkManager can ask the factory before any Activity exists.
 */
internal fun contentDownloadsEnabled(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val packageManager = context.packageManager
    val televisionUi =
        uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val touchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    return contentDownloadsEnabled(televisionUi = televisionUi, touchscreen = touchscreen)
}

internal fun contentDownloadsEnabled(televisionUi: Boolean, touchscreen: Boolean): Boolean =
    !televisionUi && touchscreen

internal fun shouldDisableContentDownloadWorker(
    workerClassName: String,
    downloadsEnabled: Boolean,
): Boolean = workerClassName == LEGACY_CONTENT_DOWNLOAD_WORKER && !downloadsEnabled

private class DisabledContentDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result = Result.success()
}

private const val LEGACY_CONTENT_DOWNLOAD_WORKER = "tv.own.owntv.core.download.DownloadWorker"
private const val LEGACY_CONTENT_DOWNLOAD_WORK_NAME = "owntv-downloads"
