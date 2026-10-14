package tv.own.owntv.mobile.zenplay

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.parser.XtAccountAccess
import tv.own.owntv.core.parser.accountAccess
import tv.own.owntv.core.settings.SettingsRepository

/** ZenPlay: the subscription's expiry for the plan in use, from the panel's `user_info.exp_date`. */
class ZenPlayAccount(
    private val settings: SettingsRepository,
    private val sources: SourceDao,
    private val xtream: XtreamClient,
) {
    private val refreshMutex = Mutex()
    private val _expiry = MutableStateFlow<ZenPlayExpiryState>(ZenPlayExpiryState.Loading)
    val expiry: StateFlow<ZenPlayExpiryState> = _expiry.asStateFlow()

    /** Refreshes once, with a bounded wait. Every path leaves Loading with an explicit result. */
    suspend fun refresh(): ZenPlayExpiryState = refreshMutex.withLock {
        _expiry.value = ZenPlayExpiryState.Loading
        val result = withTimeoutOrNull(15_000L) { loadExpiry() }
            ?: ZenPlayExpiryState.Unavailable
        _expiry.value = result
        result
    }

    private suspend fun loadExpiry(): ZenPlayExpiryState = runCatching {
        val pid = settings.activeProfileId.first()
        if (pid < 0) return@runCatching ZenPlayExpiryState.NoAccount
        val all = sources.observeForProfile(pid).first().filter { it.type == SourceType.XTREAM }
        if (all.isEmpty()) return@runCatching ZenPlayExpiryState.NoAccount
        val activeId = settings.defaultSourceId.first()
        val inUse = all.filter { it.id == activeId }.ifEmpty { all }
        val responses = inUse.map { source -> runCatching { xtream.fetchAccountStatus(source) } }
        classifyZenPlayExpiry(hasAccount = true, responses = responses)
    }.getOrElse { ZenPlayExpiryState.Unavailable }
}

sealed interface ZenPlayExpiryState {
    data object Loading : ZenPlayExpiryState
    data class Dated(val expiryMs: Long) : ZenPlayExpiryState
    data object Unlimited : ZenPlayExpiryState
    data object ExpiredWithoutDate : ZenPlayExpiryState
    data object Revoked : ZenPlayExpiryState
    data object Unavailable : ZenPlayExpiryState
    data object NoAccount : ZenPlayExpiryState
}

/** Pure classification kept separate so unlimited and network failure can never share UI text. */
internal fun classifyZenPlayExpiry(
    hasAccount: Boolean,
    responses: List<Result<XtreamClient.XtAccountDetails>>,
): ZenPlayExpiryState {
    if (!hasAccount || responses.isEmpty()) return ZenPlayExpiryState.NoAccount
    val successful = responses.mapNotNull(Result<XtreamClient.XtAccountDetails>::getOrNull)
    if (successful.isEmpty()) return ZenPlayExpiryState.Unavailable
    if (successful.any { it.accountAccess() == XtAccountAccess.REVOKED }) {
        return ZenPlayExpiryState.Revoked
    }
    if (successful.any { it.accountAccess() == XtAccountAccess.EXPIRED }) {
        return ZenPlayExpiryState.ExpiredWithoutDate
    }
    successful.mapNotNull { it.expiryMs }.minOrNull()?.let { return ZenPlayExpiryState.Dated(it) }
    return ZenPlayExpiryState.Unlimited
}
