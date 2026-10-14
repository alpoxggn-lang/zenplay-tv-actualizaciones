package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.profile.ProfileAuthorization
import tv.own.owntv.core.profile.ProfileManagementAction
import tv.own.owntv.core.profile.ProfileManagementRequirement
import tv.own.owntv.core.profile.ProfilePinPurpose
import tv.own.owntv.core.profile.canDeleteProfile
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.mobile.ui.profiles.ProfileAvatar
import tv.own.owntv.mobile.ui.profiles.ProfileEditorSheet
import tv.own.owntv.mobile.ui.profiles.ProfileGateSession
import tv.own.owntv.mobile.ui.profiles.ProfilePinSheet
import tv.own.owntv.mobile.ui.profiles.ProfilesViewModel
import tv.own.owntv.mobile.ui.screens.settings.customize.ConfirmDialog

/** The picture is the size of a list row's icon, so the rows keep the page's rhythm. */
private val ROW_AVATAR_SIZE = 36.dp

private data class PendingProfileAction(
    val target: ProfileEntity?,
    val action: ProfileManagementAction,
    val pinProfile: ProfileEntity,
    val pinPurpose: ProfilePinPurpose,
)

/**
 * Who is watching, and who may. Every row exposes Edit and Delete; a protected profile asks for its
 * PIN before either action. The active and last profiles cannot be deleted.
 */
