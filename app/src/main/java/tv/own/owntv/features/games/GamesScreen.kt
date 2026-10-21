package tv.own.owntv.features.games

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.games.GamesCompanion
import tv.own.owntv.ui.components.ContentPanelFill
import tv.own.owntv.ui.components.OwnTVButton
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.roundedPanel
import tv.own.owntv.ui.theme.OwnTVTheme

/** TV/Fire TV doorway to the separately licensed ZenPlay Games companion. */
@Composable
fun GamesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val firstFocus = remember { FocusRequester() }
    val colors = OwnTVTheme.colors
    var installed by remember { mutableStateOf(GamesCompanion.isInstalled(context)) }
    var actionFailed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installed = GamesCompanion.isInstalled(context)
                actionFailed = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(
        modifier = modifier
            .roundedPanel(fillColor = ContentPanelFill)
            .padding(horizontal = 48.dp, vertical = 38.dp)
            .focusGroup(),
    ) {
        Text(
            text = stringResource(R.string.games_companion_title),
            fontSize = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.games_companion_description),
            fontSize = 18.sp,
            color = colors.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 760.dp),
        )
        Spacer(Modifier.height(30.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OwnTVButton(
                label = stringResource(
                    if (installed) R.string.games_companion_open else R.string.games_companion_install,
                ),
                icon = if (installed) OwnTVIcon.PLAY else OwnTVIcon.DOWNLOADS,
                onClick = {
                    val opened = if (installed) {
                        GamesCompanion.open(context)
                    } else {
                        GamesCompanion.openInstallPage(context)
                    }
                    actionFailed = !opened
                },
                modifier = Modifier.focusRequester(firstFocus),
            )
            Text(
                text = stringResource(
                    if (installed) R.string.games_companion_installed else R.string.games_companion_not_installed,
                ),
                fontSize = 17.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 12.dp),
            )
        }
        Spacer(Modifier.height(30.dp))
        Text(
            text = stringResource(R.string.games_companion_legal_note),
            fontSize = 15.sp,
            color = colors.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 820.dp),
        )
        if (actionFailed) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.games_companion_action_failed),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
