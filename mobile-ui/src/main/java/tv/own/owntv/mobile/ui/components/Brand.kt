package tv.own.owntv.mobile.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.core.brand.AppIconSwitcher
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.theme.glassDialogWindow

/** The applied legacy launcher component, retained for backup compatibility. */
@Composable
fun rememberAppliedIcon(): AppIcon {
    val context = LocalContext.current
    return remember(context) { AppIconSwitcher.applied(context) }
}

/** ZenPlay's original supplied artwork is shared by the launcher and in-app branding. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun BrandMark(icon: AppIcon, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.zenplay_logo),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}

/**
 * ZenPlay's original mark and accessible live wordmark. The text follows the chosen font;
 * its violet tone stays consistent with the blue-purple palette in both themes.
 */
@Composable
fun BrandLockup(markSize: Int, textSize: Int, modifier: Modifier = Modifier, stacked: Boolean = false) {
    val icon = rememberAppliedIcon()
    val scheme = MaterialTheme.colorScheme
    val tvColor = scheme.secondary
    val parts = @Composable {
        BrandMark(icon, markSize.dp)
        Text(
            text = buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(color = scheme.onSurface)) {
                    append(stringResource(R.string.brand_own))
                }
                withStyle(androidx.compose.ui.text.SpanStyle(color = tvColor)) {
                    append(stringResource(R.string.brand_tv))
                }
            },
            fontSize = textSize.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.025).em,
        )
    }
    if (stacked) {
        Column(
            modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((markSize * 0.2f).dp),
        ) { parts() }
    } else {
        Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((markSize * 0.26f).dp),
        ) { parts() }
    }
}

/** A single ZenPlay identity, including in legacy setup flows restored from upstream. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AppIconPicker(selected: AppIcon, onPick: (AppIcon) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BrandMark(AppIcon.DEFAULT, 64.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Settings → App icon: one dialog. It shows the tiles, and after a pick that differs from the
 * launcher's icon the same dialog turns into the restart question, so dialogs never stack.
 */
@Composable
fun AppIconSettingsDialog(chosen: AppIcon, onPick: (AppIcon) -> Unit, onDismiss: () -> Unit) {
    val applied = rememberAppliedIcon()
    var picked by remember { mutableStateOf<AppIcon?>(null) }
    val restartFor = picked
    if (restartFor != null) {
        AppIconRestartDialog(restartFor, onDismiss)
        return
    }
    AlertDialog(
        modifier = Modifier.glassDialogWindow(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_app_icon)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.settings_app_icon_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                AppIconPicker(
                    selected = chosen,
                    onPick = { icon ->
                        onPick(icon)
                        if (icon != applied) picked = icon else onDismiss()
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
    )
}

/**
 * "Restart ZenPlay?" after a new icon was saved (from Settings, or brought in by a restore). Restart now
 * switches the launcher icon and reopens the app through it; Later leaves it to [AppIconSwitcher],
 * which applies it the next time the app is in the background.
 */
@Composable
fun AppIconRestartDialog(icon: AppIcon, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        modifier = Modifier.glassDialogWindow(),
        onDismissRequest = onDismiss,
        icon = { BrandMark(icon, 56.dp) },
        title = { Text(stringResource(R.string.app_icon_restart_title), textAlign = TextAlign.Center) },
        text = { Text(stringResource(R.string.app_icon_restart_message), textAlign = TextAlign.Center) },
        confirmButton = {
            TextButton(onClick = { context.findActivity()?.let { AppIconSwitcher.restartWith(it, icon) } }) {
                Text(stringResource(R.string.app_icon_restart_now))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) } },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
