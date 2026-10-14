package tv.own.owntv.features.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.BuildConfig
import tv.own.owntv.R
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.i18n.SupportedLocales
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.features.settings.SettingsViewModel
import tv.own.owntv.features.settings.sectionLabelRes
import tv.own.owntv.features.shell.components.AboutDialog
import tv.own.owntv.features.shell.components.GITHUB_REPO
import tv.own.owntv.features.shell.components.LocalSettingsRowTone
import tv.own.owntv.features.shell.components.MonoText
import tv.own.owntv.features.shell.components.PlaybackErrorLogDialog
import tv.own.owntv.features.shell.components.SettingsIconTile
import tv.own.owntv.features.shell.components.SettingsSkin
import tv.own.owntv.features.shell.components.SheetHeader
import tv.own.owntv.features.shell.components.SpineItem
import tv.own.owntv.features.shell.components.TileTone
import tv.own.owntv.player.PlaybackErrorLog
import tv.own.owntv.ui.components.ContentPanelFill
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.displayText
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.format.formatBestDateTime
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.glass
import tv.own.owntv.zenplay.ZenPlaySubscriptionState

/** Which of More's own pages is on screen. [ROOT] is the hub itself. */
private enum class MorePage {
    ROOT,
    FAVORITES,
    HISTORY,
    // Legacy rememberSaveable values: retained for deserialization, never rendered.
    PLANS,
    BACKUP,
    LOCAL_SYNC,
}

private val MorePage.isRetiredZenPlayPage: Boolean
    get() = this == MorePage.PLANS || this == MorePage.BACKUP || this == MorePage.LOCAL_SYNC

/** The rows, in the order the spine shows them. */
private enum class MoreRow {
    PROFILE,
    FAVORITES,
    HISTORY,
    RECORDINGS,
    AGENDA,
    REFRESH,
    SETTINGS,
    // Legacy rememberSaveable values: retained for deserialization, never listed.
    PLANS,
    BACKUP,
    LOCAL_SYNC,
    ERROR_LOG,
    ABOUT,
    LOGOUT,
}

private val MoreRow.isRetiredZenPlayRow: Boolean
    get() = this == MoreRow.PLANS || this == MoreRow.BACKUP || this == MoreRow.LOCAL_SYNC

/**
 * The hub the rail's last item opens — everything that is neither a channel nor a preference.
 *
 * It exists because Settings was the only door. Backup/restore and local device sharing are parked
 * for this release: ZenPlay uses one fixed source, and cross-device sharing belongs to a later
 * update. The internal implementations remain available without exposing a user-facing door.
 *
 * **The shape is Settings' own** (Plan M), and so are the parts: the spine rows are
 * [SpineItem] and the pane is headed by [SheetHeader] — the same composables the Settings root
 * draws, not lookalikes. Two plates, 12 dp apart, each with its own glass fill and border.
 *
 * **The pane never takes focus.** One axis: Up and Down walk the spine, the pane follows, OK opens,
 * Back leaves. Settings' spine always enters its sheet on Right, because there the right-hand plate
 * *is* the destination; here it is a report about a destination elsewhere, so Right would work on
 * some rows and not others. On a remote that reads as a bug, not as an answer.
 *
 * **Focus lands on Settings**, so the most-used destination is rail → More → OK: one extra press
 * over what it used to be, and never a hunt.
 *
 * There is deliberately no Downloads row: offline films and episodes are a phone/tablet feature.
 * There is no Profiles row either, because the profile card at the rail's foot is its single door.
 */
