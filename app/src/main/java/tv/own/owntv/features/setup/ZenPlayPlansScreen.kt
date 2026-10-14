package tv.own.owntv.features.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.core.setup.displayText
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.setup.SourceImporter
import tv.own.owntv.features.shell.ShellViewModel
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVButtonStyle
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * ZenPlay "Mis planes": Básica, Avanzado and Premium side by side. A plan already signed in can be
 * switched to with one press (no signing out, no typing the details again); one that is not yet
 * signed in opens the sign-in screen with that plan chosen, loads it, and makes it the active one.
 */
@Composable
fun ZenPlayPlansScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OwnTVTheme.colors
    val resources = LocalResources.current
    val shellVm: ShellViewModel = koinViewModel()
    val setupVm: SetupViewModel = koinViewModel()
    val settings = koinInject<tv.own.owntv.core.settings.SettingsRepository>()
    val playlists by shellVm.playlists.collectAsStateWithLifecycle()
    val activeId by shellVm.activePlaylistId.collectAsStateWithLifecycle()
    val importState by setupVm.state.collectAsStateWithLifecycle()

    var loginPlan by remember { mutableStateOf<ZenPlayPlan?>(null) }
    var importing by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Follow the import started from the sign-in screen.
    LaunchedEffect(importState, importing) {
        if (!importing) return@LaunchedEffect
        when (val st = importState) {
            is SourceImporter.ImportState.Success -> {
                setupVm.reset()
                importing = false
                loginPlan = null
                message = resources.getString(R.string.zenplay_plans_added)
            }
            is SourceImporter.ImportState.Failed -> {
                loginError = st.failure.displayText(resources)
                setupVm.reset()
                importing = false
            }
            else -> Unit
        }
    }

    val plan = loginPlan
    if (plan != null && !importing) {
        ZenPlayLoginScreen(
            initialPlan = plan,
            errorText = loginError,
            onCancel = { loginPlan = null; loginError = null },
            onLogin = { chosen, username, password ->
                loginError = null
                importing = true
                loginPlan = chosen
                scope.launch {
                    val pid = settings.activeProfileId.first()
                    setupVm.addZenPlan(
                        profileId = pid,
                        name = resources.getString(chosen.labelRes),
                        server = chosen.server,
                        username = username,
                        password = password,
                    )
                }
            },
            modifier = modifier,
        )
        return
    }
    if (importing) {
        val bgFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { bgFocus.requestFocus() } }
        BackHandler { setupVm.cancelImport(); importing = false }
        Box(
            modifier.fillMaxSize().background(colors.background).trapAllFocusExit().focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                tv.own.owntv.ui.components.ZenPlayLogoLoader(size = 220)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.zenplay_login_importing), fontSize = 18.sp, color = colors.onSurface)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Keep watching: the plan keeps loading and becomes the active one when it is ready.
                    OwnTVButton(
                        label = stringResource(R.string.setup_run_in_background),
                        onClick = {
                            setupVm.continueZenPlanInBackground()
                            importing = false
                            loginPlan = null
                            onBack()
                        },
                        icon = OwnTVIcon.PLAY,
                        modifier = Modifier.focusRequester(bgFocus),
                    )
                    OwnTVButton(
                        label = stringResource(R.string.zenplay_login_cancel),
                        onClick = { setupVm.cancelImport(); importing = false },
                        style = OwnTVButtonStyle.SECONDARY,
                    )
                }
            }
        }
        return
    }

    BackHandler { onBack() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .trapAllFocusExit()
            .focusGroup()
            .padding(horizontal = 40.dp, vertical = 32.dp),
    ) {
        Text(stringResource(R.string.zenplay_plans_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.zenplay_plans_subtitle), fontSize = 15.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
            ZenPlayPlan.entries.forEachIndexed { index, p ->
                val source = playlists.firstOrNull { p.matchesServer(it.url) }
                val isActive = source != null && (source.id == activeId || (playlists.size == 1))
                val shape = RoundedCornerShape(20.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(if (isActive) colors.primaryContainer.copy(alpha = 0.55f) else colors.surfaceContainer)
                        .border(if (isActive) 2.dp else 1.dp, if (isActive) colors.primary else colors.outlineVariant, shape)
                        .padding(22.dp),
                ) {
                    Text(stringResource(p.labelRes), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            source == null -> stringResource(R.string.zenplay_plans_not_signed)
                            else -> stringResource(R.string.zenplay_plans_signed_as, source.username.orEmpty())
                        },
                        fontSize = 14.sp,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))
                    val buttonModifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                    when {
                        source == null -> OwnTVButton(
                            label = stringResource(R.string.zenplay_login_submit),
                            onClick = { loginError = null; loginPlan = p },
                            icon = OwnTVIcon.PERSON,
                            style = OwnTVButtonStyle.SECONDARY,
                            modifier = buttonModifier,
                        )
                        isActive -> OwnTVButton(
                            label = stringResource(R.string.zenplay_plans_in_use),
                            onClick = { onBack() },
                            icon = OwnTVIcon.PLAY,
                            modifier = buttonModifier,
                        )
                        else -> OwnTVButton(
                            label = stringResource(R.string.zenplay_plans_use),
                            onClick = {
                                shellVm.setActivePlaylist(source.id)
                                message = resources.getString(
                                    R.string.zenplay_plans_switched,
                                    resources.getString(p.labelRes),
                                )
                            },
                            icon = OwnTVIcon.SWAP,
                            modifier = buttonModifier,
                        )
                    }
                }
            }
        }
        message?.let {
            Spacer(Modifier.height(20.dp))
            Text(it, fontSize = 16.sp, color = colors.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}
