package tv.own.owntv.features.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
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
import tv.own.owntv.features.settings.ConfirmDialog
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVAvatar
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * Phase 6.5 — the "Who's watching?" launch gate. Shown when more than one profile exists. Picking a
 * profile makes it active (and prompts for a PIN if it's locked); [onEnter] records the selected
 * profile id and proceeds into the shell.
 */
@Composable
fun ProfileGate(
    onEnter: (Long) -> Unit,
    onAddProfile: (ProfileAuthorization?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ProfilesViewModel = koinViewModel()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()
    val colors = OwnTVTheme.colors

    var actionTarget by remember { mutableStateOf<ProfileEntity?>(null) }
    var editing by remember { mutableStateOf<ProfileEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<ProfileEntity?>(null) }
    var pinFor by remember { mutableStateOf<ProfileEntity?>(null) }
    var pinPurpose by remember { mutableStateOf(ProfilePinPurpose.TARGET_PROFILE) }
    var pendingAction by remember { mutableStateOf(ProfileManagementAction.SWITCH) }
    var pendingTarget by remember { mutableStateOf<ProfileEntity?>(null) }
    var editingAuthorization by remember { mutableStateOf<ProfileAuthorization?>(null) }
    var deleteAuthorization by remember { mutableStateOf<ProfileAuthorization?>(null) }
    var pinError by remember { mutableStateOf(false) }
    var managementFailure by remember { mutableStateOf(false) }
    var managementChanged by remember { mutableStateOf(false) }
    var contextId by remember { mutableStateOf<Long?>(null) }
    var contextIndex by remember { mutableStateOf(-1) }
    val firstFocus = remember { FocusRequester() }
    val contextFocus = remember { FocusRequester() }

    LaunchedEffect(profiles.isNotEmpty()) {
        if (profiles.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }

    // Closing an action/editor/delete popup returns focus to the profile that opened it. If that
    // profile was deleted, keep the D-pad in the row by moving to the nearest surviving neighbour.
    // This prevents focus from escaping behind the gate or jumping to "Add profile" unexpectedly.
    LaunchedEffect(
        profiles,
        actionTarget,
        editing,
        confirmDelete,
        pinFor,
        managementFailure,
        managementChanged,
    ) {
        val overlayOpen = actionTarget != null || editing != null || confirmDelete != null ||
            pinFor != null || managementFailure || managementChanged
        if (overlayOpen || profiles.isEmpty() || contextId == null) return@LaunchedEffect

        val targetId = profileGateFocusTarget(profiles.map { it.id }, contextId, contextIndex)
        contextId = targetId
        contextIndex = profiles.indexOfFirst { it.id == targetId }
        delay(50) // allow the popup to release its focus owner before restoring the tile
        runCatching { contextFocus.requestFocus() }
    }

    fun perform(action: ProfileManagementAction, target: ProfileEntity?, authorization: ProfileAuthorization?) {
        when (action) {
            ProfileManagementAction.SWITCH -> target?.let { profile ->
                vm.switchTo(profile, authorization) { switched ->
                    if (switched) onEnter(profile.id) else managementChanged = true
                }
            }
            ProfileManagementAction.CREATE -> onAddProfile(authorization)
            ProfileManagementAction.EDIT -> target?.let { profile ->
                editingAuthorization = authorization
                editing = profile
            }
            ProfileManagementAction.DELETE -> target?.let { profile ->
                if (canDeleteProfile(profiles.size, profile.id, activeProfileId)) {
                    deleteAuthorization = authorization
                    confirmDelete = profile
                } else {
                    managementFailure = true
                }
            }
        }
    }

    fun request(action: ProfileManagementAction, target: ProfileEntity? = null) {
        vm.managementRequirement(action, target) { requirement ->
            when (requirement) {
                ProfileManagementRequirement.Allowed -> {
                    // Unlike Settings, the launch gate has not authenticated the persisted active
                    // profile yet. Do not let an "Allowed" result that assumes an authenticated
                    // shell session expose edit/delete before proving a configured profile PIN.
                    val gateChallenge = profileGateManagementChallenge(
                        profiles = profiles,
                        activeProfileId = activeProfileId,
                        action = action,
                        target = target,
                    )
                    if (gateChallenge == null) {
                        perform(action, target, null)
                    } else {
                        pendingAction = action
                        pendingTarget = target
                        pinPurpose = gateChallenge.purpose
                        pinError = false
                        pinFor = gateChallenge.profile
                    }
                }
                is ProfileManagementRequirement.PinRequired -> {
                    pendingAction = action
                    pendingTarget = target
                    pinPurpose = requirement.purpose
                    pinError = false
                    pinFor = requirement.profile
                }
                is ProfileManagementRequirement.Blocked -> managementFailure = true
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.profiles_gate_title), style = MaterialTheme.typography.headlineLarge, color = colors.onSurface)
            Spacer(Modifier.height(36.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.focusGroup().padding(horizontal = 48.dp),
            ) {
                itemsIndexed(profiles, key = { _, profile -> profile.id }) { index, p ->
                    ProfileTile(
                        profile = p,
                        modifier = when {
                            p.id == contextId -> Modifier.focusRequester(contextFocus)
                            index == 0 -> Modifier.focusRequester(firstFocus)
                            else -> Modifier
                        },
                        onClick = {
                            contextId = p.id
                            contextIndex = index
                            actionTarget = p
                        },
                    )
                }
                item { AddTile(onClick = { request(ProfileManagementAction.CREATE) }) }
            }
        }
    }

    actionTarget?.let { profile ->
        ProfileGateActionDialog(
            profile = profile,
            canDelete = canDeleteProfile(profiles.size, profile.id, activeProfileId),
            onEnter = {
                actionTarget = null
                request(ProfileManagementAction.SWITCH, profile)
            },
            onEdit = {
                actionTarget = null
                request(ProfileManagementAction.EDIT, profile)
            },
            onDelete = {
                actionTarget = null
                request(ProfileManagementAction.DELETE, profile)
            },
            onDismiss = { actionTarget = null },
        )
    }

    pinFor?.let { p ->
        PinDialog(
            title = when {
                pinError -> stringResource(R.string.profiles_wrong_pin)
                pinPurpose == ProfilePinPurpose.ADULT_ADMIN -> stringResource(R.string.profiles_adult_pin_required, p.name)
                else -> stringResource(R.string.profiles_enter_pin, p.name)
            },
            onSubmit = { pin ->
                if (vm.verifyPin(p, pin)) {
                    pinFor = null
                    perform(pendingAction, pendingTarget, ProfileAuthorization(p.id, pin))
                } else pinError = true
            },
            onDismiss = { pinFor = null },
        )
    }
    editing?.let { profile ->
        ProfileEditorDialog(
            initial = profile,
            onConfirm = { name, avatarId, isKids, pin ->
                vm.edit(profile, name, avatarId, isKids, pin, editingAuthorization) { changed ->
                    if (changed) {
                        editing = null
                        editingAuthorization = null
                    } else {
                        managementChanged = true
                    }
                }
            },
            onDismiss = {
                editing = null
                editingAuthorization = null
            },
            takenNames = profiles
                .filter { it.id != profile.id }
                .map { it.name.trim().lowercase() }
                .toSet(),
        )
    }
    confirmDelete?.let { profile ->
        ConfirmDialog(
            title = stringResource(R.string.profiles_delete_title, profile.name),
            message = stringResource(R.string.profiles_delete_message),
            onConfirm = {
                vm.delete(profile, deleteAuthorization) { deleted ->
                    if (deleted) {
                        confirmDelete = null
                        deleteAuthorization = null
                    } else {
                        managementChanged = true
                    }
                }
            },
            onDismiss = {
                confirmDelete = null
                deleteAuthorization = null
            },
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

/**
 * Stable focus target after a TV profile action closes. Kept pure so the delete-neighbour rule can
 * be covered without a Compose/TV instrumentation harness.
 */
internal fun profileGateFocusTarget(
    profileIds: List<Long>,
    previousId: Long?,
    previousIndex: Int,
): Long? {
    if (profileIds.isEmpty()) return null
    if (previousId != null && previousId in profileIds) return previousId
    return profileIds.getOrNull(previousIndex.coerceAtLeast(0)) ?: profileIds.last()
}

/**
 * Settings runs after the active profile has entered the shell; the launch gate does not. Require
 * a real PIN proof before profile administration whenever one is available, even when Core's
 * settings-session policy can otherwise treat the persisted active adult as already authenticated.
 */
internal fun profileGateManagementChallenge(
    profiles: List<ProfileEntity>,
    activeProfileId: Long?,
    action: ProfileManagementAction,
    target: ProfileEntity?,
): ProfileManagementRequirement.PinRequired? {
    if (action != ProfileManagementAction.CREATE &&
        action != ProfileManagementAction.EDIT &&
        action != ProfileManagementAction.DELETE
    ) return null

    target?.takeIf { !it.pinHash.isNullOrBlank() }?.let {
        return ProfileManagementRequirement.PinRequired(it, ProfilePinPurpose.TARGET_PROFILE)
    }
    val protectedAdult = profiles.firstOrNull {
        it.id == activeProfileId && !it.isKids && !it.pinHash.isNullOrBlank()
    } ?: profiles.firstOrNull { !it.isKids && !it.pinHash.isNullOrBlank() }
    return protectedAdult?.let {
        ProfileManagementRequirement.PinRequired(it, ProfilePinPurpose.ADULT_ADMIN)
    }
}

@Composable
private fun ProfileGateActionDialog(
    profile: ProfileEntity,
    canDelete: Boolean,
    onEnter: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(profile.id) { runCatching { firstFocus.requestFocus() } }

    ProfileScrim(onDismiss = onDismiss, width = 420.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            OwnTVAvatar(
                avatarId = profile.avatarId,
                imagePath = profile.avatarPath.orEmpty(),
                modifier = Modifier.size(88.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                profile.name,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            OwnTVButton(
                label = stringResource(R.string.profiles_use_profile),
                onClick = onEnter,
                icon = OwnTVIcon.PLAY,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
            Spacer(Modifier.height(10.dp))
            OwnTVButton(
                label = stringResource(R.string.profiles_edit),
                onClick = onEdit,
                icon = OwnTVIcon.PERSON,
                style = OwnTVButtonStyle.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OwnTVButton(
                label = stringResource(R.string.common_delete),
                onClick = onDelete,
                icon = OwnTVIcon.CLOSE,
                enabled = canDelete,
                style = OwnTVButtonStyle.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!canDelete) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.profiles_delete_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ProfileTile(profile: ProfileEntity, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = OwnTVTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FocusableSurface(
            onClick = onClick,
            modifier = modifier.size(132.dp),
            shape = RoundedCornerShape(24.dp),
            contentAlignment = Alignment.Center,
        ) { _ ->
            Box(contentAlignment = Alignment.TopEnd) {
                OwnTVAvatar(avatarId = profile.avatarId, imagePath = profile.avatarPath.orEmpty(), modifier = Modifier.size(104.dp))
                if (profile.pinHash != null) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(colors.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        OwnTVIcon(OwnTVIcon.SETTINGS, tint = colors.onSurface, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(profile.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, textAlign = TextAlign.Center)
        if (profile.isKids) {
            Text(stringResource(R.string.profiles_kids_badge), style = MaterialTheme.typography.labelSmall, color = colors.primary)
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    val colors = OwnTVTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FocusableSurface(
            onClick = onClick,
            modifier = Modifier.size(132.dp),
            shape = RoundedCornerShape(24.dp),
            contentAlignment = Alignment.Center,
        ) { _ ->
            Box(
                modifier = Modifier.size(104.dp).clip(CircleShape).background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                OwnTVIcon(OwnTVIcon.ADD, tint = colors.onSurfaceVariant, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.profiles_add), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
    }
}