@Composable
fun SettingsProfilePage(
    modifier: Modifier = Modifier,
    vm: ProfilesViewModel = koinViewModel(),
    gateSession: ProfileGateSession = koinViewModel(),
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeId by vm.activeProfileId.collectAsStateWithLifecycle()
    val fallbackName = stringResource(R.string.profiles_default_name)

    var editing by remember { mutableStateOf<ProfileEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProfileEntity?>(null) }
    var pendingPinAction by remember { mutableStateOf<PendingProfileAction?>(null) }
    var addingAuthorization by remember { mutableStateOf<ProfileAuthorization?>(null) }
    var editingAuthorization by remember { mutableStateOf<ProfileAuthorization?>(null) }
    var deletingAuthorization by remember { mutableStateOf<ProfileAuthorization?>(null) }
    var managementFailure by remember { mutableStateOf(false) }
    var managementChanged by remember { mutableStateOf(false) }

    val list = profiles.orEmpty()

    // Switching here is the same authentication boundary as the launch chooser, so the session is
    // told about it — otherwise the next rotation would bounce the user back to "Who's watching?".
    fun switchTo(profile: ProfileEntity, authorization: ProfileAuthorization?) =
        vm.switchTo(profile, authorization) { switched ->
            if (switched) gateSession.unlock(profile.id) else managementChanged = true
        }

    fun perform(
        action: ProfileManagementAction,
        profile: ProfileEntity?,
        authorization: ProfileAuthorization?,
    ) {
        when (action) {
            ProfileManagementAction.CREATE -> {
                addingAuthorization = authorization
                adding = true
            }
            ProfileManagementAction.SWITCH -> profile?.let {
                if (it.id != activeId) switchTo(it, authorization)
            }
            ProfileManagementAction.EDIT -> profile?.let {
                editingAuthorization = authorization
                editing = it
            }
            ProfileManagementAction.DELETE -> profile?.let {
                if (canDeleteProfile(list.size, it.id, activeId)) {
                    deletingAuthorization = authorization
                    deleting = it
                }
            }
        }
    }

    fun request(action: ProfileManagementAction, profile: ProfileEntity? = null) {
        vm.managementRequirement(action, profile) { requirement ->
            when (requirement) {
                ProfileManagementRequirement.Allowed -> perform(action, profile, null)
                is ProfileManagementRequirement.PinRequired -> pendingPinAction = PendingProfileAction(
                    target = profile,
                    action = action,
                    pinProfile = requirement.profile,
                    pinPurpose = requirement.purpose,
                )
                is ProfileManagementRequirement.Blocked -> managementFailure = true
            }
        }
    }

    SettingsPage(modifier) {
        settingsSection(R.string.profiles_title) {
            list.forEach { profile ->
                MobileListRow(
                    title = profile.name,
                    subtitle = buildList {
                        if (profile.id == activeId) add(stringResource(R.string.profiles_active_tag))
                        if (profile.isKids) add(stringResource(R.string.profiles_kids_tag))
                        if (profile.pinHash != null) add(stringResource(R.string.profiles_locked_tag))
                    }.joinToString(" • ").ifBlank { null },
                    leading = {
                        ProfileAvatar(
                            avatarId = profile.avatarId,
                            imagePath = profile.avatarPath.orEmpty(),
                            modifier = Modifier.size(ROW_AVATAR_SIZE).clip(CircleShape),
                        )
                    },
                    onClick = { request(ProfileManagementAction.SWITCH, profile) },
                    onLongClick = { request(ProfileManagementAction.EDIT, profile) },
                    selected = profile.id == activeId,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { request(ProfileManagementAction.EDIT, profile) }) {
                                Icon(
                                    imageVector = MobileIcons.Edit,
                                    contentDescription = stringResource(R.string.common_edit),
                                )
                            }
                            IconButton(
                                onClick = { request(ProfileManagementAction.DELETE, profile) },
                                enabled = canDeleteProfile(list.size, profile.id, activeId),
                            ) {
                                Icon(
                                    imageVector = MobileIcons.Delete,
                                    contentDescription = stringResource(R.string.common_delete),
                                )
                            }
                            if (profile.id == activeId) {
                                Icon(
                                    imageVector = MobileIcons.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                )
            }
            MobileListRow(
                title = stringResource(R.string.profiles_add_button),
                leading = { Icon(MobileIcons.Add, contentDescription = null) },
                onClick = { request(ProfileManagementAction.CREATE) },
            )
        }
    }

    // A locked profile is unlocked before it is switched into, not after: the point of the lock is
    // that its library never appears without the PIN.
    pendingPinAction?.let { pending ->
        ProfilePinSheet(
            profileName = pending.pinProfile.name,
            titleOverride = if (pending.pinPurpose == ProfilePinPurpose.ADULT_ADMIN) {
                stringResource(R.string.profiles_adult_pin_required, pending.pinProfile.name)
            } else null,
            onSubmit = { pin ->
                vm.verifyPin(pending.pinProfile, pin).also { valid ->
                    if (valid) {
                        pendingPinAction = null
                        perform(
                            pending.action,
                            pending.target,
                            ProfileAuthorization(pending.pinProfile.id, pin),
                        )
                    }
                }
            },
            onDismiss = { pendingPinAction = null },
        )
    }
    if (adding) {
        ProfileEditorSheet(
            initial = null,
            takenNames = list.map { it.name.lowercase() }.toSet(),
            onConfirm = { name, avatarId, isKids, pin ->
                vm.create(name, avatarId, isKids, pin, fallbackName, addingAuthorization) { id ->
                    if (id == null) {
                        managementChanged = true
                    } else {
                        adding = false
                        addingAuthorization = null
                    }
                }
            },
            onDismiss = { adding = false; addingAuthorization = null },
        )
    }
    editing?.let { profile ->
        ProfileEditorSheet(
            initial = profile,
            takenNames = list.filter { it.id != profile.id }.map { it.name.lowercase() }.toSet(),
            onConfirm = { name, avatarId, isKids, pin ->
                vm.edit(profile, name, avatarId, isKids, pin, editingAuthorization) { changed ->
                    if (!changed) {
                        managementChanged = true
                    } else {
                        editing = null
                        editingAuthorization = null
                    }
                }
            },
            onDismiss = { editing = null; editingAuthorization = null },
            customPath = profile.avatarPath.orEmpty(),
            onPickPicture = { uri -> vm.setPicture(profile, uri, editingAuthorization) },
            onClearPicture = { vm.clearPicture(profile, editingAuthorization) },
            extraAction = if (canDeleteProfile(list.size, profile.id, activeId)) {
                { request(ProfileManagementAction.DELETE, profile) }
            } else {
                null
            },
        )
    }
    deleting?.let { profile ->
        ConfirmDialog(
            title = stringResource(R.string.profiles_delete_title, profile.name),
            message = stringResource(R.string.profiles_delete_message),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                vm.delete(profile, deletingAuthorization) { deleted ->
                    if (!deleted) {
                        managementChanged = true
                    } else {
                        deleting = null
                        editing = null
                        deletingAuthorization = null
                        editingAuthorization = null
                    }
                }
            },
            onDismiss = { deleting = null; deletingAuthorization = null },
        )
    }
    if (managementFailure || managementChanged) {
        ConfirmDialog(
            title = stringResource(R.string.profiles_management_locked_title),
            message = stringResource(
                if (managementChanged) R.string.profiles_management_changed_message
                else R.string.profiles_management_locked_message,
            ),
            confirmLabel = stringResource(R.string.common_ok),
            onConfirm = { managementFailure = false; managementChanged = false },
            onDismiss = { managementFailure = false; managementChanged = false },
        )
    }
}
