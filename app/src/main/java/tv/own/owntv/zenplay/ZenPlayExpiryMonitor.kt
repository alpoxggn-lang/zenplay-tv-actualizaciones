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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.download.DownloadManager
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.parser.XtAccountAccess
import tv.own.owntv.core.parser.accountAccess
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.SettingsRepository
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.security.MessageDigest
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
    private val downloads: DownloadManager,
    private val profiles: ProfileDao,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ZenPlaySubscriptionState>(ZenPlaySubscriptionState.Loading)
    val state: StateFlow<ZenPlaySubscriptionState> = _state.asStateFlow()
    private var lastAccountIdentity: String? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        ZenPlayExpiryWork.schedule(context)
        scope.launch {
            val everyProfileSource = profiles.observeAll().flatMapLatest { profileList ->
                val flows = profileList.map { profile ->
                    sources.observeSources(profile.id).map { list ->
                        list.filter { it.type == SourceType.XTREAM }.map { source ->
                            ExpirySourceKey(
                                profileId = profile.id,
                                sourceId = source.id,
                                lastSyncAt = source.lastSyncAt,
                                binding = zenPlaySubscriptionBinding(profile.id, source),
                            )
                        }
                    }
                }
                if (flows.isEmpty()) flowOf(emptyList())
                else combine(flows) { snapshots -> snapshots.flatMap { it } }
            }
            combine(
                everyProfileSource,
                settings.activeProfileId,
                settings.defaultSourceId,
            ) { sourceKeys, profileId, activeSourceId ->
                ExpiryRefreshKey(profileId, sourceKeys, activeSourceId)
            }
                .distinctUntilChanged()
                .collectLatest { key ->
                    // Bind notification tokens only to the active UI account. The hash changes for
                    // credential replacement without exposing a URL, username or password.
                    val activeBindings = key.sources
                        .filter { it.profileId == key.profileId }
                        .map { it.binding }
                        .sorted()
                    val identity = "${key.profileId}:${key.activeSourceId}:$activeBindings"
                    if (lastAccountIdentity != null && lastAccountIdentity != identity) clearNoticeTokens()
                    lastAccountIdentity = identity
                    refresh()
                    ZenPlayExpiryWork.checkNow(context)
                }
        }
    }

    suspend fun refresh(): ZenPlaySubscriptionState = mutex.withLock {
        _state.value = ZenPlaySubscriptionState.Loading
        val query = withTimeoutOrNull(15_000L) {
            querySubscription(settings, profiles, sources, xtream)
        }
            ?: SubscriptionQueryResult(ZenPlaySubscriptionState.Unavailable, emptyList())
        val result = query.state
        _state.value = query.state
        val nowMs = System.currentTimeMillis()
        enforceProtectedDownloadEntitlements(
            sources = query.sources,
            nowMs = nowMs,
            applyIfBindingCurrent = { expected, action ->
                sources.withStableSourceIdentity(
                    sourceId = expected.sourceId,
                    validate = { current ->
                        sourceSubscriptionStillCurrent(expected, current, sources)
                    },
                    action = action,
                )
            },
            revokeForSource = { sourceId -> downloads.revokeEntitlementForSource(sourceId) },
            restoreForSource = { sourceId -> downloads.restoreEntitlementForSource(sourceId) },
        )
        if (result != ZenPlaySubscriptionState.Unavailable &&
            !shouldKeepExpiryNotification(result, nowMs)
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
    val sources: List<ExpirySourceKey>,
    val activeSourceId: Long = -1L,
)

private data class ExpirySourceKey(
    val profileId: Long,
    val sourceId: Long,
    val lastSyncAt: Long?,
    val binding: String,
)

private suspend fun querySubscription(
    settings: SettingsRepository,
    profiles: ProfileDao,
    sources: SourceRepository,
    xtream: XtreamClient,
): SubscriptionQueryResult {
    val profileId = settings.activeProfileId.first()
    val sourcesByProfile = profiles.getAllOnce().associate { profile ->
        profile.id to sources.observeSources(profile.id).first()
    }
    // Include inactive profiles so expiry/revocation gates do not depend on which avatar happens to
    // be selected. A shared source is queried only once; each linked profile's exact credential
    // binding rides with the result for the pre-mutation identity check.
    val snapshots = zenPlaySubscriptionSnapshots(sourcesByProfile)
    val responses = coroutineScope {
        snapshots.map { snapshot ->
            async { snapshot to runCatching { xtream.fetchAccountStatus(snapshot.source) } }
        }.awaitAll().toMap()
    }
    val activeId = settings.defaultSourceId.first()
    val activeSnapshots = snapshots.filter { profileId in it.profileBindings }
    val inUse = activeSnapshots.filter { it.source.id == activeId }.ifEmpty { activeSnapshots }
    val uiState = if (profileId < 0L || inUse.isEmpty()) {
        ZenPlaySubscriptionState.NoAccount
    } else {
        classifyZenPlaySubscription(
            hasAccount = true,
            responses = inUse.mapNotNull(responses::get),
        )
    }
    return SubscriptionQueryResult(
        state = uiState,
        sources = responses.map { (snapshot, response) ->
            SourceSubscriptionResult(
                sourceId = snapshot.source.id,
                profileBindings = snapshot.profileBindings,
                state = response.fold(
                    onSuccess = { details ->
                        classifyZenPlaySubscription(
                            hasAccount = true,
                            responses = listOf(Result.success(details)),
                        )
                    },
                    onFailure = { ZenPlaySubscriptionState.Unavailable },
                ),
            )
        },
    )
}

private data class SubscriptionQueryResult(
    val state: ZenPlaySubscriptionState,
    val sources: List<SourceSubscriptionResult>,
)

internal data class SourceSubscriptionResult(
    val sourceId: Long,
    val profileBindings: Map<Long, String>,
    val state: ZenPlaySubscriptionState,
)

internal data class SubscriptionSourceSnapshot(
    val source: SourceEntity,
    val profileBindings: Map<Long, String>,
)

/** One query snapshot per source id, carrying every exact profile/source identity it represented. */
internal fun zenPlaySubscriptionSnapshots(
    sourcesByProfile: Map<Long, List<SourceEntity>>,
): List<SubscriptionSourceSnapshot> {
    val linked = sourcesByProfile.entries.flatMap { (profileId, sourceList) ->
        sourceList.filter { it.type == SourceType.XTREAM }.map { source -> profileId to source }
    }
    return linked.groupBy { it.second.id }.values.map { owners ->
        val source = owners.first().second
        SubscriptionSourceSnapshot(
            source = source,
            // Every binding must describe the exact immutable entity sent to Xtream. Profile flows
            // are collected separately, so a credential rotation could otherwise leave one owner
            // carrying A and another B for the same source id; accepting either binding would let
            // A's response authorize/revoke B. Membership comes from every owner, identity from the
            // single query snapshot only.
            profileBindings = owners.associate { (profileId, _) ->
                profileId to zenPlaySubscriptionBinding(profileId, source)
            },
        )
    }.sortedBy { it.source.id }
}

/** Credential-sensitive hash; raw account data never enters refresh keys or result objects. */
internal fun zenPlaySubscriptionBinding(profileId: Long, source: SourceEntity): String {
    val material = buildString {
        append(profileId)
        append('|').append(source.id)
        append('|').append(source.type.name)
        append('|').append(source.url)
        append('|').append(source.username.orEmpty())
        append('|').append(source.password.orEmpty())
        append('|').append(source.userAgent.orEmpty())
        append('|').append(source.httpReferer.orEmpty())
        append('|').append(source.mac.orEmpty())
        append('|').append(source.stalkerSerialNumber.orEmpty())
        append('|').append(source.stalkerDeviceId.orEmpty())
        append('|').append(source.stalkerDeviceId2.orEmpty())
        append('|').append(source.stalkerSignature.orEmpty())
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private suspend fun sourceSubscriptionStillCurrent(
    expected: SourceSubscriptionResult,
    current: SourceEntity,
    sources: SourceRepository,
): Boolean = expected.profileBindings.any { (profileId, binding) ->
    sources.observeSources(profileId).first().any { it.id == expected.sourceId } &&
        zenPlaySubscriptionBinding(profileId, current) == binding
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

internal fun shouldRemoveProtectedDownloads(
    state: ZenPlaySubscriptionState,
    nowMs: Long,
): Boolean = when (state) {
    is ZenPlaySubscriptionState.Dated -> state.expiryMs <= nowMs
    ZenPlaySubscriptionState.ExpiredWithoutDate,
    ZenPlaySubscriptionState.Revoked,
    -> true
    ZenPlaySubscriptionState.Loading,
    ZenPlaySubscriptionState.Unlimited,
    ZenPlaySubscriptionState.Unavailable,
    ZenPlaySubscriptionState.NoAccount,
    -> false
}

internal suspend fun enforceProtectedDownloadEntitlements(
    sources: List<SourceSubscriptionResult>,
    nowMs: Long,
    applyIfBindingCurrent: suspend (
        expected: SourceSubscriptionResult,
        action: suspend () -> Unit,
    ) -> Boolean,
    revokeForSource: suspend (Long) -> Unit,
    restoreForSource: suspend (Long) -> Unit,
) {
    sources.distinctBy { it.sourceId }.forEach { source ->
        when {
            shouldRemoveProtectedDownloads(source.state, nowMs) -> {
                applyIfBindingCurrent(source) { revokeForSource(source.sourceId) }
            }
            source.state is ZenPlaySubscriptionState.Dated ||
                source.state == ZenPlaySubscriptionState.Unlimited -> {
                applyIfBindingCurrent(source) { restoreForSource(source.sourceId) }
            }
            // Loading/network failure/NoAccount is not proof of either expiry or renewal. Keep the
            // current gate and files exactly as they are until the provider answers conclusively.
            else -> Unit
        }
    }
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
