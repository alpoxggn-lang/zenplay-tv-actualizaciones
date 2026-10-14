package tv.own.owntv.zenplay

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import tv.own.owntv.MainActivity
import tv.own.owntv.R
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.parser.XtAccountAccess
import tv.own.owntv.core.parser.accountAccess
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.SettingsRepository
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

sealed interface ZenPlaySubscriptionState {
    data object Loading : ZenPlaySubscriptionState
    data class Dated(val expiryMs: Long) : ZenPlaySubscriptionState
    data object Unlimited : ZenPlaySubscriptionState
    data object ExpiredWithoutDate : ZenPlaySubscriptionState
    data object Revoked : ZenPlaySubscriptionState
    data object Unavailable : ZenPlaySubscriptionState
    data object NoAccount : ZenPlaySubscriptionState
}

/** One account-status authority for TV, Fire TV, phone and tablet in the Universal process. */
class ZenPlayExpiryMonitor(
    private val context: Context,
    private val settings: SettingsRepository,
    private val sources: SourceRepository,
    private val xtream: XtreamClient,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ZenPlaySubscriptionState>(ZenPlaySubscriptionState.Loading)
    val state: StateFlow<ZenPlaySubscriptionState> = _state.asStateFlow()
    private var lastAccountIdentity: String? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        ZenPlayExpiryWork.schedule(context)
        scope.launch {
            settings.activeProfileId
                .flatMapLatest { profileId ->
                    if (profileId < 0) flowOf(ExpiryRefreshKey(profileId, emptyList()))
                    else sources.observeSources(profileId).map { list ->
                        ExpiryRefreshKey(profileId, list.map { source -> source.id to source.lastSyncAt })
                    }
                }
                .combine(settings.defaultSourceId) { key, active -> key.copy(activeSourceId = active) }
                .distinctUntilChanged()
                .collectLatest { key ->
                    // IDs are enough to separate accounts and never expose URL/user/password.
                    // lastSyncAt still triggers a refresh, but does not reset the once-per-day token.
                    val identity = "${key.profileId}:${key.activeSourceId}:${key.sources.map { it.first }.sorted()}"
                    if (lastAccountIdentity != null && lastAccountIdentity != identity) clearNoticeTokens()
                    lastAccountIdentity = identity
                    refresh()
                    ZenPlayExpiryWork.checkNow(context)
                }
        }
    }

    suspend fun refresh(): ZenPlaySubscriptionState = mutex.withLock {
        _state.value = ZenPlaySubscriptionState.Loading
        val result = withTimeoutOrNull(15_000L) { querySubscription(settings, sources, xtream) }
            ?: ZenPlaySubscriptionState.Unavailable
        _state.value = result
        if (result != ZenPlaySubscriptionState.Unavailable &&
            !shouldKeepExpiryNotification(result, System.currentTimeMillis())
        ) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        }
        result
    }

    fun shouldShowInApp(expiryMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val days = daysUntilExpiry(nowMs, expiryMs)
        if (days !in 0L..1L) return false
        return preferences().getString(KEY_IN_APP_TOKEN, null) != expiryToken(expiryMs, days)
    }

    fun markInAppShown(expiryMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val days = daysUntilExpiry(nowMs, expiryMs)
        preferences().edit().putString(KEY_IN_APP_TOKEN, expiryToken(expiryMs, days)).apply()
    }

    private fun preferences() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clearNoticeTokens() {
        preferences().edit().clear().apply()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
    }
}

private data class ExpiryRefreshKey(
    val profileId: Long,
    val sources: List<Pair<Long, Long?>>,
    val activeSourceId: Long = -1L,
)

