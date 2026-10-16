package tv.own.owntv.core.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.R
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore

/** Durable foreground owner for an APK transfer. Its partial file survives worker/process restarts. */
internal class UpdateDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
    private val manager: UpdateManager,
    private val localeStore: LocaleStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        UpdateDownloadNotifications.foregroundInfo(applicationContext, manager.persistedProgress(), localeStore)

    override suspend fun doWork(): Result {
        val requestedInfo = requestedInfo()
        try {
            setForeground(getForegroundInfo())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "could not promote update download to foreground", error)
            return if (shouldRetryUpdateWork(runAttemptCount, MAX_AUTOMATIC_RETRIES)) {
                Result.retry()
            } else {
                manager.markWorkerLaunchFailure(requestedInfo)
                Result.failure()
            }
        }

        var lastPublishedPercent = -1
        return when (manager.runQueuedDownload(requestedInfo) { percent ->
            if (percent != lastPublishedPercent) {
                lastPublishedPercent = percent
                setProgressAsync(workDataOf(PROGRESS to percent))
                setForegroundAsync(UpdateDownloadNotifications.foregroundInfo(applicationContext, percent, localeStore))
            }
        }) {
            UpdateManager.DownloadRunResult.SUCCESS -> Result.success()
            UpdateManager.DownloadRunResult.RETRY -> if (
                shouldRetryUpdateWork(runAttemptCount, MAX_AUTOMATIC_RETRIES)
            ) {
                Result.retry()
            } else {
                Result.failure()
            }
            UpdateManager.DownloadRunResult.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val PROGRESS = "update_progress"
        private const val MAX_AUTOMATIC_RETRIES = 4
        private const val TAG = "UpdateDownloadWorker"
        private const val INPUT_VERSION = "update_version"
        private const val INPUT_URL = "update_url"
        private const val INPUT_SHA256 = "update_sha256"

        internal fun inputFor(info: UpdateManager.UpdateInfo): Data = workDataOf(
            INPUT_VERSION to info.version,
            INPUT_URL to info.apkUrl,
            INPUT_SHA256 to info.sha256.orEmpty(),
        )
    }

    private fun requestedInfo(): UpdateManager.UpdateInfo? {
        val version = inputData.getString(INPUT_VERSION)?.takeIf { it.isNotBlank() } ?: return null
        val url = inputData.getString(INPUT_URL)?.takeIf { it.isNotBlank() } ?: return null
        return UpdateManager.UpdateInfo(
            version = version,
            notes = "",
            apkUrl = url,
            sha256 = inputData.getString(INPUT_SHA256)?.takeIf { it.isNotBlank() },
        )
    }
}

/** Manifest receiver: unlike a dynamically registered callback, it survives process death. */
class UpdateInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { GlobalContext.get().get<UpdateManager>() }
            .getOrNull()
            ?.handleInstallStatus(intent)
    }
}

private object UpdateDownloadNotifications {
    private const val CHANNEL_ID = "owntv_app_updates"
    private const val NOTIFICATION_ID = 4203

    fun foregroundInfo(context: Context, percent: Int, localeStore: LocaleStore): ForegroundInfo {
        val localized = AppLocale.wrap(context, localeStore.currentTag.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    localized.getString(R.string.update_title),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
        val safePercent = percent.coerceIn(0, 100)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.owntv_notification)
            .setContentTitle(localized.getString(R.string.app_name))
            .setContentText(localized.getString(R.string.update_downloading, safePercent))
            .setProgress(100, safePercent, false)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}

/** User-mediated continuation for PackageInstaller when its status arrives in the background. */
internal object UpdateInstallNotifications {
    private const val CHANNEL_ID = "owntv_app_update_install"
    private const val NOTIFICATION_ID = 4204

    fun show(context: Context, confirmation: Intent, sessionId: Int): Boolean = runCatching {
        val localized = AppLocale.wrap(context, LocaleStore.from(context).currentTag.value)
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return@runCatching false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    localized.getString(R.string.update_title),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { setShowBadge(true) },
            )
            if (manager?.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
                return@runCatching false
            }
        }
        val action = PendingIntent.getActivity(
            context,
            sessionId,
            confirmation,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.owntv_notification)
            .setContentTitle(localized.getString(R.string.app_name))
            .setContentText(localized.getString(R.string.update_available))
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(action)
            .addAction(R.drawable.owntv_notification, localized.getString(R.string.update_now), action)
            .build()
        notifications.notify(NOTIFICATION_ID, notification)
        true
    }.getOrElse {
        Log.w("UpdateInstallNotify", "could not post installer confirmation", it)
        false
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
