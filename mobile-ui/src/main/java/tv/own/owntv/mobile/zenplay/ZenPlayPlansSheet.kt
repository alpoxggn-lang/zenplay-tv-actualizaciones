package tv.own.owntv.mobile.zenplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileButton
import tv.own.owntv.mobile.ui.components.MobileButtonStyle
import tv.own.owntv.mobile.ui.components.MobileTextField
import tv.own.owntv.mobile.ui.setup.ZenPlayPlan

/**
 * "Mis planes", opened from the plan chip in the top bar: Básica, Avanzado and Premium stacked for a
 * phone held upright. Tap "Usar este plan" to switch, or "Iniciar sesión" to add one more plan.
 */
@Composable
fun ZenPlayPlansSheet(onDismiss: () -> Unit, vm: ZenPlayPlansViewModel = koinViewModel()) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    var loginPlan by remember { mutableStateOf<ZenPlayPlan?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.clearMessages() }
    // A plan added successfully closes its sign-in form.
    LaunchedEffect(vm.doneRes) { if (vm.doneRes == R.string.zenplay_plans_added) { loginPlan = null; password = "" } }

    MobileBottomSheet(
        onDismissRequest = { if (!vm.busy) onDismiss() },
        title = stringResource(R.string.zenplay_plans_title),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.zenplay_plans_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ZenPlayPlan.entries.forEach { plan ->
                val source = vm.sourceFor(plan, playlists)
                val inUse = source != null && (source.id == activeId || playlists.size == 1)
                val shape = RoundedCornerShape(18.dp)
                val colors = MaterialTheme.colorScheme
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (inUse) colors.primaryContainer.copy(alpha = 0.45f) else colors.surfaceContainerHigh, shape)
                        .border(if (inUse) 2.dp else 1.dp, if (inUse) colors.primary else colors.outlineVariant, shape)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(plan.labelRes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                if (source == null) stringResource(R.string.zenplay_plans_not_signed)
                                else stringResource(R.string.zenplay_plans_signed_as, source.username.orEmpty()),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        when {
                            inUse -> Text(
                                stringResource(R.string.zenplay_plans_in_use),
                                color = colors.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            source != null -> MobileButton(
                                text = stringResource(R.string.zenplay_plans_use),
                                onClick = { vm.switchTo(plan, source) },
                                enabled = !vm.busy,
                            )
                            loginPlan != plan -> MobileButton(
                                text = stringResource(R.string.zenplay_login_submit),
                                onClick = { vm.clearMessages(); loginPlan = plan; username = ""; password = "" },
                                style = MobileButtonStyle.SECONDARY,
                                enabled = !vm.busy,
                            )
                        }
                    }
                    if (source == null && loginPlan == plan) {
                        val planName = stringResource(plan.labelRes)
                        if (vm.busy) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ZenPlayLogoLoader(size = 64)
                                Text(stringResource(R.string.zenplay_login_importing), style = MaterialTheme.typography.bodyMedium)
                            }
                            MobileButton(
                                text = stringResource(R.string.zenplay_login_cancel),
                                onClick = vm::cancel,
                                style = MobileButtonStyle.SECONDARY,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            MobileTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = stringResource(R.string.zenplay_login_username),
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Next,
                            )
                            MobileTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = stringResource(R.string.zenplay_login_password),
                                keyboardType = KeyboardType.Password,
                                isPassword = true,
                                imeAction = ImeAction.Done,
                                onImeDone = { vm.addPlan(plan, planName, username, password) },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MobileButton(
                                    text = stringResource(R.string.zenplay_login_cancel),
                                    onClick = { loginPlan = null; vm.clearMessages() },
                                    style = MobileButtonStyle.SECONDARY,
                                    modifier = Modifier.weight(1f),
                                )
                                MobileButton(
                                    text = stringResource(R.string.zenplay_login_submit),
                                    onClick = { vm.addPlan(plan, planName, username, password) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            vm.errorRes?.let {
                Text(
                    stringResource(it),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                )
            }
            val done = vm.doneRes
            val plan = vm.donePlan
            if (done != null && plan != null) {
                Text(
                    if (done == R.string.zenplay_plans_switched) stringResource(done, stringResource(plan.labelRes))
                    else stringResource(done),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