private suspend fun querySubscription(
    settings: SettingsRepository,
    sources: SourceRepository,
    xtream: XtreamClient,
): ZenPlaySubscriptionState {
    val profileId = settings.activeProfileId.first()
    if (profileId < 0) return ZenPlaySubscriptionState.NoAccount
    val all = sources.observeSources(profileId).first().filter { it.type == SourceType.XTREAM }
    if (all.isEmpty()) return ZenPlaySubscriptionState.NoAccount
    val activeId = settings.defaultSourceId.first()
    val inUse = all.filter { it.id == activeId }.ifEmpty { all }
    val responses = inUse.map { source -> runCatching { xtream.fetchAccountStatus(source) } }
    return classifyZenPlaySubscription(hasAccount = true, responses = responses)
}

internal fun classifyZenPlaySubscription(
    hasAccount: Boolean,
    responses: List<Result<XtreamClient.XtAccountDetails>>,
): ZenPlaySubscriptionState {
    if (!hasAccount || responses.isEmpty()) return ZenPlaySubscriptionState.NoAccount
    val successful = responses.mapNotNull(Result<XtreamClient.XtAccountDetails>::getOrNull)
    if (successful.isEmpty()) return ZenPlaySubscriptionState.Unavailable
    if (successful.any { it.accountAccess() == XtAccountAccess.REVOKED }) {
        return ZenPlaySubscriptionState.Revoked
    }
    if (successful.any { it.accountAccess() == XtAccountAccess.EXPIRED }) {
        return ZenPlaySubscriptionState.ExpiredWithoutDate
    }
    successful.mapNotNull { it.expiryMs }.minOrNull()?.let {
        return ZenPlaySubscriptionState.Dated(it)
    }
    return ZenPlaySubscriptionState.Unlimited
}

internal fun shouldKeepExpiryNotification(
    state: ZenPlaySubscriptionState,
    nowMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Boolean {
    val dated = state as? ZenPlaySubscriptionState.Dated ?: return false
    return daysUntilExpiry(nowMs, dated.expiryMs, zoneId) in 0L..1L
}

internal fun daysUntilExpiry(
    nowMs: Long,
    expiryMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long {
    val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
    val expiryDay = Instant.ofEpochMilli(expiryMs).atZone(zoneId).toLocalDate()
    return ChronoUnit.DAYS.between(today, expiryDay)
}

private fun expiryToken(expiryMs: Long, days: Long): String = "$expiryMs:$days"

object ZenPlayExpiryWork {
    private const val PERIODIC = "zenplay-subscription-expiry-periodic"
    private const val IMMEDIATE = "zenplay-subscription-expiry-now"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ZenPlayExpiryWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ZenPlayExpiryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(IMMEDIATE)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
    }
}

class ZenPlayExpiryWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull() ?: return Result.retry()
        val monitor = runCatching { koin.get<ZenPlayExpiryMonitor>() }.getOrNull() ?: return Result.retry()
        val state = monitor.refresh()
        if (state is ZenPlaySubscriptionState.Unavailable) return Result.retry()
        val dated = state as? ZenPlaySubscriptionState.Dated ?: return Result.success()
        val days = daysUntilExpiry(System.currentTimeMillis(), dated.expiryMs)
        if (days !in 0L..1L) return Result.success()

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = expiryToken(dated.expiryMs, days)
        if (prefs.getString(KEY_NOTIFICATION_TOKEN, null) == token) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    applicationContext.getString(R.string.zenplay_expiry_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?: Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val message = applicationContext.getString(
            if (days == 1L) R.string.zenplay_expiry_tomorrow else R.string.zenplay_expiry_today,
        )
        NotificationManagerCompat.from(applicationContext).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.zenplay_notification)
                .setContentTitle(applicationContext.getString(R.string.app_name))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
        prefs.edit().putString(KEY_NOTIFICATION_TOKEN, token).apply()
        return Result.success()
    }
}

private const val PREFS = "zenplay_subscription_notices"
private const val KEY_NOTIFICATION_TOKEN = "system_token"
private const val KEY_IN_APP_TOKEN = "in_app_token"
private const val CHANNEL = "zenplay_subscription"
private const val NOTIFICATION_ID = 0x5A31
