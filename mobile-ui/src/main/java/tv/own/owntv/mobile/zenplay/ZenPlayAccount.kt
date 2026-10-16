package tv.own.owntv.mobile.zenplay

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.parser.XtAccountAccess
import tv.own.owntv.core.parser.accountAccess
import tv.own.owntv.core.settings.SettingsRepository
import java.security.MessageDigest

/** ZenPlay: the subscription's expiry for the plan in use, from the panel's `user_info.exp_date`. */
class ZenPlayAccount(
    context: Context,
    private val settings: SettingsRepository,
    private val sources: SourceDao,
    private val xtream: XtreamClient,
) {
    private val cache = context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
    private val refreshMutex = Mutex()
    private val _expiry = MutableStateFlow<ZenPlayExpiryState>(ZenPlayExpiryState.Loading)
    val expiry: StateFlow<ZenPlayExpiryState> = _expiry.asStateFlow()

    /** Refreshes once, with a bounded wait. Every path leaves Loading with an explicit result. */
    suspend fun refresh(): ZenPlayExpiryState = refreshMutex.withLock {
        // Select the account once. The binding and the credentials sent to Xtream must describe the
        // very same immutable snapshot; selecting a binding first and then re-reading the database
        // for the request could let account B inherit account A's response during credential edits.
        val snapshot = currentAccountSnapshot()
        val cached = readCachedExpiry(snapshot?.binding)
        if (cached == null) _expiry.value = ZenPlayExpiryState.Loading
        val result = if (snapshot == null) {
            ZenPlayExpiryState.NoAccount
        } else {
            withTimeoutOrNull(15_000L) { loadExpiry(snapshot) }
                ?: ZenPlayExpiryState.Unavailable
        }
        // A response for credentials that were replaced while the request was in flight is not an
        // answer about the account now selected. Never publish or cache it under either identity.
        val currentAfterQuery = currentAccountSnapshot()
        if (currentAfterQuery?.binding != snapshot?.binding) {
            val current = currentAfterQuery
            val currentState = readCachedExpiry(current?.binding) ?: ZenPlayExpiryState.Unavailable
            _expiry.value = currentState
            return@withLock currentState
        }
        // Offline viewing must not depend on a network round-trip at play time. Keep the last
        // provider-confirmed entitlement when the panel is temporarily unreachable; a dated plan
        // still stops exactly at its stored epoch. A positive revoked/expired answer always wins and
        // is cached immediately, so reconnecting cannot resurrect an invalid subscription.
        val effective = if (result == ZenPlayExpiryState.Unavailable) cached ?: result else result
        if (result != ZenPlayExpiryState.Unavailable && result != ZenPlayExpiryState.Loading) {
            persist(snapshot?.binding, result)
        }
        _expiry.value = effective
        effective
    }

    /**
     * Resolve the entitlement for one protected download's actual owner.
     *
     * This path deliberately does not read or mutate [expiry]: that flow belongs to the account
     * selected in the subscription UI, whereas a completed download is permanently bound to its
     * own profile/source pair. M3U and Stalker have no reliable Xtream-style expiry endpoint, so
     * their protected files remain governed by ownership, source existence and profile membership.
     */
    suspend fun protectedPlaybackEntitlement(
        profileId: Long,
        source: SourceEntity,
    ): ZenPlayExpiryState {
        val binding = zenPlaySourceBinding(profileId, source)
        // Source deletion, unlinking, or credential rotation invalidates even an otherwise-current
        // cached result. This check also covers non-Xtream downloads before granting Unlimited.
        if (!sourceBindingStillCurrent(profileId, source.id, binding)) {
            return ZenPlayExpiryState.Unavailable
        }
        if (!requiresXtreamEntitlement(source.type)) return ZenPlayExpiryState.Unlimited
        val cached = readSourceCachedExpiry(binding)
        if (cached != null && allowsProtectedPlayback(cached, System.currentTimeMillis())) {
            return cached
        }
        val result = withTimeoutOrNull(15_000L) {
            runCatching { xtream.fetchAccountStatus(source) }
                .fold(
                    onSuccess = { details ->
                        classifyZenPlayExpiry(
                            hasAccount = true,
                            responses = listOf(Result.success(details)),
                        )
                    },
                    onFailure = { ZenPlayExpiryState.Unavailable },
                )
        } ?: ZenPlayExpiryState.Unavailable
        // The network request used [source]'s credentials. If those credentials or their profile
        // link changed in flight, fail closed rather than persisting/allowing the stale response.
        if (!sourceBindingStillCurrent(profileId, source.id, binding)) {
            return ZenPlayExpiryState.Unavailable
        }
        val effective = if (result == ZenPlayExpiryState.Unavailable) cached ?: result else result
        if (result != ZenPlayExpiryState.Unavailable && result != ZenPlayExpiryState.Loading) {
            persistSource(binding, result)
        }
        return effective
    }

    private suspend fun loadExpiry(snapshot: ZenPlayAccountSnapshot): ZenPlayExpiryState = runCatching {
        val responses = snapshot.sources.map { source -> runCatching { xtream.fetchAccountStatus(source) } }
        classifyZenPlayExpiry(hasAccount = true, responses = responses)
    }.getOrElse { ZenPlayExpiryState.Unavailable }

    private suspend fun currentAccountSnapshot(): ZenPlayAccountSnapshot? {
        val pid = settings.activeProfileId.first().takeIf { it >= 0L } ?: return null
        val all = sources.observeForProfile(pid).first()
        val activeId = settings.defaultSourceId.first()
        return zenPlayAccountSnapshot(pid, all, activeId)
    }

    private suspend fun sourceBindingStillCurrent(
        profileId: Long,
        sourceId: Long,
        expectedBinding: String,
    ): Boolean {
        val current = sources.observeForProfile(profileId).first().firstOrNull { it.id == sourceId }
            ?: return false
        return zenPlaySourceBinding(profileId, current) == expectedBinding
    }

    private fun persist(binding: String?, state: ZenPlayExpiryState) {
        if (binding == null) return
        cache.edit().apply {
            putString(KEY_BINDING, binding)
            when (state) {
                is ZenPlayExpiryState.Dated -> {
                    putString(KEY_KIND, KIND_DATED)
                    putLong(KEY_EXPIRY_MS, state.expiryMs)
                }
                ZenPlayExpiryState.Unlimited -> putString(KEY_KIND, KIND_UNLIMITED).remove(KEY_EXPIRY_MS)
                ZenPlayExpiryState.ExpiredWithoutDate -> putString(KEY_KIND, KIND_EXPIRED).remove(KEY_EXPIRY_MS)
                ZenPlayExpiryState.Revoked -> putString(KEY_KIND, KIND_REVOKED).remove(KEY_EXPIRY_MS)
                ZenPlayExpiryState.NoAccount -> putString(KEY_KIND, KIND_NO_ACCOUNT).remove(KEY_EXPIRY_MS)
                ZenPlayExpiryState.Loading, ZenPlayExpiryState.Unavailable -> return
            }
        }.apply()
    }

    private fun readCachedExpiry(binding: String?): ZenPlayExpiryState? {
        if (binding == null || cache.getString(KEY_BINDING, null) != binding) return null
        return when (cache.getString(KEY_KIND, null)) {
        KIND_DATED -> cache.getLong(KEY_EXPIRY_MS, -1L)
            .takeIf { it > 0L }
            ?.let(ZenPlayExpiryState::Dated)
        KIND_UNLIMITED -> ZenPlayExpiryState.Unlimited
        KIND_EXPIRED -> ZenPlayExpiryState.ExpiredWithoutDate
        KIND_REVOKED -> ZenPlayExpiryState.Revoked
        KIND_NO_ACCOUNT -> ZenPlayExpiryState.NoAccount
            else -> null
        }
    }

    private fun persistSource(binding: String, state: ZenPlayExpiryState) {
        cache.edit().apply {
            when (state) {
                is ZenPlayExpiryState.Dated -> {
                    putString(sourceKey(KEY_KIND, binding), KIND_DATED)
                    putLong(sourceKey(KEY_EXPIRY_MS, binding), state.expiryMs)
                }
                ZenPlayExpiryState.Unlimited -> {
                    putString(sourceKey(KEY_KIND, binding), KIND_UNLIMITED)
                    remove(sourceKey(KEY_EXPIRY_MS, binding))
                }
                ZenPlayExpiryState.ExpiredWithoutDate -> {
                    putString(sourceKey(KEY_KIND, binding), KIND_EXPIRED)
                    remove(sourceKey(KEY_EXPIRY_MS, binding))
                }
                ZenPlayExpiryState.Revoked -> {
                    putString(sourceKey(KEY_KIND, binding), KIND_REVOKED)
                    remove(sourceKey(KEY_EXPIRY_MS, binding))
                }
                ZenPlayExpiryState.NoAccount -> {
                    putString(sourceKey(KEY_KIND, binding), KIND_NO_ACCOUNT)
                    remove(sourceKey(KEY_EXPIRY_MS, binding))
                }
                ZenPlayExpiryState.Loading, ZenPlayExpiryState.Unavailable -> return
            }
        }.apply()
    }

    private fun readSourceCachedExpiry(binding: String): ZenPlayExpiryState? {
        val kind = cache.getString(sourceKey(KEY_KIND, binding), null)
        val scoped = when (kind) {
            KIND_DATED -> cache.getLong(sourceKey(KEY_EXPIRY_MS, binding), -1L)
                .takeIf { it > 0L }
                ?.let(ZenPlayExpiryState::Dated)
            KIND_UNLIMITED -> ZenPlayExpiryState.Unlimited
            KIND_EXPIRED -> ZenPlayExpiryState.ExpiredWithoutDate
            KIND_REVOKED -> ZenPlayExpiryState.Revoked
            KIND_NO_ACCOUNT -> ZenPlayExpiryState.NoAccount
            else -> null
        }
        // Read the old single-account slot as a migration fallback only when its binding is exact.
        return scoped ?: readCachedExpiry(binding)
    }

    private fun sourceKey(key: String, binding: String): String = "source_${key}_$binding"

    private companion object {
        const val CACHE_NAME = "zenplay_entitlement"
        const val KEY_KIND = "kind"
        const val KEY_BINDING = "binding"
        const val KEY_EXPIRY_MS = "expiry_ms"
        const val KIND_DATED = "dated"
        const val KIND_UNLIMITED = "unlimited"
        const val KIND_EXPIRED = "expired"
        const val KIND_REVOKED = "revoked"
        const val KIND_NO_ACCOUNT = "no_account"
    }
}

