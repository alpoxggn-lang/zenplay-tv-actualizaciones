package tv.own.owntv.mobile.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.content.ContentAccessPolicy
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.download.DownloadManager
import tv.own.owntv.core.download.DownloadItemResolver
import tv.own.owntv.core.download.DownloadStorageInfo
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.ui.screens.library.VodTuner

/**
 * What has been saved for offline watching, and how the queue is getting on.
 *
 * The queue itself is core's — the same worker that feeds the television — so this only reads it,
 * filters out what the profile is not allowed to see, and offers the four buttons a download can
 * need. The one thing computed here is the transfer rate: core records bytes, not speed, so the
 * screen measures it from two readings a second apart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModel(
    private val downloadDao: DownloadDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val settings: SettingsRepository,
    private val downloadManager: DownloadManager,
    private val vodTuner: VodTuner,
    private val contentAccess: ContentAccessPolicy,
    private val itemResolver: DownloadItemResolver,
) : ViewModel() {

    /**
     * The profile's downloads, minus rows whose film or show the user has hidden — hidden is hidden
     * everywhere, Downloads included. The file stays on disk and the row comes back on unhide.
     */
    val downloads: StateFlow<List<DownloadEntity>> = settings.activeProfileId
        .flatMapLatest { pid ->
            if (pid < 0) {
                flowOf(emptyList())
            } else downloadDao.observeForProfile(pid).mapLatest { list ->
                list.filter { canAccess(it, pid) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun canAccess(d: DownloadEntity, profileId: Long): Boolean {
        if (profileId < 0L || d.profileId != profileId) return false
        val resolved = itemResolver.resolve(d) ?: return false
        return when (resolved.mediaType) {
            MediaType.MOVIE -> movieDao.getById(resolved.itemId)?.let { contentAccess.allows(profileId, it) } == true
            MediaType.EPISODE -> seriesDao.getEpisodeById(resolved.itemId)
                ?.let { contentAccess.allows(profileId, it) } == true
            else -> false
        }
    }

    /** Free and total space in ZenPlay's private, encrypted download area. */
    val storage: StateFlow<DownloadStorageInfo?> =
        downloads.map { Unit }
            .mapLatest { downloadManager.storageInfo() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * How fast the queue is moving, in megabits per second, from the growth of the running rows.
     * Zero whenever nothing is running, and after a pause, so a stale figure never lingers.
     */
    val speedMbps: StateFlow<Double> = flow {
        var lastBytes = -1L
        var lastAt = 0L
        while (true) {
            val running = downloads.value.filter { it.status == DownloadStatus.RUNNING }
            val bytes = running.sumOf { it.downloadedBytes }
            val now = System.currentTimeMillis()
            emit(
                if (running.isEmpty() || lastBytes < 0 || now <= lastAt) 0.0
                else (bytes - lastBytes).coerceAtLeast(0L) * 8.0 / (now - lastAt) / 1_000.0,
            )
            lastBytes = if (running.isEmpty()) -1L else bytes
            lastAt = now
            delay(SPEED_SAMPLE_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /**
     * Whether the queue waits for Wi-Fi. It sits with the folder because the two are the only
     * choices this screen has, and a user who opens them is asking one question: where these go and
     * what they cost.
     */
    val wifiOnly: StateFlow<Boolean> = settings.downloadsWifiOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setWifiOnly(on: Boolean) {
        viewModelScope.launch { settings.setDownloadsWifiOnly(on) }
    }

    private val _playing = MutableStateFlow(false)
    /** True once a download has actually started playing, so the screen knows to open the player. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    fun play(download: DownloadEntity) {
        viewModelScope.launch {
            val pid = settings.activeProfileId.first()
            val owned = downloadDao.getByIdForProfile(download.id, pid) ?: return@launch
            if (!canAccess(owned, pid)) return@launch
            if (owned.filePath == null) return@launch
            _playing.value = vodTuner.playDownload(owned.id)
        }
    }

    fun playerOpened() {
        _playing.value = false
    }

    fun retry(download: DownloadEntity) {
        viewModelScope.launch {
            val pid = settings.activeProfileId.first()
            if (canAccess(download, pid)) downloadManager.retry(download)
        }
    }
    fun pause(download: DownloadEntity) = downloadManager.pause(download)
    fun resume(download: DownloadEntity) {
        viewModelScope.launch {
            val pid = settings.activeProfileId.first()
            if (canAccess(download, pid)) downloadManager.resume(download)
        }
    }
    fun delete(download: DownloadEntity) = downloadManager.delete(download)

    private companion object {
        const val SPEED_SAMPLE_MS = 1_000L
    }
}
