package tv.own.owntv.mobile.ui.setup

import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.work.CatalogSyncScheduler

/** A request result, not sync completion: the existing sync tracker reports worker outcomes. */
internal enum class ZenPlayRefreshRequestResult { QUEUED, IN_PROGRESS, NO_SOURCES, FAILED, PARTIAL }

/** Process-owned entry point shared by a real foreground transition and the manual button. */
internal class ZenPlayCatalogRefresh(
    private val loadSources: suspend () -> List<SourceEntity>,
    private val enqueue: suspend (SourceEntity) -> Unit,
) {
    constructor(
        sources: SourceDao,
        settings: SettingsRepository,
        scheduler: CatalogSyncScheduler,
        finalizer: ImportFinalizer,
    ) : this(
        loadSources = { activeProfileSources(settings, sources).first().sources },
        enqueue = { source ->
            val counts = finalizer.contentCounts(source.id)
            scheduler.enqueueSync(
                sourceId = source.id,
                reason = REFRESH_REASON,
                contentTypes = SyncContentTypes.enabledOf(source),
                baseItemCount = counts.channels + counts.movies + counts.series,
                policy = ExistingWorkPolicy.KEEP,
            )
        },
    )

    private val mutex = Mutex()
    private val _isEnqueuing = MutableStateFlow(false)
    val isEnqueuing: StateFlow<Boolean> = _isEnqueuing.asStateFlow()

    /** Uses the current profile and selected playlist. No age threshold, timer or forced pruning. */
    suspend fun refreshNow(): ZenPlayRefreshRequestResult {
        // A request still being prepared has no queue result yet; do not announce false success.
        if (!mutex.tryLock()) return ZenPlayRefreshRequestResult.IN_PROGRESS
        _isEnqueuing.value = true
        return try {
            withContext(Dispatchers.IO) { requestZenPlayCatalogs(loadSources(), enqueue) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Exception text may contain account URLs. The UI receives only the safe result enum.
            ZenPlayRefreshRequestResult.FAILED
        } finally {
            _isEnqueuing.value = false
            mutex.unlock()
        }
    }

    private companion object {
        const val REFRESH_REASON = "zenplay_refresh"
    }
}

/** Every visible enabled playlist is requested, including one that synced a moment ago. */
internal suspend fun requestZenPlayCatalogs(
    sources: List<SourceEntity>,
    enqueue: suspend (SourceEntity) -> Unit,
): ZenPlayRefreshRequestResult {
    val targets = sources.distinctBy { it.id }.filter { SyncContentTypes.enabledFor(it).hasAny }
    if (targets.isEmpty()) return ZenPlayRefreshRequestResult.NO_SOURCES
    var queued = 0
    for (source in targets) {
        try {
            enqueue(source)
            queued++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep requesting independent playlists; one unavailable source must not block the rest.
        }
    }
    return when (queued) {
        0 -> ZenPlayRefreshRequestResult.FAILED
        targets.size -> ZenPlayRefreshRequestResult.QUEUED
        else -> ZenPlayRefreshRequestResult.PARTIAL
    }
}

/** Activity-start transitions have no debounce: rotations and internal activities stay one visit. */
internal class ZenPlayForegroundGate {
    private val started = mutableSetOf<Any>()
    private var configurationHandoff = false

    fun onStarted(activity: Any): Boolean {
        if (!started.add(activity)) return false
        val enteredForeground = started.size == 1 && !configurationHandoff
        configurationHandoff = false
        return enteredForeground
    }

    fun onStopped(activity: Any, changingConfigurations: Boolean) {
        if (!started.remove(activity)) return
        if (started.isEmpty()) configurationHandoff = changingConfigurations
    }

    /** Android may keep a PiP activity STARTED; its later resume is nevertheless a new app visit. */
    fun onPictureInPicture(activity: Any) = onStopped(activity, changingConfigurations = false)
}
