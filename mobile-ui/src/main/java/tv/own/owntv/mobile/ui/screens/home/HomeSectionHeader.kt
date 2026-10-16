package tv.own.owntv.mobile.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.own.owntv.mobile.ui.theme.MobileDimens

/**
 * Home's cinematic rail heading.
 *
 * It deliberately stays separate from the generic [tv.own.owntv.mobile.ui.components.SectionHeader]:
 * library and settings headings retain their denser information-layout treatment, while Home gets
 * one consistent discovery rhythm. The accent is a flat gradient, not a blur or animated effect.
 */
@Composable
internal fun HomeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val actionColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        HomeHeadingCyan
    } else {
        HomeHeadingCyanDark
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = MobileDimens.ScreenPaddingH,
                end = if (actionLabel != null) MobileDimens.GapSmall else MobileDimens.ScreenPaddingH,
                top = if (tablet) MobileDimens.GapLarge else MobileDimens.GapMedium,
                bottom = MobileDimens.GapSmall,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(if (tablet) 28.dp else 24.dp)
                    .background(HomeHeadingAccent, CircleShape),
            )
            Text(
                text = title,
                style = if (tablet) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = MobileDimens.TouchTarget),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = actionColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val HomeHeadingCyan = Color(0xFF53DFFF)
private val HomeHeadingCyanDark = Color(0xFF00677D)
private val HomeHeadingPurple = Color(0xFFA774FF)
private val HomeHeadingAccent = Brush.verticalGradient(listOf(HomeHeadingCyan, HomeHeadingPurple))
