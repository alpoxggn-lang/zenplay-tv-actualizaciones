package tv.own.owntv.mobile.ui.screens.downloads

import tv.own.owntv.mobile.ui.components.MobileIcons
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.storage.MediaFolders
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.FilterChipRow
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.mobile.ui.components.SectionHeader
import tv.own.owntv.mobile.ui.components.SettingRow
import tv.own.owntv.mobile.ui.theme.MobileDimens
import java.text.NumberFormat

/**
 * Films and episodes saved for watching with the network off.
 *
 * Three chips instead of the television's four headings — a phone list is scrolled, not scanned, and
 * a user opening this screen is looking for one of three things: what is coming down now, what is
 * ready, or what went wrong. Anything still queued counts as coming down.
 */
@Composable
fun DownloadsScreen(
    onPlayerOpened: () -> Unit,
    modifier: Modifier = Modifier,
    vm: DownloadsViewModel = koinViewModel(),
) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val speed by vm.speedMbps.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()

    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(DownloadsTab.MOVIES) }
    var menuFor by remember { mutableStateOf<DownloadEntity?>(null) }
    var optionsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(playing) {
        if (playing) {
            vm.playerOpened()
            onPlayerOpened()
        }
    }

    val shown = downloads.filter { tab.holds(it.mediaType) }
    // The statuses, now headings rather than chips, in the order the television lists them.
    val sections = DownloadSection.entries
        .map { section -> section to shown.filter { section.holds(it.status) } }
        .filter { it.second.isNotEmpty() }

    Column(modifier.fillMaxSize()) {
        StorageHeader(
            storage = storage,
            speedMbps = speed,
            onOpenOptions = { optionsSheet = true },
        )
        FilterChipRow(
            labels = DownloadsTab.entries.map { stringResource(it.labelRes) },
            selectedIndex = DownloadsTab.entries.indexOf(tab),
            onSelect = { tab = DownloadsTab.entries[it] },
        )

        if (shown.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(MobileDimens.GapLarge),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.content_downloads_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                sections.forEach { (section, items) ->
                    item(key = "header_${section.name}") {
                        SectionHeader(title = stringResource(section.labelRes))
                    }
                    items(items, key = { it.id }) { download ->
                        DownloadRow(
                            download = download,
                            onClick = {
                                if (download.status == DownloadStatus.COMPLETED) vm.play(download)
                                else menuFor = download
                            },
                            onLongClick = { menuFor = download },
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { download ->
        DownloadMenu(
            download = download,
            vm = vm,
            onDismiss = { menuFor = null },
        )
    }

    if (optionsSheet) {
        DownloadOptionsSheet(
            wifiOnly = wifiOnly,
            onWifiOnly = vm::setWifiOnly,
            onDismiss = { optionsSheet = false },
        )
    }
}

/**
 * The three things this screen keeps, in the order Favourites and History use — and the same three
 * the television shows.
 *
 * They used to be Active / Completed / Failed. A status is not a *kind* of thing: it changes on its
 * own while you watch, so the chip you were looking at empties and the item you were following moves
 * to another one. The statuses are still there, as headings down the list, where they can all be
 * seen at once. Live TV holds recordings: a recording is a download of a live channel.
 */
private enum class DownloadSection(val labelRes: Int) {
    ACTIVE(R.string.content_downloads_active),
    COMPLETED(R.string.content_downloads_completed_group),
    FAILED(R.string.content_downloads_failed_group),
    ;

    /** Queued counts as active: from the user's side it is a download that has not arrived yet. */
    fun holds(status: DownloadStatus): Boolean = when (this) {
        ACTIVE -> status == DownloadStatus.RUNNING || status == DownloadStatus.PAUSED ||
            status == DownloadStatus.QUEUED
        COMPLETED -> status == DownloadStatus.COMPLETED
        FAILED -> status == DownloadStatus.FAILED
    }
}

private enum class DownloadsTab(val labelRes: Int) {
    MOVIES(R.string.common_nav_movies),
    SERIES(R.string.common_nav_series),
    ;

    /**
     * The rule is core's, not this screen's — an EPISODE belongs under Series, and writing that out
     * here is what hid every episode download from this list.
     */
    fun holds(type: MediaType): Boolean = when (this) {
        MOVIES -> MediaFolders.folderFor(type) == MediaFolders.MOVIES
        SERIES -> MediaFolders.folderFor(type) == MediaFolders.SERIES
    }
}

@Composable
private fun StorageHeader(
    storage: tv.own.owntv.core.download.DownloadStorageInfo?,
    speedMbps: Double,
    onOpenOptions: () -> Unit,
) {
    val unknown = stringResource(R.string.content_downloads_unknown_size)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                start = MobileDimens.ScreenPaddingH,
                end = MobileDimens.GapSmall,
                top = MobileDimens.GapSmall,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.content_downloads_storage),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = storage?.let {
                    stringResource(
                        R.string.content_downloads_storage_free,
                        gigabytes(it.freeBytes, unknown),
                        gigabytes(it.totalBytes, unknown),
                    )
                } ?: unknown,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MobileDimens.GapSmall),
            )
            if (speedMbps > 0.0) {
                Text(
                    text = stringResource(R.string.player_stream_mbps, decimal(speedMbps)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The screen's only settings control, and it lives here rather than in the top bar:
            // there used to be a gear up there *and* a folder glyph down here, two buttons for two
            // halves of one question. Not a folder glyph either — what it opens is the folder list
            // itself, and a folder that opens folders says nothing.
            IconButton(onClick = onOpenOptions) {
                Icon(
                    imageVector = MobileIcons.Tune,
                    contentDescription = stringResource(R.string.content_downloads_options),
                    // Without a tint this inherits a content colour meant for a filled button and
                    // comes out black on the dark card.
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        storage?.let {
            LinearProgressIndicator(
                progress = { it.usedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = MobileDimens.GapSmall, bottom = MobileDimens.GapSmall),
            )
        }
        Text(
            text = stringResource(R.string.content_downloads_protected_storage),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = MobileDimens.GapSmall, bottom = MobileDimens.GapSmall),
        )
    }
}

@Composable
private fun DownloadRow(download: DownloadEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    val unknown = stringResource(R.string.content_downloads_unknown_size)
    val fraction = if (download.totalBytes > 0) {
        (download.downloadedBytes.toFloat() / download.totalBytes).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column {
        MobileListRow(
            title = download.title,
            subtitle = statusLine(download, unknown),
            subtitleMaxLines = 1,
            leading = {
                Icon(
                    imageVector = MobileIcons.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailing = {
                if (download.status == DownloadStatus.COMPLETED) {
                    Icon(
                        imageVector = MobileIcons.PlayArrow,
                        contentDescription = stringResource(R.string.content_downloads_play),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            onClick = onClick,
            onLongClick = onLongClick,
        )
        if (download.status == DownloadStatus.RUNNING || download.status == DownloadStatus.PAUSED) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MobileDimens.ScreenPaddingH),
            )
        }
    }
}

/** The second line of a row: where it got to, or why it stopped. */
@Composable
private fun statusLine(download: DownloadEntity, unknown: String): String = when (download.status) {
    DownloadStatus.COMPLETED -> stringResource(
        R.string.content_downloads_completed,
        megabytes(download.totalBytes, unknown),
    )
    DownloadStatus.FAILED -> stringResource(R.string.content_downloads_failure_message)
    DownloadStatus.QUEUED -> stringResource(R.string.content_downloads_queued)
    else -> if (download.totalBytes > 0) {
        stringResource(
            R.string.content_downloads_progress,
            ((download.downloadedBytes.toFloat() / download.totalBytes).coerceIn(0f, 1f) * 100).toInt(),
            megabytes(download.downloadedBytes, unknown),
            megabytes(download.totalBytes, unknown),
        )
    } else {
        stringResource(R.string.content_downloads_progress_unknown, megabytes(download.downloadedBytes, unknown))
    }
}

@Composable
private fun DownloadMenu(
    download: DownloadEntity,
    vm: DownloadsViewModel,
    onDismiss: () -> Unit,
) {
    MobileBottomSheet(onDismissRequest = onDismiss, title = download.title) {
        when (download.status) {
            DownloadStatus.COMPLETED -> {
                MenuRow(R.string.content_downloads_play, MobileIcons.PlayArrow) {
                    vm.play(download); onDismiss()
                }
            }
            DownloadStatus.FAILED -> MenuRow(R.string.common_retry, MobileIcons.Refresh) {
                vm.retry(download); onDismiss()
            }
            DownloadStatus.PAUSED -> MenuRow(R.string.common_resume, MobileIcons.PlayArrow) {
                vm.resume(download); onDismiss()
            }
            else -> MenuRow(R.string.content_downloads_pause, MobileIcons.Pause) {
                vm.pause(download); onDismiss()
            }
        }
        MenuRow(R.string.common_delete, MobileIcons.Delete, destructive = true) {
            vm.delete(download); onDismiss()
        }
    }
}

/** Protected downloads stay in private app storage; only the Wi-Fi policy is user-configurable. */
@Composable
private fun DownloadOptionsSheet(
    wifiOnly: Boolean,
    onWifiOnly: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    MobileBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.content_downloads_options),
    ) {
        MobileListRow(
            title = stringResource(R.string.content_downloads_protected_title),
            subtitle = stringResource(R.string.content_downloads_protected_description),
            subtitleMaxLines = 3,
            leading = {
                Icon(
                    imageVector = MobileIcons.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
        SettingRow(
            title = stringResource(R.string.settings_downloads_wifi_only),
            subtitle = stringResource(R.string.settings_downloads_wifi_only_description),
            checked = wifiOnly,
            onCheckedChange = onWifiOnly,
        )
    }
}

@Composable
private fun MenuRow(
    labelRes: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    MobileListRow(
        title = stringResource(labelRes),
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = onClick,
    )
}

private fun gigabytes(bytes: Long, unknown: String): String =
    if (bytes <= 0) unknown else decimal(bytes / 1_073_741_824.0)

/**
 * A download's size, with its unit. `common_size_mb` carries the unit because a number on its own is
 * not a size — the completed row read "233,8 downloaded", which says nothing at all.
 */
@Composable
private fun megabytes(bytes: Long, unknown: String): String =
    if (bytes <= 0) unknown else stringResource(R.string.common_size_mb, decimal(bytes / 1_048_576.0))

private fun decimal(value: Double): String = NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
}.format(value)
