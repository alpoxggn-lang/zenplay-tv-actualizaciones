package tv.own.owntv.mobile.zenplay

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.settings.SettingsRepository

/** ZenPlay: the subscription's expiry for the plan in use, from the panel's `user_info.exp_date`. */
class ZenPlayAccount(
    private val settings: SettingsRepository,
    private val sources: SourceDao,
    private val xtream: XtreamClient,
) {
    /** Epoch ms, or null when unknown/unlimited. With "all playlists" the soonest date wins. */
    suspend fun expiryMs(): Long? = runCatching {
        val pid = settings.activeProfileId.first()
        if (pid < 0) return@runCatching null
        val all = sources.observeForProfile(pid).first().filter { it.type == SourceType.XTREAM }
        val activeId = settings.defaultSourceId.first()
        val inUse = all.filter { it.id == activeId }.ifEmpty { all }
        inUse.mapNotNull { runCatching { xtream.accountExpiryMs(it) }.getOrNull() }.minOrNull()
    }.getOrNull()
}
