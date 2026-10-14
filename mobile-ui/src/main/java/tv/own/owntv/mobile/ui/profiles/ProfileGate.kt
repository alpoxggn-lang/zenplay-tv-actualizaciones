package tv.own.owntv.mobile.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.profile.ProfileAuthorization
import tv.own.owntv.core.profile.ProfileManagementAction
import tv.own.owntv.core.profile.ProfileManagementRequirement
import tv.own.owntv.core.profile.ProfilePinPurpose
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.theme.MobileDimens
import tv.own.owntv.mobile.ui.screens.settings.customize.ConfirmDialog

/** Two columns on a phone, four once there is room — the tiles stay a thumb's width apart either way. */
private val TILE_MIN_WIDTH = 140.dp
private val AVATAR_SIZE = 88.dp

/**
 * "Who's watching?" — the chooser the app opens with when more than one person uses it, or when the
 * only profile is PIN-locked.
 *
 * There is no way past it: it is shown instead of the app, not over it, so there is no back gesture
 * that reveals somebody else's library behind it. Choosing an unlocked profile enters immediately;
 * a locked one asks for its PIN first.
 */
@Composable
fun ProfileGate(
    profiles: List<ProfileEntity>,
    onEntered: (ProfileEntity) -> Unit,
    modifier: Modifier = Modifier,
    vm: ProfilesViewModel,
) {
    var pinFor by remember { mutableStateOf<ProfileEntity?>(null) }
    var pinPurpose by remember { mutableStateOf(ProfilePinPurpose.TARGET_PROFILE) }
    var pendingAction by remember { mutableStateOf(ProfileManagementAction.SWITCH) }
    var pendingTarget by remember { mutableStateOf<ProfileEntity?>(null) }
    var addProfile by remember { mutableStateOf(false) }
    var addAuthorization by remember { mutableStateOf<ProfileAuthorization?>(null) }
    var managementFailure by remember { mutableStateOf(false) }
    var managementChanged by remember { mutableStateOf(false) }
    val fallbackName = stringResource(R.string.profiles_default_name)

    fun perform(action: ProfileManagementAction, profile: ProfileEntity?, authorization: ProfileAuthorization?) {
        when (action) {
            ProfileManagementAction.SWITCH -> profile?.let {
                vm.switchTo(it, authorization) { switched ->
                    if (switched) onEntered(it) else managementChanged = true
                }
            }
            ProfileManagementAction.CREATE -> {
                addAuthorization = authorization
                addProfile = true
            }
            else -> Unit
        }
    }

    fun request(action: ProfileManagementAction, profile: ProfileEntity? = null) {
        vm.managementRequirement(action, profile) { requirement ->
            when (requirement) {
                ProfileManagementRequirement.Allowed -> perform(action, profile, null)
                is ProfileManagementRequirement.PinRequired -> {
                    pendingAction = action
                    pendingTarget = profile
                    pinPurpose = requirement.purpose
                    pinFor = requirement.profile
                }
                is ProfileManagementRequirement.Blocked -> managementFailure = true
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = MobileDimens.PagePaddingH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.profiles_gate_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = MobileDimens.GapLarge),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(TILE_MIN_WIDTH),
            horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapMedium),
            verticalArrangement = Arrangement.spacedBy(MobileDimens.GapMedium),
            contentPadding = PaddingValues(bottom = MobileDimens.GapLarge),
        ) {
            items(profiles, key = { it.id }) { profile ->
                ProfileTile(
                    profile = profile,
                    onClick = { request(ProfileManagementAction.SWITCH, profile) },
                )
            }
            item(key = "add") { AddProfileTile(onClick = { request(ProfileManagementAction.CREATE) }) }
        }
    }

    pinFor?.let { locked ->
        ProfilePinSheet(
            profileName = locked.name,
            titleOverride = if (pinPurpose == ProfilePinPurpose.ADULT_ADMIN) {
                stringResource(R.string.profiles_adult_pin_required, locked.name)
            } else null,
            onSubmit = { pin ->
                vm.verifyPin(locked, pin).also { valid ->
                    if (valid) {
                        pinFor = null
                        perform(pendingAction, pendingTarget, ProfileAuthorization(locked.id, pin))
                    }
                }
            },
            onDismiss = { pinFor = null },
        )
    }
    if (addProfile) {
        ProfileEditorSheet(
            initial = null,
            takenNames = profiles.map { it.name.lowercase() }.toSet(),
            onConfirm = { name, avatarId, isKids, pin ->
                vm.create(name, avatarId, isKids, pin, fallbackName, addAuthorization) { id ->
                    if (id == null) {
                        managementChanged = true
                    } else {
                        addProfile = false
                        addAuthorization = null
                    }
                }
            },
            onDismiss = { addProfile = false; addAuthorization = null },
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

/** One person's tile: their picture, their name, and what stands between them and the app. */
@Composable
private fun ProfileTile(profile: ProfileEntity, onClick: () -> Unit) {
    TileFrame(onClick = onClick) {
        ProfileAvatar(
            avatarId = profile.avatarId,
            imagePath = profile.avatarPath.orEmpty(),
            modifier = Modifier.size(AVATAR_SIZE).clip(CircleShape),
        )
        Text(
            text = profile.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = MobileDimens.GapSmall),
        )
        val tag = when {
            profile.isKids -> stringResource(R.string.profiles_kids_badge)
            profile.pinHash != null -> stringResource(R.string.profiles_locked_tag)
            else -> null
        }
        if (tag != null) {
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AddProfileTile(onClick: () -> Unit) {
    TileFrame(onClick = onClick) {
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE)
                .clip(CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MobileIcons.Add, contentDescription = null)
        }
        Text(
            text = stringResource(R.string.profiles_add),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = MobileDimens.GapSmall),
        )
    }
}

/** The tile is square so a row of them lines up whether or not a name wraps under one of them. */
@Composable
private fun TileFrame(onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(MobileDimens.GapSmall),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}
