package tv.own.owntv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.own.owntv.R
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.core.brand.AppIconSwitcher

/**
 * The icon colour the launcher shows right now. The in-app logo follows it, not the saved choice, so
 * logo and home-screen icon change together after the restart.
 */
@Composable
fun rememberAppliedIcon(): AppIcon {
    val context = LocalContext.current
    return remember(context) { AppIconSwitcher.applied(context) }
}

/** ZenPlay: every icon choice shows the ZenPlay logo (the robot + play mark with its wordmark). */
@Composable
fun BrandMark(@Suppress("UNUSED_PARAMETER") icon: AppIcon, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.zenplay_logo),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}

/**
 * ZenPlay lockup. The supplied logo already carries the "ZenPlay" wordmark, so the lockup is the
 * logo alone, drawn a little larger than the old mark so the wordmark stays readable.
 */
@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    markSize: Int = 36,
    @Suppress("UNUSED_PARAMETER") textSize: Int = 26,
    stacked: Boolean = false,
) {
    val logoSize = if (stacked) (markSize * 1.8f).dp else (markSize * 1.6f).dp
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        BrandMark(rememberAppliedIcon(), logoSize)
    }
}
