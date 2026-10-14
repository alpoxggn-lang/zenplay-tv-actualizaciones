package tv.own.owntv.features.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.ui.components.ChannelLogoTile
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.zenplay.ZenAgenda
import tv.own.owntv.zenplay.ZenSportsAgenda
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ZenPlay "Agenda deportiva": today's games and shows on the playlist's sports channels, in time
 * order — "EN VIVO" for what is on now — and, below, event channels that carry no guide. OK on a
 * row watches that channel full screen.
 */
@Composable
internal fun ZenSportsAgendaScreen(
    onWatch: (ChannelEntity, List<ChannelEntity>) -> Unit,
    onBack: () -> Unit,
    onChildFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    var agenda by remember { mutableStateOf<ZenAgenda?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        agenda = runCatching { ZenSportsAgenda.shared.load() }.getOrDefault(ZenAgenda(emptyList(), emptyList(), emptyList()))
    }
    BackHandler { onBack() }
    val first = remember { FocusRequester() }
    LaunchedEffect(agenda) { if (agenda != null) runCatching { first.requestFocus() } }
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val today = remember { SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "MX")).format(Date()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0B0E1C))
            .padding(horizontal = 28.dp, vertical = 20.dp)
            .focusGroup(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚽", fontSize = 26.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.zenplay_agenda_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(today.replaceFirstChar { it.uppercase() }, fontSize = 14.sp, color = Color.White.copy(alpha = 0.65f))
            }
        }
        Spacer(Modifier.height(16.dp))
        val a = agenda
        when {
            a == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    OwnTVSpinner(sizeDp = 48)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.zenplay_agenda_loading), color = Color.White.copy(alpha = 0.7f))
                }
            }
            a.items.isEmpty() && a.events.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.zenplay_agenda_empty),
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            else -> {
                val now = System.currentTimeMillis()
                val firstIsItem = a.items.isNotEmpty()
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (a.items.isNotEmpty()) {
                        itemsIndexed(a.items, key = { _, it -> "p" + it.channel.id + "_" + it.startMs }) { index, item ->
                            val live = item.startMs <= now && item.stopMs > now
                            val focusMod = if (index == 0) Modifier.focusRequester(first) else Modifier
                            AgendaRow(
                                time = clock.format(Date(item.startMs)) + " – " + clock.format(Date(item.stopMs)),
                                live = live,
                                title = item.title,
                                channel = item.channel,
                                onClick = { onWatch(item.channel, a.channels) },
                                modifier = focusMod,
                            )
                        }
                    }
                    if (a.events.isNotEmpty()) {
                        item("events_header") {
                            Text(
                                stringResource(R.string.zenplay_agenda_events),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary,
                                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                            )
                        }
                        itemsIndexed(a.events, key = { _, it -> "e" + it.id }) { index, ch ->
                            val focusMod = if (index == 0 && !firstIsItem) Modifier.focusRequester(first) else Modifier
                            AgendaRow(
                                time = null,
                                live = false,
                                title = ch.name,
                                channel = ch,
                                onClick = { onWatch(ch, a.channels) },
                                modifier = focusMod,
                            )
                        }
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
    channel: ChannelEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        focusedContainerColor = colors.primary.copy(alpha = 0.35f),
        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
        contentAlignment = Alignment.CenterStart,
    ) { _ ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(150.dp)) {
                if (time != null) {
                    Text(time, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                if (live) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ZenRecordDot(size = 8)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.zenplay_agenda_live), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
                    }
                }
            }
            ChannelLogoTile(
                logoUrl = channel.displayLogoUrl,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            ) {
                Text("⚽", fontSize = 22.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (time != null) {
                    Text(channel.name, fontSize = 13.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
