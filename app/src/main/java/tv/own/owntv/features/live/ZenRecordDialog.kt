package tv.own.owntv.features.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.theme.OwnTVTheme
import java.text.SimpleDateFormat
import java.util.Date

/** ZenPlay's red "record" dot. */
@Composable
internal fun ZenRecordDot(size: Int = 14, color: Color = Color(0xFFEF4444)) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

/** "1 h 30 min", "45 min". */
internal fun zenDurationLabel(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

/**
 * ZenPlay: "¿Cuánto tiempo quieres grabar?" — the user picks the length of a live recording.
 * Quick choices (15 min … 4 h), "Hasta que termine el programa" when the guide knows the end, and a
 * custom length adjusted 15 minutes at a time with − / +.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ZenRecordDurationDialog(
    channelName: String,
    programmeEndMs: Long?,
    onPick: (minutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val locale = LocalLocale.current.platformLocale
    val first = remember { FocusRequester() }
    var custom by remember { mutableIntStateOf(60) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val now = System.currentTimeMillis()
    val untilEnd = programmeEndMs?.takeIf { it > now + 60_000L }?.let { ((it - now + 59_999L) / 60_000L).toInt() }

    OwnTVPopup(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF12152A))
                .padding(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZenRecordDot(16)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.zenplay_record_title),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                channelName,
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))

            if (untilEnd != null) {
                val endLabel = SimpleDateFormat("HH:mm", locale).format(Date(programmeEndMs))
                ZenRecordChoice(
                    label = stringResource(R.string.zenplay_record_until_end, endLabel),
                    modifier = Modifier.fillMaxWidth().focusRequester(first),
                    onClick = { onPick(untilEnd) },
                )
                Spacer(Modifier.height(12.dp))
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listOf(15, 30, 45, 60, 90, 120, 180, 240).forEachIndexed { i, minutes ->
                    ZenRecordChoice(
                        label = zenDurationLabel(minutes),
                        modifier = Modifier.width(132.dp)
                            .then(if (i == 0 && untilEnd == null) Modifier.focusRequester(first) else Modifier),
                        onClick = { onPick(minutes) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.zenplay_record_custom),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ZenRecordChoice(label = "−", modifier = Modifier.width(64.dp), onClick = { custom = (custom - 15).coerceAtLeast(15) })
                Text(
                    zenDurationLabel(custom),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(150.dp),
                )
                ZenRecordChoice(label = "+", modifier = Modifier.width(64.dp), onClick = { custom = (custom + 15).coerceAtMost(12 * 60) })
                Spacer(Modifier.width(8.dp))
                ZenRecordChoice(
                    label = stringResource(R.string.zenplay_record_start),
                    modifier = Modifier.width(170.dp),
                    accent = true,
                    onClick = { onPick(custom) },
                )
            }
        }
    }
}

@Composable
private fun ZenRecordChoice(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(12.dp),
        focusedContainerColor = colors.primary,
        unfocusedContainerColor = if (accent) Color(0xFFB91C1C) else Color.White.copy(alpha = 0.08f),
        contentAlignment = Alignment.Center,
    ) { focused ->
        Text(
            label,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = if (focused) colors.onPrimary else Color.White,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}
