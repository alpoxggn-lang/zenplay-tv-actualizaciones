package tv.own.owntv.features.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.features.movies.DetailButton
import tv.own.owntv.features.movies.FavoriteCircle
import tv.own.owntv.features.movies.InfoChip
import tv.own.owntv.features.movies.RatingRing
import tv.own.owntv.features.movies.SectionTitle
import tv.own.owntv.features.movies.qualityTag
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.ResumeDialog
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.zenplay.ZenPlayDetailsRepo
import tv.own.owntv.zenplay.ZenPlayProviderInfo
import tv.own.owntv.zenplay.ZenPlayRecommendation

/**
 * ZenPlay's show page on the television, laid out like the reference: poster on the left with the
 * title, ★ rating, quality and synopsis beside it; "Reproducir / Continuar" and "Reproducir desde el
 * inicio", the heart and the users' score ring; a "Temporada N ▾" picker; one row per episode with
 * its picture, "N. Título", synopsis and length; and "Te puede gustar" (five similar shows) at the
 * very end, under the last episode. Long-press an episode for watched state / external player.
 */
@Composable
internal fun ZenPlaySeriesDetails(
    series: SeriesEntity,
    vm: SeriesViewModel,
    onFullscreen: () -> Unit,
    onChildFocused: () -> Unit,
    restoreFocus: Boolean,
    onRestored: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val loading by vm.episodesLoading.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val selectedSeason by vm.selectedSeason.collectAsStateWithLifecycle()
    val lastPlayedId by vm.lastPlayedEpisodeId.collectAsStateWithLifecycle()
    val episodeProgress by vm.episodeProgress.collectAsStateWithLifecycle()
    val completedIds by vm.completedEpisodeIds.collectAsStateWithLifecycle()
    val nextUpId by vm.nextUpEpisodeId.collectAsStateWithLifecycle()
    val resumeMode by vm.resumeMode.collectAsStateWithLifecycle()
    val externalPlayerOn by vm.externalPlayerOn.collectAsStateWithLifecycle()
    val goFullscreen: () -> Unit = { if (!externalPlayerOn) onFullscreen() }
    val scope = rememberCoroutineScope()

    val repo = remember { ZenPlayDetailsRepo.shared }
    var info by remember(series.id) { mutableStateOf<ZenPlayProviderInfo?>(null) }
    var similar by remember(series.id) { mutableStateOf<List<ZenPlayRecommendation>>(emptyList()) }
    LaunchedEffect(series.id) {
        try {
            info = repo.seriesInfo(series)
            similar = repo.recommendations(series, info?.genres.orEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            similar = emptyList()
        }
    }

    BackHandler { vm.closeSeries() }

    val seasons = episodes.map { it.seasonNumber }.distinct().sorted()
    val activeSeason = if (selectedSeason in seasons) selectedSeason else seasons.firstOrNull() ?: 1
    val seasonEpisodes = episodes.filter { it.seasonNumber == activeSeason }.sortedBy { it.episodeNumber }
    val firstEpisode = episodes.sortedWith(tv.own.owntv.core.content.EpisodeOrder.episodes).firstOrNull()
    val target = nextUpId?.let { id -> episodes.firstOrNull { it.id == id } } ?: firstEpisode
    val targetPos = target?.let { episodeProgress[it.id]?.takeIf { p -> p.durationMs > 1L }?.positionMs } ?: 0L
    val resuming = targetPos >= 10_000L

    var resumePrompt by remember { mutableStateOf<Pair<EpisodeEntity, Long>?>(null) }
    var contextEpisode by remember { mutableStateOf<EpisodeEntity?>(null) }
    var seasonMenu by remember { mutableStateOf(false) }
    val startEpisode: (EpisodeEntity) -> Unit = { ep ->
        scope.launch {
            val pos = vm.savedPositionMs(ep)
            when {
                resumeMode == SettingsRepository.ResumeMode.ASK && pos >= 10_000 -> resumePrompt = ep to pos
                resumeMode == SettingsRepository.ResumeMode.AUTO && pos > 0 -> { vm.playEpisode(ep, pos); goFullscreen() }
                else -> { vm.playEpisode(ep, 0); goFullscreen() }
            }
        }
    }

    val playFocus = remember { FocusRequester() }
    val lastFocus = remember { FocusRequester() }
    val seasonFocus = remember { FocusRequester() }
    val pageScroll = remember(series.id) { androidx.compose.foundation.ScrollState(0) }
    LaunchedEffect(series.id, target != null) {
        if (restoreFocus) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        runCatching { playFocus.requestFocus() }
    }
    // Back from the player: land on the episode that was playing (or the Play button).
    LaunchedEffect(restoreFocus, seasonEpisodes.size) {
        if (!restoreFocus) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        val ok = seasonEpisodes.any { it.id == lastPlayedId } && runCatching { lastFocus.requestFocus() }.isSuccess
        if (!ok) runCatching { playFocus.requestFocus() }
        onRestored()
    }

    val poster = series.posterUrl?.takeIf { it.isNotBlank() } ?: info?.poster
    val backdrop = info?.backdrop ?: series.backdropUrl?.takeIf { it.isNotBlank() } ?: poster
    val plot = series.plot?.takeIf { it.isNotBlank() } ?: info?.plot
    val rating = series.rating?.takeIf { it > 0 } ?: info?.rating
    val year = series.year?.takeIf { it > 0 }?.toString() ?: info?.year
    val quality = qualityTag(series.name) ?: "HD"

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF05060C))
            .onFocusChanged { if (it.hasFocus) onChildFocused() }
            .trapAllFocusExit()
            .focusGroup(),
    ) {
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.40f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color(0xFF05060C), 0.5f to Color(0xE605060C), 1f to Color(0x8005060C)),
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(pageScroll)
                .padding(horizontal = 32.dp, vertical = 20.dp)
                .focusGroup(),
        ) {
            // Back arrow, top-left.
            FocusableSurface(
                onClick = { vm.closeSeries() },
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                focusedContainerColor = colors.primary,
                unfocusedContainerColor = Color.White.copy(alpha = 0.12f),
                contentAlignment = Alignment.Center,
            ) { focused ->
                tv.own.owntv.ui.components.OwnTVIcon(
                    icon = OwnTVIcon.BACK,
                    tint = if (focused) colors.onPrimary else Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Box(
                    Modifier
                        .width(200.dp)
                        .height(300.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceContainerHigh)
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                ) {
                    if (!poster.isNullOrBlank()) {
                        AsyncImage(model = poster, contentDescription = series.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(
                        series.name,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        rating?.let { InfoChip("★ ${"%.1f".format(it)}") }
                        year?.let { InfoChip(it) }
                        info?.age?.let { InfoChip(it) }
                        InfoChip(quality, outlined = true)
                    }
                    if (!plot.isNullOrBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            plot,
                            fontSize = 16.sp,
                            lineHeight = 23.sp,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 1100.dp),
                        )
                    }
                    if (info?.genres?.isNotEmpty() == true) {
                        Spacer(Modifier.height(14.dp))
                        SectionTitle(stringResource(R.string.zenplay_details_genres))
                        Text(info!!.genres.joinToString(" • "), fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (target != null) {
                    DetailButton(
                        label = if (resuming) {
                            stringResource(R.string.zenplay_details_continue_episode, target.seasonNumber, target.episodeNumber)
                        } else if (target.id == firstEpisode?.id) {
                            stringResource(R.string.zenplay_details_from_start)
                        } else {
                            stringResource(R.string.zenplay_details_play_episode, target.seasonNumber, target.episodeNumber)
                        },
                        icon = if (resuming || target.id != firstEpisode?.id) OwnTVIcon.PLAY else OwnTVIcon.REFRESH,
                        primary = true,
                        onClick = { vm.playEpisode(target, if (resuming) targetPos else 0L); goFullscreen() },
                        modifier = Modifier.focusRequester(playFocus),
                    )
                    if (firstEpisode != null && (resuming || target.id != firstEpisode.id)) {
                        DetailButton(
                            label = stringResource(R.string.zenplay_details_from_start),
                            icon = OwnTVIcon.REFRESH,
                            primary = false,
                            onClick = { vm.playEpisode(firstEpisode, 0L); goFullscreen() },
                        )
                    }
                }
                FavoriteCircle(isFavorite = favoriteIds.contains(series.id), onClick = { vm.toggleFavorite(series) })
                rating?.let {
                    RatingRing(it)
                    val pct = (it * 10).toInt()
                    Text(if (pct >= 70) "😊" else if (pct >= 50) "🙂" else "😐", fontSize = 30.sp)
                }
            }

            Spacer(Modifier.height(22.dp))
            when {
                loading && episodes.isEmpty() -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    OwnTVSpinner(sizeDp = 48)
                }
                episodes.isEmpty() -> Text(
                    stringResource(R.string.content_no_episodes),
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
                else -> {
                    // "Temporada N ▾"
                    FocusableSurface(
                        onClick = { if (seasons.size > 1) seasonMenu = true },
                        modifier = Modifier.height(56.dp).focusRequester(seasonFocus),
                        shape = RoundedCornerShape(10.dp),
                        focusedContainerColor = colors.primary,
                        unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
                        contentAlignment = Alignment.Center,
                    ) { focused ->
                        val fg = if (focused) colors.onPrimary else Color.White
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            tv.own.owntv.ui.components.OwnTVIcon(icon = OwnTVIcon.LIST_GRID, tint = fg, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.zenplay_details_season, activeSeason), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = fg)
                            if (seasons.size > 1) {
                                tv.own.owntv.ui.components.OwnTVIcon(icon = OwnTVIcon.CHEVRON_DOWN, tint = fg, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    seasonEpisodes.forEach { ep ->
                        val remote = ep.remoteId
                        val prog = episodeProgress[ep.id]
                        val completed = ep.id in completedIds
                        ZenEpisodeRow(
                            title = "${ep.episodeNumber}. ${episodeDisplayTitle(ep)}",
                            plot = ep.plot,
                            image = remote?.let { info?.episodeImages?.get(it) } ?: series.backdropUrl ?: poster,
                            duration = ep.durationSecs?.takeIf { it > 0 }?.let { clock(it.toLong()) }
                                ?: remote?.let { info?.episodeDurations?.get(it) },
                            progress = prog?.takeIf { !completed && it.durationMs > 0 && it.positionMs > 0 }
                                ?.let { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) },
                            completed = completed,
                            onClick = { startEpisode(ep) },
                            onLongClick = { contextEpisode = ep },
                            modifier = if (ep.id == lastPlayedId) Modifier.focusRequester(lastFocus) else Modifier,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            // "Te puede gustar" — at the very end, under the episodes.
            if (similar.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionTitle(stringResource(R.string.zenplay_details_recommended))
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(similar, key = { it.id }) { rec ->
                        ZenSimilarShow(rec) { vm.openSeriesById(rec.id) }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (seasonMenu) {
        tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = { seasonMenu = false }) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            Column(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF14172B))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                seasons.forEach { number ->
                    val eps = episodes.filter { it.seasonNumber == number }
                    FocusableSurface(
                        onClick = {
                            seasonMenu = false
                            vm.selectSeason(number)
                            scope.launch { kotlinx.coroutines.delay(60); runCatching { seasonFocus.requestFocus() } }
                        },
                        modifier = Modifier.width(320.dp).height(52.dp)
                            .then(if (number == activeSeason) Modifier.focusRequester(first) else Modifier),
                        selected = number == activeSeason,
                        shape = RoundedCornerShape(10.dp),
                        focusedContainerColor = colors.primary,
                        unfocusedContainerColor = Color.Transparent,
                        selectedContainerColor = Color.White.copy(alpha = 0.10f),
                        contentAlignment = Alignment.CenterStart,
                    ) { focused ->
                        val fg = if (focused) colors.onPrimary else Color.White
                        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.zenplay_details_season, number),
                                fontSize = 17.sp,
                                fontWeight = if (number == activeSeason) FontWeight.Bold else FontWeight.Medium,
                                color = fg,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                pluralStringResource(
                                    R.plurals.zenplay_details_episode_count,
                                    eps.size,
                                    eps.size,
                                ),
                                fontSize = 13.sp,
                                color = fg.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }

    contextEpisode?.let { ep ->
        tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = { contextEpisode = null }) {
            EpisodeContextMenu(
                title = stringResource(R.string.content_season_episode_title, ep.seasonNumber, ep.episodeNumber, episodeDisplayTitle(ep)),
                watched = ep.id in completedIds,
                hasTmdbDetails = false,
                canRefetchTmdb = false,
                onShowDetails = { contextEpisode = null },
                onPlayExternal = { contextEpisode = null; vm.playEpisodeExternal(ep) },
                onToggleWatched = {
                    contextEpisode = null
                    if (ep.id in completedIds) vm.markEpisodeUnwatched(ep) else vm.markEpisodeWatched(ep)
                },
                onRefetch = { contextEpisode = null },
                onDismiss = { contextEpisode = null },
            )
        }
    }

    resumePrompt?.let { (ep, pos) ->
        ResumeDialog(
            positionMs = pos,
            onResume = { resumePrompt = null; vm.playEpisode(ep, pos); goFullscreen() },
            onStartOver = { resumePrompt = null; vm.playEpisode(ep, 0); goFullscreen() },
            onDismiss = { resumePrompt = null },
        )
    }

}

/** One episode row: picture, "N. Título", synopsis, length — like the reference. */
@Composable
private fun ZenEpisodeRow(
    title: String,
    plot: String?,
    image: String?,
    duration: String?,
    progress: Float?,
    completed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        focusedContainerColor = colors.primary.copy(alpha = 0.35f),
        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
        contentAlignment = Alignment.CenterStart,
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(170.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (!image.isNullOrBlank()) {
                    AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (focused || completed) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        tv.own.owntv.ui.components.OwnTVIcon(
                            icon = if (completed && !focused) OwnTVIcon.STAR else OwnTVIcon.PLAY,
                            tint = Color.White,
                            filled = true,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f)))
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(4.dp).background(colors.primary))
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!plot.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(plot, fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!duration.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(duration, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.75f))
                }
            }
        }
    }
}

@Composable
private fun ZenSimilarShow(rec: ZenPlayRecommendation, onClick: () -> Unit) {
    val colors = OwnTVTheme.colors
    Column(Modifier.width(140.dp)) {
        FocusableSurface(
            onClick = onClick,
            modifier = Modifier.width(140.dp).height(210.dp),
            shape = RoundedCornerShape(12.dp),
            focusedContainerColor = colors.primary,
            unfocusedContainerColor = colors.surfaceContainerHigh,
            contentAlignment = Alignment.Center,
        ) { _ ->
            if (!rec.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = rec.posterUrl,
                    contentDescription = rec.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Text(rec.name, fontSize = 13.sp, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(rec.name, fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Seconds → "00:42:10". */
private fun clock(secs: Long): String =
    "%02d:%02d:%02d".format(java.util.Locale.ROOT, secs / 3600, (secs % 3600) / 60, secs % 60)
