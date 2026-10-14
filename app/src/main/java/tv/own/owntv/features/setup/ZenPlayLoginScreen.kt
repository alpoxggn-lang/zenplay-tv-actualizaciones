package tv.own.owntv.features.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVTextField
import tv.own.owntv.ui.theme.OwnTVTheme

/**
 * ZenPlay's fixed Xtream service. The customer only types the user name and password supplied with
 * the account; no server or plan choice is exposed in the application.
 */
enum class ZenPlayPlan(@param:StringRes val labelRes: Int, val server: String) {
    ZENPLAY(R.string.zenplay_plan_zenplay, "https://dns.ruta5.xyz:8443/"),
}

/**
 * ZenPlay's first screen: enter user and password, then sign in. It replaces OwnTV's welcome →
 * disclaimer → profile → add-source wizard for a first run.
 *
 * [errorText] is the reason the previous attempt failed (shown under the button), or null.
 */
@Composable
fun ZenPlayLoginScreen(
    onLogin: (plan: ZenPlayPlan, username: String, password: String) -> Unit,
    errorText: String? = null,
    modifier: Modifier = Modifier,
    /** ZenPlay "Mis planes": the plan to preselect. */
    initialPlan: ZenPlayPlan = ZenPlayPlan.ZENPLAY,
    /** When set, Back closes the screen (adding a plan from inside the app). */
    onCancel: (() -> Unit)? = null,
) {
    val colors = OwnTVTheme.colors
    if (onCancel != null) androidx.activity.compose.BackHandler { onCancel() }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var missing by remember { mutableStateOf(false) }
    val usernameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { usernameFocus.requestFocus() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF070B1A), Color(0xFF0F1440), Color(0xFF221052)),
                ),
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            // Left: the logo and tagline.
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.zenplay_logo),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(260.dp),
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    stringResource(R.string.zenplay_login_tagline),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // Right: the form.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(colors.surfaceContainerLow.copy(alpha = 0.85f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 36.dp, vertical = 30.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.zenplay_login_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.zenplay_login_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(22.dp))
                OwnTVTextField(
                    value = username,
                    onValueChange = { username = it; missing = false },
                    label = stringResource(R.string.zenplay_login_username),
                    modifier = Modifier.fillMaxWidth().focusRequester(usernameFocus),
                    keyboardType = KeyboardType.Text,
                )
                Spacer(Modifier.height(14.dp))
                OwnTVTextField(
                    value = password,
                    onValueChange = { password = it; missing = false },
                    label = stringResource(R.string.zenplay_login_password),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                )
                Spacer(Modifier.height(24.dp))
                OwnTVButton(
                    label = stringResource(R.string.zenplay_login_submit),
                    onClick = {
                        if (username.isBlank() || password.isBlank()) {
                            missing = true
                        } else {
                            onLogin(initialPlan, username.trim(), password)
                        }
                    },
                    icon = OwnTVIcon.PLAY,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                )
                val message = when {
                    missing -> stringResource(R.string.zenplay_login_missing_fields)
                    !errorText.isNullOrBlank() -> errorText
                    else -> null
                }
                if (message != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFF8FA8),
                    )
                }
            }
        }
    }
}

/** ZenPlay: whether [url] (a saved list's server address) is this plan's server. */
fun ZenPlayPlan.matchesServer(url: String): Boolean {
    fun key(u: String): String? = runCatching {
        val uri = java.net.URI(u.trim())
        val scheme = uri.scheme?.lowercase() ?: "http"
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        "${uri.host?.lowercase()}:$port"
    }.getOrNull()
    val mine = key(server) ?: return false
    return key(url) == mine
}
