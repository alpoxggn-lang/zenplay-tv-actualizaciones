package tv.own.owntv.mobile.zenplay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LiveRed = Color(0xFFEF4444)

/** The banner at the top of "En vivo" that opens today's sports agenda. */
@Composable
fun ZenSportsAgendaBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Color(0xFF2E5BFF), Color(0xFF7B3FE4))))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("⚽", fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.zenplay_agenda_title), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            Text(stringResource(R.string.zenplay_agenda_banner_hint), fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
        }
        Icon(MobileIcons.KeyboardArrowRight, contentDescription = null, tint = Color.White)
    }
}

/**
 * ZenPlay "Agenda deportiva de hoy" on the phone: what is on today on the playlist's sports
 * channels, in time order with "EN VIVO" on what is airing, then event channels with no guide.
 * Tapping a row opens that channel.
 */
@Composable
fun ZenSportsAgendaScreen(
    onOpenChannel: (channelId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var agenda by remember { mutableStateOf<ZenAgenda?>(null) }
    LaunchedEffect(Unit) {
        agenda = runCatching { ZenSportsAgenda.shared.load() }.getOrDefault(ZenAgenda(emptyList(), emptyList(), emptyList()))
    }
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val today = remember { SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "MX")).format(Date()) }
    val a = agenda
    when {
        a == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ZenPlayLogoLoader(size = 72)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.zenplay_agenda_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        a.items.isEmpty() && a.events.isEmpty() -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.zenplay_agenda_empty),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            val now = System.currentTimeMillis()
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item("date") {
                    Text(
                        today.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                items(a.items, key = { "p" + it.channel.id + "_" + it.startMs }) { item ->
                    AgendaRow(
                        time = clock.format(Date(item.startMs)) + " – " + clock.format(Date(item.stopMs)),
                        live = item.startMs <= now && item.stopMs > now,
                        title = item.title,
                        subtitle = item.channel.name,
                        channel = item.channel,
                        onClick = { onOpenChannel(item.channel.id) },
                    )
                }
                if (a.events.isNotEmpty()) {
                    item("events") {
                        Text(
                            stringResource(R.string.zenplay_agenda_events),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(a.events, key = { "e" + it.id }) { ch ->
                        AgendaRow(
                            time = null,
                            live = false,
                            title = ch.name,
                            subtitle = null,
                            channel = ch,
                            onClick = { onOpenChannel(ch.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(
    time: String?,
    live: Boolean,
    title: String,
    subtitle: String?,
    channel: ChannelEntity,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            tv.own.owntv.mobile.ui.components.ChannelLogoImage(
                url = channel.displayLogoUrl,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                fallback = { Text("⚽", fontSize = 22.sp) },
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (time != null || live) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (time != null) Text(time, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (live) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(LiveRed))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.zenplay_agenda_live), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LiveRed)
                    }
                }
            }
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
