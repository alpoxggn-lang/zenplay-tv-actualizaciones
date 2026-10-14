package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.profile.ProfileAuthorization
import tv.own.owntv.core.profile.ProfileManagementAction
import tv.own.owntv.core.profile.ProfileManagementRequirement
import tv.own.owntv.core.profile.ProfilePinPurpose
import tv.own.owntv.core.profile.canDeleteProfile
import tv.own.owntv.features.profiles.PinDialog
import tv.own.owntv.features.profiles.ProfileEditorDialog
import tv.own.owntv.features.profiles.ProfilesViewModel
import tv.own.owntv.ui.components.OwnTVAvatar
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.OwnTVTheme

private data class PendingProtectedProfileAction(
    val target: ProfileEntity?,
    val action: ProfileManagementAction,
    val pinProfile: ProfileEntity,
    val pinPurpose: ProfilePinPurpose,
)

/** Phase 13 — create / edit / delete viewer profiles. */
@Composable
fun ManageProfilesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: ProfilesViewModel = koinViewModel()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()
    val defaultProfileName = stringResource(R.string.profiles_default_name)
    val colors = OwnTVTheme.colors

    var editing by remember { mutableStateOf<ProfileEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ProfileEntity?>(null) }
    var pendingProtectedAction by remember { mutableStateOf<PendingProtectedProfileAction?>(null) }
    var pinError by remember { mutableStateOf(false) }
    var creatingAuthorization by remember { mutableStateOf<ProfileAuthorization?>(null) }
    var editingAuthorization by remember { mutableStateOf<ProfileAuthorization?>(null) }
    var deleteAuthorization by remember { mutableStateOf<ProfileAuthorization?>(null) }
    var managementFailure by remember { mutableStateOf(false) }
    var managementChanged by remember { mutableStateOf(false) }
    val addFocus = remember { FocusRequester() }

    fun perform(
        action: ProfileManagementAction,
        profile: ProfileEntity?,
        authorization: ProfileAuthorization?,
    ) {
        when (action) {
            ProfileManagementAction.CREATE -> {
                creatingAuthorization = authorization
                creating = true
            }
            ProfileManagementAction.EDIT -> profile?.let {
                editingAuthorization = authorization
                editing = it
            }
            ProfileManagementAction.DELETE -> profile?.let {
                if (canDeleteProfile(profiles.size, it.id, activeProfileId)) {
                    deleteAuthorization = authorization
                    confirmDelete = it
                }
            }
            ProfileManagementAction.SWITCH -> Unit
        }
    }

    fun request(action: ProfileManagementAction, profile: ProfileEntity? = null) {
        vm.managementRequirement(action, profile) { requirement ->
            when (requirement) {
                ProfileManagementRequirement.Allowed -> perform(action, profile, null)
                is ProfileManagementRequirement.PinRequired -> {
                    pinError = false
                    pendingProtectedAction = PendingProtectedProfileAction(
                        target = profile,
                        action = action,
                        pinProfile = requirement.profile,
                        pinPurpose = requirement.purpose,
                    )
                }
                is ProfileManagementRequirement.Blocked -> managementFailure = true
            }
        }
    }

    // Per-row focus restore (mirrors ManageSourcesScreen / MoviesScreen): after create/edit/delete
    // closes, focus lands back INSIDE the list — on the acted-on row if it survived, else the nearest
    // neighbour, else the first row, else "Add Profile" (empty list). The previous version always
    // refocused "Add Profile", which threw focus out of the menu.
    var contextId by remember { mutableStateOf<Long?>(null) }
    var contextIndex by remember { mutableStateOf(-1) }
    val contextFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }

    LaunchedEffect(editing, creating, confirmDelete) {
        if (editing != null || creating || confirmDelete != null) return@LaunchedEffect
        kotlinx.coroutines.delay(50) // let the screen lay out after the tab swap before grabbing focus
        val targetId = contextId
        if (targetId != null && profiles.any { it.id == targetId }) {
            runCatching { contextFocus.requestFocus() }
        } else if (profiles.isNotEmpty()) {
            runCatching { firstRowFocus.requestFocus() }
        } else {
            runCatching { addFocus.requestFocus() }
        }
    }

    // When a deleted profile vanishes from `profiles`, move focus to the nearest surviving neighbour
    // (same slot, else last row) instead of escaping the list.
    LaunchedEffect(profiles) {
        val targetId = contextId ?: return@LaunchedEffect
        if (profiles.any { it.id == targetId }) return@LaunchedEffect
        withFrameNanos { }
        if (profiles.isEmpty()) {
            contextId = null; contextIndex = -1
            runCatching { addFocus.requestFocus() }
            return@LaunchedEffect
        }
        val neighbor = profiles.getOrNull(contextIndex.coerceAtLeast(0)) ?: profiles.last()
        contextId = neighbor.id
        contextIndex = profiles.indexOfFirst { it.id == neighbor.id }
        withFrameNanos { }
        runCatching { contextFocus.requestFocus() }
    }

    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .roundedPanel()
            // D-pad entry from outside should fall INSIDE the menu — last-acted row, else first row,
            // else "Add Profile" (only when the list is empty).
            .focusProperties {
                onEnter = {
                    val tid = contextId
                    when {
                        tid != null && profiles.any { it.id == tid } -> runCatching { contextFocus.requestFocus() }
                        profiles.isNotEmpty() -> runCatching { firstRowFocus.requestFocus() }
                        else -> runCatching { addFocus.requestFocus() }
                    }
                }
            }
            .focusGroup()
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.profiles_title), style = MaterialTheme.typography.headlineLarge, color = colors.onSurface)
            Spacer(Modifier.weight(1f))
            OwnTVButton(stringResource(R.string.profiles_add_button), onClick = { request(ProfileManagementAction.CREATE) }, icon = OwnTVIcon.ADD, modifier = Modifier.focusRequester(addFocus))
        }
        Spacer(Modifier.height(20.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(profiles, key = { _, it -> it.id }) { index, p ->
                ProfileRow(
                    profile = p,
                    isActive = p.id == activeProfileId,
                    canDelete = canDeleteProfile(profiles.size, p.id, activeProfileId),
                    rowModifier = when {
                        p.id == contextId -> Modifier.focusRequester(contextFocus)
                        index == 0 -> Modifier.focusRequester(firstRowFocus)
                        else -> Modifier
                    },
                    onEdit = {
                        contextId = p.id
                        contextIndex = index
                        request(ProfileManagementAction.EDIT, p)
                    },
                    onDelete = {
                        contextId = p.id
                        contextIndex = index
                        request(ProfileManagementAction.DELETE, p)
                    },
                )
            }
        }
    }
    pendingProtectedAction?.let { pending ->
        PinDialog(
            title = if (pinError) {
                stringResource(R.string.profiles_wrong_pin)
            } else if (pending.pinPurpose == ProfilePinPurpose.ADULT_ADMIN) {
                stringResource(R.string.profiles_adult_pin_required, pending.pinProfile.name)
            } else {
                stringResource(R.string.profiles_enter_pin, pending.pinProfile.name)
            },
            onSubmit = { pin ->
                if (vm.verifyPin(pending.pinProfile, pin)) {
                    pendingProtectedAction = null
                    pinError = false
                    perform(
                        pending.action,
                        pending.target,
                        ProfileAuthorization(pending.pinProfile.id, pin),
                    )
                } else {
                    pinError = true
                }
            },
            onDismiss = {
                pendingProtectedAction = null
                pinError = false
            },
        )
    }

    if (creating) {
        ProfileEditorDialog(
            initial = null,
            onConfirm = { name, avatarId, isKids, pin ->
                vm.create(name, avatarId, isKids, pin, defaultProfileName, creatingAuthorization) { id ->
                    if (id != null) {
                        creating = false
                        creatingAuthorization = null
                    } else {
                        managementChanged = true
                    }
                }
            },
            onDismiss = { creating = false; creatingAuthorization = null },
            // Names must stay unique (backup restore matches profiles by name).
            takenNames = profiles.map { it.name.trim().lowercase() }.toSet(),
        )
    }
    editing?.let { p ->
        ProfileEditorDialog(
            initial = p,
            onConfirm = { name, avatarId, isKids, pin ->
                vm.edit(p, name, avatarId, isKids, pin, editingAuthorization) { changed ->
                    if (changed) {
                        editing = null
                        editingAuthorization = null
                    } else {
                        managementChanged = true
                    }
                }
            },
            onDismiss = { editing = null; editingAuthorization = null },
            takenNames = profiles.filter { it.id != p.id }.map { it.name.trim().lowercase() }.toSet(),
        )
    }
    confirmDelete?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.profiles_delete_title, p.name),
            message = stringResource(R.string.profiles_delete_message),
            onConfirm = {
                vm.delete(p, deleteAuthorization) { deleted ->
                    if (deleted) {
                        confirmDelete = null
                        deleteAuthorization = null
                    } else {
                        managementChanged = true
                    }
                }
            },
            onDismiss = { confirmDelete = null; deleteAuthorization = null },
        )
    }
    if (managementFailure || managementChanged) {
        ConfirmDialog(
            title = stringResource(R.string.profiles_management_locked_title),
            message = stringResource(
                if (managementChanged) R.string.profiles_management_changed_message
                else R.string.profiles_management_locked_message,
            ),
            confirmLabel = R.string.common_ok,
            onConfirm = { managementFailure = false; managementChanged = false },
            onDismiss = { managementFailure = false; managementChanged = false },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: ProfileEntity,
    isActive: Boolean,
    canDelete: Boolean,
    rowModifier: Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    Row(
        modifier = rowModifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHigh).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OwnTVAvatar(avatarId = profile.avatarId, imagePath = profile.avatarPath.orEmpty(), modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            val tags = buildList {
                if (isActive) add(stringResource(R.string.profiles_active_tag))
                if (profile.isKids) add(stringResource(R.string.profiles_kids_tag))
                if (profile.pinHash != null) add(stringResource(R.string.profiles_locked_tag))
            }
            if (tags.isNotEmpty()) {
                Text(tags.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        OwnTVButton(stringResource(R.string.common_edit), onClick = onEdit, style = OwnTVButtonStyle.SECONDARY)
        Spacer(Modifier.width(10.dp))
        OwnTVButton(
            stringResource(R.string.common_delete),
            onClick = onDelete,
            enabled = canDelete,
            style = OwnTVButtonStyle.SECONDARY,
        )
    }
}
