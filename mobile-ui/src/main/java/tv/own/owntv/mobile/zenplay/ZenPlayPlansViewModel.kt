package tv.own.owntv.mobile.zenplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.setup.SourceImporter
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.setup.ZenPlayAuthResult
import tv.own.owntv.mobile.ui.setup.ZenPlayAuthenticator
import tv.own.owntv.mobile.ui.setup.ZenPlayPlan

/**
 * ZenPlay "Mis planes": Básica, Avanzado and Premium. A plan already signed in is switched to with one
 * tap; one that is not opens a short sign-in, is loaded into the current profile, shared with every
 * other profile, and becomes the plan in use. No signing out, no typing the details again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZenPlayPlansViewModel(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val profileDao: ProfileDao,
    private val importer: SourceImporter,
    private val authenticator: ZenPlayAuthenticator,
    private val scheduler: CatalogSyncScheduler,
    private val finalizer: ImportFinalizer,
    private val epg: ZenPlayEpg,
) : ViewModel() {

    val playlists: StateFlow<List<SourceEntity>> = settings.activeProfileId
        .flatMapLatest { pid -> if (pid < 0) flowOf(emptyList<SourceEntity>()) else sourceDao.observeForProfile(pid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeId: StateFlow<Long> = settings.defaultSourceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1L)

    val progress = importer.progress

    var busy by mutableStateOf(false)
        private set
    var errorRes by mutableStateOf<Int?>(null)
        private set
    /** The plan that was just added or switched to, for the confirmation line. */
    var doneRes by mutableStateOf<Int?>(null)
        private set
    var donePlan by mutableStateOf<ZenPlayPlan?>(null)
        private set

    private var job: Job? = null

    fun clearMessages() { errorRes = null; doneRes = null }

    /** The saved list that belongs to [plan], or null if that plan is not signed in. */
    fun sourceFor(plan: ZenPlayPlan, lists: List<SourceEntity>): SourceEntity? =
        lists.firstOrNull { plan.matchesServer(it.url) }

    fun switchTo(plan: ZenPlayPlan, source: SourceEntity) {
        viewModelScope.launch {
            settings.setDefaultSource(source.id)
            donePlan = plan
            doneRes = R.string.zenplay_plans_switched
            // A plan not refreshed for a while is brought up to date as it comes into use.
            if (System.currentTimeMillis() - (source.lastSyncAt ?: 0L) >= STALE_MS) {
                runCatching {
                    val counts = finalizer.contentCounts(source.id)
                    scheduler.enqueueSync(
                        source.id,
                        reason = "zenplay_plan_switch",
                        contentTypes = SyncContentTypes.enabledOf(source),
                        baseItemCount = counts.channels + counts.movies + counts.series,
                        policy = ExistingWorkPolicy.KEEP,
                    )
                }
            }
        }
    }

    fun addPlan(plan: ZenPlayPlan, planName: String, username: String, password: String) {
        if (busy) return
        val user = username.trim()
        if (user.isBlank() || password.isEmpty()) {
            errorRes = R.string.zenplay_login_missing_fields
            return
        }
        busy = true
        errorRes = null
        doneRes = null
        job = viewModelScope.launch {
            try {
                val auth = authenticator.authenticate(plan.serverUrl, user, password)
                if (auth != ZenPlayAuthResult.ACTIVE) {
                    errorRes = when (auth) {
                        ZenPlayAuthResult.INVALID_CREDENTIALS -> R.string.zenplay_login_invalid
                        ZenPlayAuthResult.EXPIRED -> R.string.zenplay_login_expired
                        ZenPlayAuthResult.UNREACHABLE -> R.string.zenplay_login_unreachable
                        else -> R.string.zenplay_login_invalid_response
                    }
                    return@launch
                }
                val pid = settings.activeProfileId.first()
                importer.reset()
                importer.backgroundHandoff = false
                importer.useProfile(pid)
                importer.xtream(
                    planName, plan.serverUrl, user, password,
                    autoRefresh = PlaylistRefresh(PlaylistAutoRefresh.STARTUP),
                    makeDefault = true,
                )
                val added = (importer.state.value as? SourceImporter.ImportState.Success)?.source
                if (added == null) {
                    errorRes = R.string.zenplay_login_import_failed
                    return@launch
                }
                // Every profile on the device gets the new plan; favourites and history stay per profile.
                profileDao.getAllOnce().forEach { profile ->
                    runCatching { sourceDao.link(ProfileSourceCrossRef(profileId = profile.id, sourceId = added.id)) }
                }
                settings.setDefaultSource(added.id)
                runCatching { epg.ensureFor(added) }
                donePlan = plan
                doneRes = R.string.zenplay_plans_added
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (_: Exception) {
                errorRes = R.string.zenplay_login_import_failed
            } finally {
                importer.useProfile(-1L)
                importer.reset()
                busy = false
            }
        }
    }

    fun cancel() { job?.cancel() }

    private companion object {
        const val STALE_MS = 30 * 60_000L
    }
}

/** Whether [url] (a saved list's server address) is this plan's server, comparing host and port. */
fun ZenPlayPlan.matchesServer(url: String): Boolean {
    fun key(u: String): String? = runCatching {
        val uri = java.net.URI(u.trim())
        val scheme = uri.scheme?.lowercase() ?: "http"
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        "${uri.host?.lowercase()}:$port"
    }.getOrNull()
    val mine = key(serverUrl) ?: return false
    return key(url) == mine
}
