package tv.own.owntv.mobile.zenplay

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.R
import java.text.DateFormat
import java.util.Date

/** "Vence: 21 sep 2027", or a red "Suscripción vencida", for the plan in use. Nothing while unknown. */
@Composable
fun ZenPlayExpiryChip(modifier: Modifier = Modifier) {
    val account: ZenPlayAccount = koinInject()
    val settings: SettingsRepository = koinInject()
    val activeId by settings.defaultSourceId.collectAsStateWithLifecycle(initialValue = -1L)
    val profileId by settings.activeProfileId.collectAsStateWithLifecycle(initialValue = -1L)
    val expiry by produceState<Long?>(initialValue = null, activeId, profileId) { value = account.expiryMs() }
    val ms = expiry ?: return
    val expired = ms <= System.currentTimeMillis()
    val date = remember(ms) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms)) }
    Text(
        text = if (expired) stringResource(R.string.zenplay_expiry_expired) else stringResource(R.string.zenplay_expiry_until, date),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (expired) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .background(
                if (expired) Color(0xFF8A1C3A) else MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(50),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** "Vence el 21 sep 2027" / "Suscripción vencida" for the plan in use, or null while unknown. */
@Composable
fun rememberZenPlayExpiryText(): String? {
    val account: ZenPlayAccount = koinInject()
    val settings: SettingsRepository = koinInject()
    val activeId by settings.defaultSourceId.collectAsStateWithLifecycle(initialValue = -1L)
    val profileId by settings.activeProfileId.collectAsStateWithLifecycle(initialValue = -1L)
    val expiry by produceState<Long?>(initialValue = null, activeId, profileId) { value = account.expiryMs() }
    val ms = expiry ?: return null
    val date = remember(ms) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms)) }
    return if (ms <= System.currentTimeMillis()) stringResource(R.string.zenplay_expiry_expired)
    else stringResource(R.string.zenplay_expiry_until, date)
}

/** "¿Cerrar sesión?" with what it removes spelled out. */
@Composable
fun ZenPlayLogoutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.zenplay_logout_confirm_title)) },
        text = { Text(stringResource(R.string.zenplay_logout_warning)) },
        confirmButton = {
            TextButton(onClick = { zenPlayLogout(context) }) {
                Text(stringResource(R.string.zenplay_logout_title), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.zenplay_login_cancel)) }
        },
    )
}

/**
 * Sign out: wipes everything ZenPlay stored on this phone — account, profiles, PINs, favourites,
 * downloads and settings — exactly as "Clear data" in Android's app settings would. Android closes
 * the app as part of it; the next launch opens on the sign-in screen.
 */
fun zenPlayLogout(context: Context) {
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
}
