package tv.own.owntv.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.ui.theme.OwnTVTheme

private val SectionCyan = Color(0xFF35C8F1)
private val SectionPurple = Color(0xFF865DF2)

/**
 * One title treatment for every rail on the television Home screen. The short gradient beacon is
 * visible from sofa distance, while the fading hairline keeps wide rows aligned without turning the
 * page into a stack of heavy panels. This is paint-only: it adds no focus target or animation.
 */
@Composable
internal fun HomeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = OwnTVTheme.colors
    Row(
        modifier = modifier.semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(if (prominent) 6.dp else 4.dp)
                .height(if (prominent) 34.dp else 24.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(SectionCyan, SectionPurple))),
        )
        Spacer(Modifier.width(if (prominent) 12.dp else 10.dp))
        Text(
            text = title,
            style = if (prominent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            fontWeight = if (prominent) FontWeight.ExtraBold else FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(14.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            SectionCyan.copy(alpha = if (prominent) 0.42f else 0.28f),
                            SectionPurple.copy(alpha = if (prominent) 0.22f else 0.14f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}