internal fun requiresXtreamEntitlement(type: SourceType): Boolean = type == SourceType.XTREAM

internal data class ZenPlayAccountSnapshot(
    val profileId: Long,
    val sources: List<SourceEntity>,
    val binding: String,
)

/** Pure snapshot selection shared by refresh and its race tests. */
internal fun zenPlayAccountSnapshot(
    profileId: Long,
    allSources: List<SourceEntity>,
    activeSourceId: Long,
): ZenPlayAccountSnapshot? {
    if (profileId < 0L) return null
    val xtream = allSources.filter { it.type == SourceType.XTREAM }
    if (xtream.isEmpty()) return null
    val selected = xtream.filter { it.id == activeSourceId }.ifEmpty { xtream }.toList()
    return ZenPlayAccountSnapshot(
        profileId = profileId,
        sources = selected,
        binding = zenPlaySourcesBinding(profileId, selected),
    )
}

/** Credential-sensitive, non-reversible identity used by the per-source entitlement cache. */
internal fun zenPlaySourceBinding(profileId: Long, source: SourceEntity): String =
    zenPlaySourcesBinding(profileId, listOf(source))

internal fun zenPlaySourcesBinding(profileId: Long, sources: List<SourceEntity>): String {
    val material = buildString {
        append(profileId)
        sources.sortedBy { it.id }.forEach { source ->
            append('|').append(source.id)
            append('|').append(source.type.name)
            append('|').append(source.url)
            append('|').append(source.username.orEmpty())
            // Only the digest is stored. Including the credential prevents an account replacement
            // on the same URL/username from inheriting the previous account's offline entitlement.
            append('|').append(source.password.orEmpty())
            append('|').append(source.userAgent.orEmpty())
            append('|').append(source.httpReferer.orEmpty())
            append('|').append(source.mac.orEmpty())
            append('|').append(source.stalkerSerialNumber.orEmpty())
            append('|').append(source.stalkerDeviceId.orEmpty())
            append('|').append(source.stalkerDeviceId2.orEmpty())
            append('|').append(source.stalkerSignature.orEmpty())
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
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

/** Pure entitlement rule used by protected downloads and covered without Android/network state. */
internal fun allowsProtectedPlayback(state: ZenPlayExpiryState, nowMs: Long): Boolean = when (state) {
    is ZenPlayExpiryState.Dated -> state.expiryMs > nowMs
    ZenPlayExpiryState.Unlimited -> true
    ZenPlayExpiryState.Loading,
    ZenPlayExpiryState.ExpiredWithoutDate,
    ZenPlayExpiryState.Revoked,
    ZenPlayExpiryState.Unavailable,
    ZenPlayExpiryState.NoAccount,
    -> false
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
