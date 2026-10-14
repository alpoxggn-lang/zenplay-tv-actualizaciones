package tv.own.owntv.mobile.ui.screens.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.DownloadAction
import tv.own.owntv.mobile.ui.components.DownloadActionButton
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.label
import tv.own.owntv.mobile.zenplay.ZenPlayRecommendation

/*
 * ZenPlay's film/show page for a phone held upright: the reference layout (poster beside the title,
 * facts, synopsis, Play, favourite, the users' score ring, a season picker and episode cards with a
 * picture) restacked into one scrolling column.
 */

private val Accent = Color(0xFFB9A4FF)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ZenDetailHeader(
    title: String,
    poster: String?,
    backdrop: String?,
    facts: List<String>,
    quality: String?,
    plot: String?,
    posterModifier: Modifier = Modifier,
) {
    Box(Modifier.fillMaxWidth()) {
        // Backdrop behind the top of the page, fading into the background.
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            if (!backdrop.isNullOrBlank()) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.15f),
                        0.6f to Color.Black.copy(alpha = 0.55f),
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
            )
        }
        Column(Modifier.fillMaxWidth().padding(top = 96.dp, start = 16.dp, end = 16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Box(
                    posterModifier
                        .width(118.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                ) {
                    if (!poster.isNullOrBlank()) {
                        AsyncImage(
                            model = poster,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        facts.forEach { FactChip(it) }
                        quality?.let { FactChip(it, outlined = true) }
                    }
                }
            }
            if (!plot.isNullOrBlank()) {
                var expanded by remember { mutableStateOf(false) }
                Spacer(Modifier.height(14.dp))
                Text(
                    plot,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }
    }
}

@Composable
private fun FactChip(text: String, outlined: Boolean = false) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .then(
                if (outlined) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.8f), shape)
                else Modifier.background(Color.White.copy(alpha = 0.14f)),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** The big white Play button, full width — the page's one obvious thing to do. */
@Composable
internal fun ZenPlayButton(label: String, onClick: () -> Unit, primary: Boolean = true, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(shape)
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (primary) MobileIcons.PlayArrow else MobileIcons.Restore,
            contentDescription = null,
            tint = if (primary) Color.Black else Color.White,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = if (primary) Color.Black else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A round action with its name under it: favourite, download. */
@Composable
internal fun ZenRoundAction(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { content() }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

/** "Descargar" with its state underneath (Descargando 1, En cola, Eliminar…). */
@Composable
internal fun ZenDownloadAction(
    action: DownloadAction,
    idleLabel: String,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    ZenRoundAction(label = if (action.completed) stringResource(R.string.zenplay_downloaded) else action.label(idleLabel)) {
        DownloadActionButton(
            action = action,
            onDownload = onDownload,
            onRetry = onRetry,
            onDelete = onDelete,
            idleLabel = idleLabel,
        )
    }
}

/** The "74% · Calificación de los usuarios" ring, from a 0–10 rating. */
@Composable
internal fun ZenRatingRing(rating: Double) {
    val pct = (rating * 10).toInt().coerceIn(0, 100)
    val ring = when {
        pct >= 70 -> Color(0xFF21D07A)
        pct >= 50 -> Color(0xFFD2D531)
        else -> Color(0xFFDB2360)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 4.dp.toPx()
                val arc = Size(size.width - w, size.height - w)
                drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, Offset(w / 2, w / 2), arc, style = Stroke(w))
                drawArc(ring, -90f, 360f * pct / 100f, false, Offset(w / 2, w / 2), arc, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text("$pct%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.zenplay_details_user_score),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun ZenSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = Accent,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

/** Genres, director and cast under the buttons. */
@Composable
internal fun ZenCredits(genres: List<String>, director: String?, cast: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 18.dp)) {
        if (genres.isNotEmpty()) {
            ZenSectionTitle(stringResource(R.string.zenplay_details_genres))
            Text(
                genres.joinToString(" • "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (!director.isNullOrBlank()) {
            ZenSectionTitle(stringResource(R.string.zenplay_details_director))
            Text(
                director,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (cast.isNotEmpty()) {
            ZenSectionTitle(stringResource(R.string.zenplay_details_cast))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cast) { name ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                        Box(
                            Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF2A2F55)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            name,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** "Temporada 1 ▾": the season list opens from one button instead of a strip of chips. */
@Composable
internal fun ZenSeasonPicker(
    seasons: List<Int>,
    current: Int?,
    labelFor: @Composable (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    if (seasons.isEmpty() || current == null) return
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .clickable(enabled = seasons.size > 1) { open = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MobileIcons.GridView, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(labelFor(current), fontWeight = FontWeight.Bold, color = Color.White)
            if (seasons.size > 1) {
                Spacer(Modifier.width(8.dp))
                Icon(MobileIcons.KeyboardArrowDown, contentDescription = null, tint = Color.White)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            seasons.forEach { number ->
                DropdownMenuItem(
                    text = { Text(labelFor(number), fontWeight = if (number == current) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onSelect(number) },
                )
            }
        }
    }
}

/** One episode: its picture, "1. Title", the synopsis, the length, and its own download button. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ZenEpisodeCard(
    title: String,
    plot: String?,
    image: String?,
    duration: String?,
    progress: Float?,
    completed: Boolean,
    download: DownloadAction,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(124.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (!image.isNullOrBlank()) {
                AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Icon(
                imageVector = if (completed) MobileIcons.CheckCircle else MobileIcons.PlayCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(28.dp),
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!plot.isNullOrBlank()) {
                Text(
                    plot,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!duration.isNullOrBlank()) {
                Text(duration, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.75f))
            }
        }
        DownloadActionButton(action = download, onDownload = onDownload, onRetry = onRetry, onDelete = onDelete)
    }
}

/** "Te puede gustar": five similar titles from the customer's own catalogue. */
@Composable
internal fun ZenRecommendations(items: List<ZenPlayRecommendation>, onOpen: (ZenPlayRecommendation) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
        ZenSectionTitle(stringResource(R.string.zenplay_details_recommended))
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { (if (it.isSeries) "s" else "m") + it.id }) { item ->
                Column(Modifier.width(112.dp).clickable { onOpen(item) }) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!item.posterUrl.isNullOrBlank()) {
                            AsyncImage(model = item.posterUrl, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(if (item.isSeries) MobileIcons.Theaters else MobileIcons.Movie, contentDescription = null)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(item.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "01:33:00" → "1h 33min"; "45" (minutes) → "45min"; anything else as written. */
internal fun zenCompactDuration(raw: String?): String? {
    val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = s.split(':').mapNotNull { it.trim().toIntOrNull() }
    if (parts.size == 3) {
        val (h, m, _) = parts
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }
    s.toIntOrNull()?.let { return if (it >= 60) "${it / 60}h ${it % 60}min" else "${it}min" }
    return s
}

/** A quality tag from the provider's title ("4K", "FHD", "HD"), when it carries one. */
internal fun zenQualityTag(name: String): String? {
    val n = name.uppercase()
    return when {
        "4K" in n || "UHD" in n || "2160" in n -> "4K"
        "FHD" in n || "1080" in n -> "FHD"
        Regex("\\bHD\\b").containsMatchIn(n) || "720" in n -> "HD"
        else -> null
    }
}
