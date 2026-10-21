package tv.own.owntv.mobile.ui.screens.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import tv.own.owntv.core.games.GamesCompanion
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileGroup
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.mobile.ui.theme.MobileDimens

/**
 * One safe door to the separately licensed emulator companion.
 *
 * The streaming APK intentionally contains neither emulator cores nor downloadable ROM catalogs.
 * ZenPlay Games owns its own library and lets the customer import software they are allowed to use.
 */
@Composable
fun GamesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = MobileDimens.PagePaddingH,
            vertical = MobileDimens.ScreenPaddingV,
        ),
        verticalArrangement = Arrangement.spacedBy(MobileDimens.GapLarge),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
                Text(
                    text = stringResource(R.string.games_companion_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.games_companion_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            MobileGroup {
                MobileListRow(
                    title = stringResource(
                        if (installed) R.string.games_companion_open else R.string.games_companion_install,
                    ),
                    subtitle = stringResource(
                        if (installed) R.string.games_companion_installed else R.string.games_companion_not_installed,
                    ),
                    subtitleMaxLines = 3,
                    leading = { Icon(MobileIcons.Gamepad, contentDescription = null) },
                    trailing = { Icon(MobileIcons.OpenInNew, contentDescription = null) },
                    onClick = {
                        val opened = if (installed) {
                            GamesCompanion.open(context)
                        } else {
                            GamesCompanion.openInstallPage(context)
                        }
                        actionFailed = !opened
                    },
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.games_companion_legal_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MobileDimens.GapSmall),
            )
            if (actionFailed) {
                Text(
                    text = stringResource(R.string.games_companion_action_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = MobileDimens.GapSmall),
                )
            }
        }
    }
}