@Composable
fun MoreScreen(
    onOpenSettings: () -> Unit,
    profileName: String,
    planName: String,
    subscriptionState: ZenPlaySubscriptionState,
    onSwitchProfile: () -> Unit,
    onOpenRecordings: () -> Unit,
    onOpenAgenda: () -> Unit,
    onRefresh: () -> Unit,
    onFullscreen: () -> Unit,
    onChildFocused: () -> Unit,
    /** False while the full-screen or docked player owns playback — see [FavoritesScreen]. */
    previewEnabled: Boolean,
    modifier: Modifier = Modifier,
    counts: MoreCountsViewModel = koinViewModel(),
    settingsVm: SettingsViewModel = koinViewModel(),
) {
    var page by rememberSaveable { mutableStateOf(MorePage.ROOT) }
    val activePage = page.takeUnless { it.isRetiredZenPlayPage } ?: MorePage.ROOT
    LaunchedEffect(page) {
        if (page.isRetiredZenPlayPage) page = MorePage.ROOT
    }
    var showAbout by remember { mutableStateOf(false) }
    var showErrorLog by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }
    val favorites by counts.favorites.collectAsStateWithLifecycle()
    val history by counts.history.collectAsStateWithLifecycle()
    val quickPinned by settingsVm.quickPinnedKeys.collectAsStateWithLifecycle()
    val quickPreview = quickPreviewRows(settingsVm)
    val favoriteItems by counts.favoriteItems.collectAsStateWithLifecycle()
    val historyItems by counts.historyItems.collectAsStateWithLifecycle()

    // Each page and each dialog is left by Back, and the row it was opened from is where focus has
    // to land — otherwise leaving Backup drops the user at the top of the list every time.
    val rowFocus = remember { MoreRow.entries.associateWith { FocusRequester() } }
    var returnTo by remember { mutableStateOf(MoreRow.PROFILE) }
    val focusRow: (MoreRow) -> Unit = { returnTo = it }

    // Which row the pane is describing. Focus drives it, the same way Settings' spine selects a
    // group on focus rather than on OK — so walking the list reads the list.
    var selected by rememberSaveable { mutableStateOf(MoreRow.PROFILE) }
    val activeSelection = selected.takeUnless { it.isRetiredZenPlayRow } ?: MoreRow.SETTINGS
    LaunchedEffect(selected) {
        if (selected.isRetiredZenPlayRow) selected = MoreRow.SETTINGS
    }

    when (activePage) {
        MorePage.FAVORITES -> {
            FavoritesScreen(
                onFullscreen = onFullscreen,
                onChildFocused = onChildFocused,
                previewEnabled = previewEnabled,
                onBack = { page = MorePage.ROOT },
                modifier = modifier,
            )
            return
        }
        MorePage.HISTORY -> {
            HistoryScreen(
                onFullscreen = onFullscreen,
                onChildFocused = onChildFocused,
                previewEnabled = previewEnabled,
                onBack = { page = MorePage.ROOT },
                modifier = modifier,
            )
            return
        }
        MorePage.PLANS, MorePage.BACKUP, MorePage.LOCAL_SYNC -> Unit
        MorePage.ROOT -> Unit
    }

    val dialogOpen = showAbout || showErrorLog || showLogout
    // The wait is the whole fix: coming back from a sub-page (Local sync, Backup, Favourites) this
    // effect starts in the same composition that re-creates the spine, so without it the request
    // lands before the rows are placed, fails silently into `runCatching`, and focus drops to the top
    // of the list instead of the row the user left from.
    LaunchedEffect(dialogOpen) {
        if (!dialogOpen) {
            delay(FOCUS_RESTORE_DELAY_MS)
            runCatching { rowFocus.getValue(returnTo).requestFocus() }
        }
    }

    val context = LocalContext.current
    // Read once, here: the spine's badge and the pane both want it, and reading it twice would be
    // two file reads for one answer.
    val logEntries by produceState<List<PlaybackErrorLog.Entry>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { PlaybackErrorLog.read(context) }
    }

    val colors = OwnTVTheme.colors
    val paneShape = SettingsSkin.PaneShape
    val expiryText = when (val expiry = subscriptionState) {
        ZenPlaySubscriptionState.Loading -> stringResource(R.string.zenplay_expiry_loading)
        is ZenPlaySubscriptionState.Dated -> if (expiry.expiryMs <= System.currentTimeMillis()) {
            stringResource(R.string.zenplay_expiry_expired)
        } else {
            stringResource(
                R.string.zenplay_expiry_until,
                formatBestDateTime(context, "dMMMyyyy", expiry.expiryMs),
            )
        }
        ZenPlaySubscriptionState.Unlimited -> stringResource(R.string.zenplay_expiry_unlimited)
        ZenPlaySubscriptionState.ExpiredWithoutDate -> stringResource(R.string.zenplay_expiry_expired)
        ZenPlaySubscriptionState.Revoked -> stringResource(R.string.zenplay_expiry_revoked)
        ZenPlaySubscriptionState.Unavailable -> stringResource(R.string.zenplay_expiry_unavailable)
        ZenPlaySubscriptionState.NoAccount -> stringResource(R.string.zenplay_expiry_no_account)
    }
    val accountSummary = listOf(profileName, planName, expiryText)
        .filter { it.isNotBlank() }
        .joinToString(stringResource(R.string.content_epg_bits_separator))

    // Settings' own outer shell: ONE content panel holding a head that spans both columns and the
    // two plates below it. Without this the plates float on the wallpaper and the screen reads as a
    // different kind of thing from the hub it sits one press away from.
    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel(fillColor = ContentPanelFill)
            // Directional entry from the rail lands on the row the user was last on — Settings does
            // the same with its category column, and it is why one Right press reaches real content.
            .focusProperties {
                onEnter = { runCatching { rowFocus.getValue(returnTo).requestFocus() } }
            }
            .focusGroup()
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
    ) {
        // The panel head, spanning both columns: what this screen is, and what it holds.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.zenplay_my_zenplay),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                text = accountSummary,
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
        // 294 dp is the design width, but at 150% UI Zoom the whole panel is not much wider than
        // that — so it gives way rather than squeezing the pane into a strip. Settings' own rule.
        val spineWidth = minOf(SettingsSkin.SpineWidth, maxWidth * 0.34f)
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- The spine: Settings' own, row for row.
            Column(
                modifier = Modifier
                    .width(spineWidth)
                    .fillMaxHeight()
                    .clip(paneShape)
                    .glass(surface = GlassSurface.CARDS, baseFill = colors.surfaceContainerLow, shape = paneShape)
                    .border(1.dp, colors.outlineVariant, paneShape)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                SpineGroup(stringResource(R.string.zenplay_account_title))
                SpineRow(
                    row = MoreRow.PROFILE,
                    icon = OwnTVIcon.PERSON,
                    title = stringResource(R.string.zenplay_profiles_title),
                    summary = stringResource(R.string.zenplay_profiles_summary),
                    badge = profileName,
                    selected = activeSelection,
                    focus = rowFocus.getValue(MoreRow.PROFILE),
                    onSelected = { selected = it },
                    onClick = { focusRow(MoreRow.PROFILE); onSwitchProfile() },
                )
                SpineGroup(stringResource(R.string.settings_group_data))
                SpineRow(
                    row = MoreRow.FAVORITES,
                    icon = OwnTVIcon.FAVORITE,
                    title = stringResource(R.string.content_category_favorites),
                    summary = stringResource(R.string.more_spine_favorites_summary),
                    badge = favorites.total.toString(),
                    selected = activeSelection,
                    focus = rowFocus.getValue(MoreRow.FAVORITES),
                    onSelected = { selected = it },
                    onClick = { focusRow(MoreRow.FAVORITES); page = MorePage.FAVORITES },
                )
                SpineRow(
                    row = MoreRow.HISTORY,
                    icon = OwnTVIcon.HISTORY,
                    title = stringResource(R.string.content_category_history),
                    summary = stringResource(R.string.more_spine_history_summary),
                    badge = history.total.toString(),
                    selected = activeSelection,
                    focus = rowFocus.getValue(MoreRow.HISTORY),
                    onSelected = { selected = it },
                    onClick = { focusRow(MoreRow.HISTORY); page = MorePage.HISTORY },
                )
                SpineRow(
                    row = MoreRow.RECORDINGS,
                    icon = OwnTVIcon.VIDEO,
                    title = stringResource(R.string.zenplay_recordings_title),
                    summary = stringResource(R.string.zenplay_recordings_summary),
                    badge = "",
                    selected = activeSelection,
                    focus = rowFocus.getValue(MoreRow.RECORDINGS),
                    onSelected = { selected = it },
                    onClick = { focusRow(MoreRow.RECORDINGS); onOpenRecordings() },
                )
                SpineRow(
                    row = MoreRow.AGENDA,
                    icon = OwnTVIcon.CATCHUP,
                    title = stringResource(R.string.zenplay_agenda_title),
                    summary = stringResource(R.string.zenplay_agenda_summary),
                    badge = "",
                    selected = activeSelection,
                    focus = rowFocus.getValue(MoreRow.AGENDA),
                    onSelected = { selected = it },
                    onClick = { focusRow(MoreRow.AGENDA); onOpenAgenda() },
                )
                SpineRow(
                    row = MoreRow.REFRESH,
                    icon = OwnTVIcon.REFRESH,
                    title = stringResource(R.string.zenplay_refresh_label),
                    summary = stringResource(R.string.zenplay_refresh_summary),
                    badge = "",
                    selected = activeSelection,
                    focus = rowFocus.getValue(MoreRow.REFRESH),
                    onSelected = { selected = it },
                    onClick = { focusRow(MoreRow.REFRESH); onRefresh() },
                )

                SpineGroup(stringResource(R.string.settings_app_group))
                SpineRow(
                    row = MoreRow.SETTINGS,
                    icon = OwnTVIcon.SETTINGS,
                    title = stringResource(R.string.common_nav_settings),
                    summary = stringResource(R.string.more_spine_settings_summary),
                    badge = quickPinned.size.toString(),
                    selected = activeSelection,
                    focus = rowFocus.getValue(MoreRow.SETTINGS),
                    onSelected = { selected = it },
                    onClick = { focusRow(MoreRow.SETTINGS); onOpenSettings() },
                )
                SpineGroup(stringResource(R.string.settings_app_group))
                SpineRow(
                    row = MoreRow.ERROR_LOG,
                    icon = OwnTVIcon.WARNING,
                    title = stringResource(R.string.settings_playback_error_log),
                    summary = stringResource(R.string.more_spine_error_log_summary),
                    badge = (logEntries?.size ?: 0).toString(),
                    selected = activeSelection,
                    focus = rowFocus.getValue(MoreRow.ERROR_LOG),
                    onSelected = { selected = it },
                    onClick = { focusRow(MoreRow.ERROR_LOG); showErrorLog = true },
                )
                SpineRow(
                    row = MoreRow.ABOUT,
                    icon = OwnTVIcon.INFO,
                    title = stringResource(R.string.settings_about),
                    summary = stringResource(R.string.more_spine_about_summary),
                    badge = BuildConfig.VERSION_NAME,
                    selected = activeSelection,
                    focus = rowFocus.getValue(MoreRow.ABOUT),
                    onSelected = { selected = it },
                    onClick = { focusRow(MoreRow.ABOUT); showAbout = true },
                )

                // ZenPlay: sign out, so the customer can sign in again with another plan or account.
                SpineGroup(stringResource(R.string.zenplay_logout_group))
                // ZenPlay: "Mis planes" opens from the plan chip in the top bar, so it is not repeated here.
                SpineRow(
                    row = MoreRow.LOGOUT,
                    icon = OwnTVIcon.POWER,
                    title = stringResource(R.string.zenplay_logout_title),
                    summary = stringResource(R.string.zenplay_logout_summary),
                    badge = "",
                    selected = activeSelection,
                    focus = rowFocus.getValue(MoreRow.LOGOUT),
                    onSelected = { selected = it },
                    onClick = { focusRow(MoreRow.LOGOUT); showLogout = true },
                )

                SpineFooter()
            }

            // --- The pane: what is behind the highlighted row. Read-only, and never focusable.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(paneShape)
                    .glass(surface = GlassSurface.CARDS, baseFill = colors.surfaceContainerLow, shape = paneShape)
                    .border(1.dp, colors.outlineVariant, paneShape),
            ) {
                val destination = when (activeSelection) {
                    MoreRow.PROFILE -> stringResource(R.string.zenplay_profiles_title)
                    MoreRow.RECORDINGS -> stringResource(R.string.zenplay_recordings_title)
                    MoreRow.AGENDA -> stringResource(R.string.zenplay_agenda_title)
                    MoreRow.REFRESH -> stringResource(R.string.zenplay_refresh_label)
                    MoreRow.SETTINGS -> stringResource(R.string.common_nav_settings)
                    MoreRow.FAVORITES -> stringResource(R.string.content_category_favorites)
                    MoreRow.HISTORY -> stringResource(R.string.content_category_history)
                    MoreRow.PLANS, MoreRow.BACKUP, MoreRow.LOCAL_SYNC ->
                        stringResource(R.string.common_nav_settings)
                    MoreRow.ERROR_LOG -> stringResource(R.string.settings_playback_error_log)
                    MoreRow.ABOUT -> stringResource(R.string.settings_about)
                    MoreRow.LOGOUT -> stringResource(R.string.zenplay_logout_title)
                }
                // Settings' own sheet header: 18 sp title, 12.5 sp summary, bordered mono tag.
                SheetHeader(
                    title = destination,
                    // The pane takes the LONG description where one exists — the spine's short
                    // summary is short because the spine is 294 dp, not because the app has nothing
                    // more to say.
                    summary = when (activeSelection) {
                        MoreRow.PROFILE -> stringResource(R.string.zenplay_profiles_summary)
                        MoreRow.RECORDINGS -> stringResource(R.string.zenplay_recordings_summary)
                        MoreRow.AGENDA -> stringResource(R.string.zenplay_agenda_summary)
                        MoreRow.REFRESH -> stringResource(R.string.zenplay_refresh_summary)
                        MoreRow.SETTINGS -> stringResource(R.string.more_spine_settings_summary)
                        MoreRow.FAVORITES -> stringResource(R.string.more_pane_favorites_summary)
                        MoreRow.HISTORY -> stringResource(R.string.more_pane_history_summary)
                        MoreRow.PLANS, MoreRow.BACKUP, MoreRow.LOCAL_SYNC ->
                            stringResource(R.string.more_spine_settings_summary)
                        MoreRow.ERROR_LOG -> stringResource(R.string.settings_playback_error_description)
                        MoreRow.ABOUT -> stringResource(R.string.settings_about_description)
                        MoreRow.LOGOUT -> stringResource(R.string.zenplay_logout_description)
                    },
                    tag = when (activeSelection) {
                        MoreRow.PROFILE -> profileName
                        MoreRow.RECORDINGS, MoreRow.AGENDA, MoreRow.REFRESH -> ""
                        MoreRow.SETTINGS ->
                            pluralStringResource(R.plurals.settings_pinned_count, quickPinned.size, quickPinned.size)
                        MoreRow.FAVORITES -> favorites.total.toString()
                        MoreRow.HISTORY -> history.total.toString()
                        MoreRow.PLANS, MoreRow.BACKUP, MoreRow.LOCAL_SYNC -> ""
                        MoreRow.ERROR_LOG -> (logEntries?.size ?: 0).toString()
                        MoreRow.ABOUT -> BuildConfig.VERSION_NAME
                        MoreRow.LOGOUT -> ""
                    },
                    tagHot = false,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    when (activeSelection) {
                        MoreRow.PROFILE -> AccountPane(profileName, planName, expiryText)
                        MoreRow.RECORDINGS -> PaneNote(stringResource(R.string.zenplay_recordings_summary))
                        MoreRow.AGENDA -> PaneNote(stringResource(R.string.zenplay_agenda_summary))
                        MoreRow.REFRESH -> PaneNote(stringResource(R.string.zenplay_refresh_summary))
                        MoreRow.SETTINGS -> SettingsPane(quickPreview)
                        MoreRow.FAVORITES -> CountsPane(favorites, favoriteItems)
                        MoreRow.HISTORY -> CountsPane(history, historyItems)
                        MoreRow.PLANS, MoreRow.BACKUP, MoreRow.LOCAL_SYNC -> SettingsPane(quickPreview)
                        MoreRow.ERROR_LOG -> ErrorLogPane(logEntries)
                        MoreRow.ABOUT -> AboutPane()
                        MoreRow.LOGOUT -> PaneNote(stringResource(R.string.zenplay_logout_warning))
                    }
                }
                PaneHint(destination)
            }
        }
        }
    }

    // The same two dialogs Settings used to open, unchanged — only their door moved.
    if (showAbout) {
        OwnTVPopup(onDismissRequest = { showAbout = false }) {
            AboutDialog(onDismiss = { showAbout = false })
        }
    }
    if (showErrorLog) {
        OwnTVPopup(onDismissRequest = { showErrorLog = false }) {
            PlaybackErrorLogDialog(onDismiss = { showErrorLog = false })
        }
    }
    if (showLogout) {
        OwnTVPopup(onDismissRequest = { showLogout = false }) {
            ZenPlayLogoutDialog(
                onConfirm = { zenPlayLogout(context) },
                onDismiss = { showLogout = false },
            )
        }
    }
}

