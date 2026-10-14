package tv.own.owntv.mobile.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.sync.detailText
import tv.own.owntv.core.sync.importProgressDisplay
import tv.own.owntv.core.theme.AccentColor
import tv.own.owntv.core.theme.ThemeMode
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileButton
import tv.own.owntv.mobile.ui.components.MobileButtonStyle
import tv.own.owntv.mobile.ui.components.MobileTextField
import tv.own.owntv.mobile.ui.theme.MobileTheme

/** Touch-first entry point. A verified account and completed import are required to enter Home. */
@Composable
fun ZenPlayLoginScreen(
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    vm: ZenPlayLoginViewModel = koinViewModel(),
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(vm.busy) {
        if (vm.busy) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    // During an import Back cancels and cleans up. At first-run idle, Android handles exiting.
    BackHandler(enabled = vm.busy || onCancel != null) {
        if (vm.busy) vm.cancel() else onCancel?.invoke()
    }

    MobileTheme(themeMode = ThemeMode.DARK, accent = AccentColor.BLUE) {
        BoxWithConstraints(
            modifier = modifier.fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF071329), Color(0xFF15122E), Color(0xFF261347))))
                .safeDrawingPadding().imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            val availableHeight = maxHeight
            if (maxWidth >= 760.dp) {
                Row(
                    modifier = Modifier.widthIn(max = 1040.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoginBrand(
                        logoSize = if (availableHeight < 480.dp) 180.dp else 280.dp,
                        modifier = Modifier.weight(1f),
                    )
                    LoginForm(vm = vm, modifier = Modifier.weight(1f))
                }
            } else {
                Column(
                    modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    LoginBrand(logoSize = if (availableHeight < 560.dp) 96.dp else 152.dp)
                    LoginForm(vm = vm, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun LoginBrand(logoSize: Dp, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.zenplay_logo),
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(logoSize),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.zenplay_login_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoginForm(vm: ZenPlayLoginViewModel, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val profileName = stringResource(vm.plan.labelRes)
    val progress by vm.progress.collectAsStateWithLifecycle()
    val resources = LocalContext.current.resources
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var passwordVisible by remember { mutableStateOf(false) }
    // ZenPlay: after the account details comes the profile — name, picture, optional PIN, kids.
    var profileStep by remember { mutableStateOf(false) }
    val submit: () -> Unit = {
        focusManager.clearFocus()
        keyboard?.hide()
        passwordVisible = false
        if (vm.validate()) profileStep = true
    }
    // Profile fields, shown inside this same card (no pop-up sheet: the login runs before the app shell).
    var profileNameInput by remember { mutableStateOf("") }
    var profileKids by remember { mutableStateOf(false) }
    var profilePin by remember { mutableStateOf("") }
    val enter: () -> Unit = {
        focusManager.clearFocus()
        keyboard?.hide()
        if (profilePin.isEmpty() || profilePin.length >= 4) {
            profileStep = false
            vm.signIn(profileNameInput.trim().ifBlank { profileName }, 0, profileKids, profilePin.takeIf { it.isNotBlank() })
        }
    }
    val shape = RoundedCornerShape(28.dp)

    Column(
        modifier = modifier.background(Color(0xE0161D38), shape)
            .border(1.dp, Color(0xFF39436B), shape).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.zenplay_login_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.zenplay_login_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )

        if (vm.busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ZenPlay: the animated logo while the account is checked and the content loads.
                tv.own.owntv.mobile.zenplay.ZenPlayLogoLoader(size = 72)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(if (vm.importing) R.string.zenplay_login_importing else R.string.zenplay_login_checking),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(text = profileName, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (vm.importing) {
                Text(
                    text = progress?.importProgressDisplay()?.detailText(resources)
                        ?: stringResource(R.string.setup_preparing_catalog),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            MobileButton(
                text = stringResource(R.string.zenplay_login_cancel),
                onClick = vm::cancel,
                style = MobileButtonStyle.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (profileStep) {
            Text(
                text = stringResource(R.string.zenplay_profile_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            MobileTextField(
                value = profileNameInput,
                onValueChange = { if (it.length <= 24) profileNameInput = it },
                label = stringResource(R.string.zenplay_profile_name),
                placeholder = profileName,
                imeAction = ImeAction.Next,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.zenplay_profile_kids), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.zenplay_profile_kids_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(checked = profileKids, onCheckedChange = { profileKids = it })
            }
            MobileTextField(
                value = profilePin,
                onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) profilePin = it },
                label = stringResource(R.string.zenplay_profile_pin),
                placeholder = stringResource(R.string.zenplay_profile_pin_hint),
                keyboardType = KeyboardType.NumberPassword,
                isPassword = true,
                imeAction = ImeAction.Done,
                onImeDone = enter,
                isError = profilePin.isNotEmpty() && profilePin.length < 4,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MobileButton(
                    text = stringResource(R.string.zenplay_profile_back),
                    onClick = { profileStep = false },
                    style = MobileButtonStyle.SECONDARY,
                    modifier = Modifier.weight(1f),
                )
                MobileButton(
                    text = stringResource(R.string.zenplay_profile_enter),
                    onClick = enter,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            MobileTextField(
                value = vm.username,
                onValueChange = vm::updateUsername,
                label = stringResource(R.string.zenplay_login_username),
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            )
            MobileTextField(
                value = vm.password,
                onValueChange = vm::updatePassword,
                label = stringResource(R.string.zenplay_login_password),
                keyboardType = KeyboardType.Password,
                isPassword = !passwordVisible,
                imeAction = ImeAction.Done,
                onImeDone = submit,
            )
            TextButton(
                onClick = { passwordVisible = !passwordVisible },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(if (passwordVisible) R.string.zenplay_login_hide_password else R.string.zenplay_login_show_password))
            }
            vm.errorRes?.let { error ->
                Text(
                    text = stringResource(error),
                    color = colors.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                        .background(colors.errorContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                )
            }
            MobileButton(
                text = stringResource(R.string.zenplay_login_submit),
                onClick = submit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
