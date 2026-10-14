package tv.own.owntv.zenplay

import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.CancellationException
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.sync.work.EpgSyncScheduler

/**
 * ZenPlay: the TV guide (EPG) comes on its own. Every signed-in plan's XMLTV feed — the provider's
 * `xmltv.php`, downloaded over the internet — is registered as a guide source and downloaded in the
 * background, with no "Agregar EPG" step. Already-registered feeds are re-downloaded once they are
 * older than [STALE_MS].
 */
class ZenPlayEpg(
    private val sources: SourceDao,
    private val epgRepository: EpgRepository,
    private val store: EpgSourceStore,
    private val scheduler: EpgSyncScheduler,
    private val epgDao: EpgDao,
) {
    /** Register [source]'s guide (if it has one) and download it now. */
    suspend fun ensureFor(source: SourceEntity, force: Boolean = true) {
        val urls = runCatching { epgRepository.guideUrls(source) }.getOrDefault(emptyList())
        for (url in urls) {
            try {
                val existing = store.getAll().firstOrNull { it.url == url }
                val epg = existing ?: store.add(source.name, url, source.userAgent)
                val stale = System.currentTimeMillis() - (epg.lastSyncAt ?: 0L) >= STALE_MS
                if (existing == null || force || stale) {
                    scheduler.enqueueSync(
                        epg.id,
                        reason = REASON,
                        baseProgrammes = runCatching { epgDao.countForSources(listOf(epg.id)) }.getOrDefault(0),
                        policy = ExistingWorkPolicy.KEEP,
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // One feed that cannot be registered must not stop the others.
            }
        }
    }

    /** Every playlist on the device: missing guides are added, old ones refreshed. */
    suspend fun ensureAll(force: Boolean = false) {
        runCatching { sources.getAllOnce() }.getOrDefault(emptyList()).forEach { ensureFor(it, force) }
    }

    companion object {
        /** The app-wide instance, built from Koin's singletons (the TV app has no module entry for it). */
        val shared: ZenPlayEpg by lazy {
            val k = org.koin.core.context.GlobalContext.get()
            ZenPlayEpg(k.get(), k.get(), k.get(), k.get(), k.get())
        }
        private const val REASON = "zenplay_epg"
        private const val STALE_MS = 12 * 3_600_000L
    }
}