/** ZenPlay: "¿Cerrar sesión?" — Cancel is focused first so a stray OK never signs anyone out. */
@Composable
private fun ZenPlayLogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = OwnTVTheme.colors
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    Column(
        modifier = Modifier
            .width(460.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(colors.surfaceContainerHigh)
            .padding(24.dp),
    ) {
        Text(
            stringResource(R.string.zenplay_logout_confirm_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.zenplay_logout_warning),
            fontSize = 14.sp,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            tv.own.owntv.ui.components.OwnTVButton(
                label = stringResource(R.string.zenplay_login_cancel),
                onClick = onDismiss,
                style = tv.own.owntv.ui.components.OwnTVButtonStyle.SECONDARY,
                modifier = Modifier.weight(1f).focusRequester(cancelFocus),
            )
            tv.own.owntv.ui.components.OwnTVButton(
                label = stringResource(R.string.zenplay_logout_title),
                onClick = onConfirm,
                icon = OwnTVIcon.POWER,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * ZenPlay sign-out: wipes everything the app stored on this device — the account, profiles, PINs,
 * favourites and settings — exactly as "Clear data" in Android's app settings would. Android closes
 * the app as part of it; the next launch opens on the sign-in screen.
 */
private fun zenPlayLogout(context: android.content.Context) {
    tv.own.owntv.zenplay.ZenPlayExpiryWork.cancel(context)
    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    am.clearApplicationUserData()
}

/** The spine's foot — Settings says "Accent follows your focus"; so does this. */
@Composable
private fun SpineFooter() {
    val colors = OwnTVTheme.colors
    Row(
        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).heightIn(min = 5.dp).clip(RoundedCornerShape(3.dp)).background(colors.primary))
        Text(
            text = stringResource(R.string.settings_spine_foot),
            fontSize = 10.sp,
            color = colors.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A group heading inside the spine. */
@Composable
private fun SpineGroup(text: String) {
    MonoText(
        text.uppercase(),
        9.5.sp,
        OwnTVTheme.colors.outline,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * One spine row. [SpineItem] unchanged apart from the two additive parameters this screen needed —
 * a string badge, because More shows "Off" and a version where Settings shows a count, and a
 * distinct `onClick`, because More's rows navigate where Settings' merely select.
 */
@Composable
private fun SpineRow(
    row: MoreRow,
    icon: OwnTVIcon,
    title: String,
    summary: String,
    badge: String,
    selected: MoreRow,
    focus: FocusRequester,
    onSelected: (MoreRow) -> Unit,
    onClick: () -> Unit,
) = SpineItem(
    label = title,
    summary = summary,
    icon = icon,
    count = 0,
    badge = badge,
    selected = selected == row,
    active = false,
    onFocused = { onSelected(row) },
    onClick = onClick,
    modifier = Modifier.focusRequester(focus),
)

/** Read-only account summary; actions stay on the focused rows so the pane never traps D-pad focus. */
@Composable
private fun ColumnScope.AccountPane(profileName: String, planName: String, expiryText: String) {
    PaneValueRow(
        icon = OwnTVIcon.PERSON,
        label = stringResource(R.string.zenplay_account_profile),
        value = profileName,
    )
    PaneValueRow(
        icon = OwnTVIcon.PLAYLIST,
        label = stringResource(R.string.zenplay_account_plan),
        value = planName,
    )
    PaneValueRow(
        icon = OwnTVIcon.INFO,
        label = stringResource(R.string.zenplay_account_expiry),
        value = expiryText,
    )
}

/**
 * **Settings** — the row the hub opens on, so its pane is what makes More → Settings continuous
 * rather than a jump cut: the toggles the user pinned to Quick, and the groups behind the door.
 */
@Composable
private fun ColumnScope.SettingsPane(quick: List<QuickPreviewRow>) {
    // The user's pinned Quick rows WITH their live values, resolved by `quickPreviewRows` — the six
    // root toggles from their own flows, the Video player rows through `videoQuickBinding`, which is
    // the same resolver the Settings root uses. That is the whole pinnable set, so this is the list,
    // not a sample of it.
    if (quick.isNotEmpty()) {
        PaneLabel(stringResource(R.string.settings_group_quick))
        // Whatever fits, cut at the bottom of the plate — never scrolled, never pushing the groups
        // and the OK hint off the screen. A long pin list simply shows as much as there is room for.
        Column(modifier = Modifier.weight(1f, fill = false).clipToBounds()) {
            quick.forEach { row -> PaneValueRow(icon = row.icon, label = row.label, value = row.value) }
        }
    }
    PaneLabel(stringResource(R.string.more_pane_groups))
    // Settings' nine groups, by their own labels. Quick is group zero and is listed above instead.
    PaneChips(
        listOf(
            stringResource(R.string.settings_group_profile),
            stringResource(R.string.settings_group_sources),
            stringResource(R.string.settings_group_appearance),
            stringResource(R.string.settings_group_layout),
            stringResource(R.string.settings_group_content_metadata),
            stringResource(R.string.settings_group_playback),
            stringResource(R.string.settings_group_network),
            stringResource(R.string.settings_group_app),
        ),
    )
}

/**
 * **Favourites** and **Watch history** — three numbers, and deliberately nothing else.
 *
 * No artwork and no resume bars. Two 62 dp thumbnails are a worse view of the same list than the
 * screen one press away, and reaching for them is what walks into Plan Z defect 6: the browse panes
 * are Activity-scoped on the television, so a pane rendering live favourites would share one
 * view-model instance with the destination. These three numbers are already in `TypeCounts`.
 *
 * A type at zero still shows its number, so an empty Movies reads as "you have not starred a film"
 * rather than as a broken pane.
 */
@Composable
private fun ColumnScope.CountsPane(counts: TypeCounts, items: List<PaneItem>) {
    val colors = OwnTVTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(
            stringResource(R.string.common_nav_live_tv) to counts.live,
            stringResource(R.string.common_nav_movies) to counts.movies,
            stringResource(R.string.common_nav_series) to counts.series,
        ).forEach { (label, n) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SettingsSkin.veil2)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                MonoText(n.toString(), 30.sp, if (n == 0) colors.outline else colors.onSurface)
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
    // The items behind those numbers, in a box of their own — a view, never focusable, so the
    // pane still has exactly one interaction: OK opens the real screen.
    if (items.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SettingsSkin.veil2)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.name,
                        fontSize = 13.sp,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    MonoText(
                        stringResource(
                            when (item.type) {
                                MediaType.LIVE -> R.string.common_nav_live_tv
                                MediaType.MOVIE -> R.string.common_nav_movies
                                else -> R.string.common_nav_series
                            },
                        ),
                        10.sp,
                        colors.outline,
                    )
                }
            }
        }
    }
}

/**
 * **Backup & Restore** — "Last backup: 2 days ago" is the one sentence this screen can say that the
 * user cannot get anywhere else, and until Plan M nothing in either app recorded it.
 */
@Composable
private fun BackupPane(last: SettingsRepository.LastBackup?) {
    val context = LocalContext.current
    if (last != null) {
        PaneValueRow(
            icon = OwnTVIcon.BACKUP,
            label = stringResource(R.string.more_pane_last_backup),
            value = formatBestDateTime(context, "dMMMyyyy", last.at),
        )
        PaneValueRow(
            icon = OwnTVIcon.INFO,
            label = stringResource(R.string.settings_size),
            value = android.text.format.Formatter.formatShortFileSize(context, last.bytes) +
                if (last.encrypted) {
                    stringResource(R.string.content_epg_bits_separator) +
                        stringResource(R.string.more_pane_encrypted)
                } else {
                    ""
                },
        )
        if (last.path.isNotBlank()) {
            PaneValueRow(
                icon = OwnTVIcon.DOWNLOADS,
                label = stringResource(R.string.settings_backup_location),
                value = last.path,
            )
        }
    }
    PaneLabel(stringResource(R.string.more_pane_backup_contents))
    PaneChips(BackupManager.Section.entries.map { stringResource(sectionLabelRes(it)) })
}

/**
 * **Local sync** — the one pane where the state matters more than the contents.
 *
 * "Off · not listening" answers the question a user actually has, *why can't my phone see it*, from
 * the sofa. Read-only on purpose: turning Sync mode on is a deliberate act and stays one, so the pane
 * reports and the screen decides.
 */
@Composable
private fun LocalSyncPane(sync: SyncSnapshot) {
    PaneValueRow(
        icon = OwnTVIcon.REFRESH,
        label = stringResource(
            if (sync.listening) R.string.more_pane_sync_listening else R.string.more_pane_sync_not_listening,
        ),
        value = stringResource(if (sync.listening) R.string.common_on else R.string.common_off),
        hot = sync.listening,
    )
    if (sync.devices.isNotEmpty()) {
        PaneLabel(stringResource(R.string.local_sync_paired_devices))
        sync.devices.forEach { device ->
            PaneValueRow(
                icon = OwnTVIcon.NETWORK,
                label = device.name,
                desc = device.address,
                // The same two branches Local sync's own rows use, so one device cannot read
                // "Not synced yet" on one screen and "1 Jan 1970" on the other.
                value = if (device.lastSyncAt <= 0) {
                    stringResource(R.string.settings_epg_sources_not_synced)
                } else {
                    relativeShort(device.lastSyncAt)
                },
                hot = device.lastSyncAt > 0,
            )
        }
    }
}

/**
 * **Error log** — the newest few, by kind. When it is empty it says so, which is itself the answer
 * to "is anything wrong".
 */
@Composable
private fun ErrorLogPane(entries: List<PlaybackErrorLog.Entry>?) {
    val context = LocalContext.current
    when {
        entries == null -> PaneNote(stringResource(R.string.settings_loading))
        entries.isEmpty() -> PaneNote(stringResource(R.string.settings_no_playback_errors))
        else -> entries.take(PANE_LOG_ROWS).forEach { e ->
            PaneValueRow(
                icon = OwnTVIcon.WARNING,
                label = e.reason?.displayText() ?: e.legacyReason ?: e.engine,
                desc = e.engine,
                value = formatBestDateTime(context, "dMMM", e.atMs),
                // An error is the one kind worth picking out of a list of events.
                hot = e.kind == PlaybackErrorLog.Kind.ERROR,
            )
        }
    }
}

/**
 * **About** — short enough that the pane is very nearly the page. OK still opens the dialog, which is
 * where the full licence and the contributions text scroll.
 */
@Composable
private fun AboutPane() {
    PaneValueRow(
        icon = OwnTVIcon.INFO,
        label = stringResource(R.string.settings_about),
        value = BuildConfig.VERSION_NAME,
    )
    PaneValueRow(
        icon = OwnTVIcon.LANGUAGE,
        label = stringResource(R.string.more_pane_about_languages),
        value = SupportedLocales.all.count { it.packaged }.toString(),
    )
    PaneValueRow(
        icon = OwnTVIcon.INFO,
        label = stringResource(R.string.settings_about_license),
        value = GITHUB_REPO,
    )
}

/** How many log entries the pane shows. Three is what the mockup draws; the dialog has them all. */
private const val PANE_LOG_ROWS = 3

/** Long enough for the spine to be laid out before focus is asked for. `BackupScreen`'s own number. */
private const val FOCUS_RESTORE_DELAY_MS = 50L

/** "2 days ago" — Android's own relative time, so it is localised without a string of ours. */
private fun relativeShort(at: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(
        at,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()

/**
 * A pane row in Settings' own sheet-row shape: the icon tile, the label, an optional description,
 * and the value in mono on the right. Not focusable — see the screen's own note on Right.
 */
@Composable
private fun PaneValueRow(
    icon: OwnTVIcon,
    label: String,
    value: String?,
    desc: String? = null,
    hot: Boolean = false,
) {
    val colors = OwnTVTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsSkin.RowMinHeight)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsIconTile(icon, hot = hot)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (desc != null) {
                Text(
                    text = desc,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = colors.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (value != null) {
            MonoText(value, 13.sp, if (hot) colors.primary else colors.onSurfaceVariant)
        }
    }
}

/** A pane section label, in the spine's eyebrow style so the two plates read as one screen. */
@Composable
private fun PaneLabel(text: String) {
    MonoText(
        text.uppercase(),
        10.sp,
        OwnTVTheme.colors.outline,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

/** The mockup's `.chips` — a wrapped row of small mono pills. */
@Composable
private fun PaneChips(items: List<String>) {
    val colors = OwnTVTheme.colors
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val shape = RoundedCornerShape(999.dp)
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(colors.primary.copy(alpha = 0.14f))
                    .padding(horizontal = 13.dp, vertical = 6.dp),
            ) {
                MonoText(item, 12.sp, colors.primary)
            }
        }
    }
}

/** What a pane says when it has nothing to report. */
@Composable
private fun PaneNote(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = OwnTVTheme.colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

/** `OK — open Settings`. The pane's only instruction, because OK is the pane's only interaction. */
@Composable
private fun PaneHint(destination: String) {
    val colors = OwnTVTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(5.dp)
        Box(
            modifier = Modifier
                .clip(shape)
                .background(SettingsSkin.veil)
                .border(1.dp, colors.outlineVariant, shape)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            MonoText(stringResource(R.string.common_ok), 10.sp, colors.onSurfaceVariant)
        }
        Text(
            text = stringResource(R.string.more_pane_hint_open, destination),
            fontSize = 11.5.sp,
            color = colors.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Settings' own wrapper, so a page opened from here has the tone its row had. */
@Composable
private fun Toned(tone: TileTone, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalSettingsRowTone provides tone, content = content)
