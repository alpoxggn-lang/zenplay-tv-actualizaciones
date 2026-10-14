package tv.own.owntv.features.movies

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.metadata.MetadataCast
import tv.own.owntv.core.metadata.MetadataImages
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.theme.OwnTVTheme
import java.net.URLEncoder

/**
 * What the provider's Xtream panel knows about one film (`get_vod_info`). Every field is optional:
 * panels fill in very different amounts.
 */
@Immutable
data class ZenPlayVodInfo(
    val plot: String? = null,
    val cast: List<String> = emptyList(),
    val director: String? = null,
    val genres: List<String> = emptyList(),
    val year: String? = null,
    val rating: Double? = null,
    val durationText: String? = null,
    val age: String? = null,
    val backdrop: String? = null,
    val poster: String? = null,
)

/** Fetch `get_vod_info` for [movie] from its own Xtream panel; null for other source types or on error. */
private suspend fun loadVodInfo(movie: MovieEntity, sourceDao: SourceDao, http: HttpClient): ZenPlayVodInfo? =
    withContext(Dispatchers.IO) {
        runCatching {
            val remoteId = movie.remoteId?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val source = sourceDao.getById(movie.sourceId) ?: return@runCatching null
            if (source.type != SourceType.XTREAM) return@runCatching null
            val u = URLEncoder.encode(source.username.orEmpty(), "UTF-8")
            val p = URLEncoder.encode(source.password.orEmpty(), "UTF-8")
            val url = source.url.trimEnd('/') +
                "/player_api.php?username=$u&password=$p&action=get_vod_info&vod_id=" +
                URLEncoder.encode(remoteId, "UTF-8")
            val info = JSONObject(http.getText(url, source.userAgent)).optJSONObject("info")
                ?: return@runCatching null
            fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
                info.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" }
            }
            fun names(raw: String?): List<String> =
                raw?.split(',', '/', '|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val backdrop = info.optJSONArray("backdrop_path")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.firstOrNull { it.startsWith("http") }
            } ?: str("backdrop_path")?.takeIf { it.startsWith("http") }
            ZenPlayVodInfo(
                plot = str("plot", "description"),
                cast = names(str("cast", "actors")).take(12),
                director = str("director"),
                genres = names(str("genre")),
                year = str("releasedate", "release_date", "year")?.take(4),
                rating = str("rating")?.toDoubleOrNull()?.takeIf { it > 0 },
                durationText = str("duration"),
                age = str("age", "mpaa_rating", "certification"),
                backdrop = backdrop,
                poster = str("movie_image", "cover_big")?.takeIf { it.startsWith("http") },
            )
        }.getOrNull()
    }

/**
 * ZenPlay's film page: opened when a film is chosen, instead of starting playback at once.
 * Poster on the left; title, facts, synopsis, genres and director on the right; then Play /
 * Continue, Favourite and the rating ring; the cast underneath. Facts come from the provider's
 * panel (`get_vod_info`) merged with any TMDB data the app already has. Back closes it.
 */
@Composable
fun ZenPlayMovieDetails(
    movie: MovieEntity,
    meta: MetadataCacheEntity?,
    fallbackPoster: String?,
    isFavorite: Boolean,
    resumePositionMs: Long,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** ZenPlay: opens one of the "Te puede gustar" films in place of this one. */
    onOpenMovie: (MovieEntity) -> Unit = {},
) {
    val colors = OwnTVTheme.colors
    val sourceDao = koinInject<SourceDao>()
    val http = koinInject<HttpClient>()
    var info by remember(movie.id) { mutableStateOf<ZenPlayVodInfo?>(null) }
    LaunchedEffect(movie.id) { info = loadVodInfo(movie, sourceDao, http) }
    val movieDao = koinInject<MovieDao>()
    var similar by remember(movie.id) { mutableStateOf<List<MovieEntity>>(emptyList()) }
    LaunchedEffect(movie.id) { similar = similarMovies(movie, movieDao) }
    BackHandler { onBack() }

    val playFocus = remember { FocusRequester() }
    val pageScroll = remember(movie.id) { androidx.compose.foundation.ScrollState(0) }
    LaunchedEffect(movie.id) { runCatching { playFocus.requestFocus() } }

    val tmdbCast = MetadataCast.parse(meta?.castJson)
    val poster = movie.posterUrl?.takeIf { it.isNotBlank() } ?: info?.poster ?: fallbackPoster
        ?: MetadataImages.poster(meta?.posterPath)
    val backdrop = info?.backdrop ?: MetadataImages.backdrop(meta?.backdropPath, size = "w1280")
        ?: movie.backdropUrl?.takeIf { it.isNotBlank() } ?: poster
    val plot = movie.plot?.takeIf { it.isNotBlank() } ?: info?.plot ?: meta?.overview
    val rating = movie.rating?.takeIf { it > 0 } ?: info?.rating ?: meta?.rating
    val year = (movie.year ?: movie.parsedYear)?.toString() ?: info?.year ?: meta?.year?.toString()
    val duration = movie.durationSecs?.takeIf { it > 0 }?.let { secs ->
        val h = secs / 3600; val m = (secs % 3600) / 60
        if (h > 0) "${h}h ${m}min" else "${m}min"
    } ?: info?.durationText?.let { compactDuration(it) }
    val genres = info?.genres?.takeIf { it.isNotEmpty() } ?: jsonNames(meta?.genresJson)
    val castNames = info?.cast?.takeIf { it.isNotEmpty() }
    val castPhotos = tmdbCast.associate { it.name.lowercase() to MetadataImages.profile(it.profilePath) }
    val cast: List<Pair<String, String?>> = castNames?.map { it to castPhotos[it.lowercase()] }
        ?: tmdbCast.map { it.name to MetadataImages.profile(it.profilePath) }
    val quality = qualityTag(movie.name)

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF05060C)).trapAllFocusExit().focusGroup()) {
        // Backdrop on the right, fading into the page.
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.45f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color(0xFF05060C),
                    0.45f to Color(0xF205060C),
                    1f to Color(0x6605060C),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xFF05060C)),
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(pageScroll)
                .padding(horizontal = 32.dp, vertical = 24.dp)
                .focusGroup(),
        ) {
            // Back arrow, top-left — the same as the show page.
            FocusableSurface(
                onClick = onBack,
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
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Box(
                    Modifier
                        .width(210.dp)
                        .height(315.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceContainerHigh)
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                ) {
                    if (!poster.isNullOrBlank()) {
                        AsyncImage(
                            model = poster,
                            contentDescription = movie.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        movie.name,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        rating?.let { InfoChip("★ ${"%.1f".format(it)}") }
                        year?.let { InfoChip(it) }
                        duration?.let { InfoChip(it) }
                        info?.age?.let { InfoChip(it) }
                        genres.take(2).takeIf { it.isNotEmpty() }?.let { InfoChip(it.joinToString(", ")) }
                        quality?.let { InfoChip(it, outlined = true) }
                    }
                    if (!plot.isNullOrBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            plot,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = Color.White.copy(alpha = 0.88f),
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 760.dp),
                        )
                    }
                    if (genres.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        SectionTitle(stringResource(R.string.zenplay_details_genres))
                        Text(genres.joinToString(" • "), fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
                    }
                    info?.director?.let { director ->
                        Spacer(Modifier.height(14.dp))
                        SectionTitle(stringResource(R.string.zenplay_details_director))
                        Text(director, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val resuming = resumePositionMs >= 10_000L
                DetailButton(
                    label = if (resuming) {
                        stringResource(R.string.zenplay_details_continue, formatClock(resumePositionMs))
                    } else {
                        stringResource(R.string.zenplay_details_play)
                    },
                    icon = OwnTVIcon.PLAY,
                    primary = true,
                    onClick = onPlay,
                    modifier = Modifier.focusRequester(playFocus),
                )
                if (resuming) {
                    DetailButton(
                        label = stringResource(R.string.zenplay_details_from_start),
                        icon = OwnTVIcon.REFRESH,
                        primary = false,
                        onClick = onPlayFromStart,
                    )
                }
                FavoriteCircle(isFavorite = isFavorite, onClick = onToggleFavorite)
                rating?.let { RatingRing(it) }
            }

            if (cast.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                SectionTitle(stringResource(R.string.zenplay_details_cast))
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(cast) { (name, photo) -> CastCircle(name, photo) }
                }
            }

            if (similar.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                SectionTitle(stringResource(R.string.zenplay_details_recommended))
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(similar, key = { it.id }) { m -> ZenSimilarPoster(m) { onOpenMovie(m) } }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB9A4FF))
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun InfoChip(text: String, outlined: Boolean = false) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(shape)
            .then(
                if (outlined) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.8f), shape)
                else Modifier.background(Color.White.copy(alpha = 0.14f)),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
internal fun DetailButton(
    label: String,
    icon: OwnTVIcon,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(14.dp),
        focusedContainerColor = colors.primary,
        unfocusedContainerColor = if (primary) Color.White else Color.White.copy(alpha = 0.14f),
        contentAlignment = Alignment.Center,
    ) { focused ->
        val fg = when {
            focused -> colors.onPrimary
            primary -> Color.Black
            else -> Color.White
        }
        Row(
            modifier = Modifier.padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tv.own.owntv.ui.components.OwnTVIcon(icon = icon, tint = fg, filled = true, modifier = Modifier.size(20.dp))
            Text(
                label,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun FavoriteCircle(isFavorite: Boolean, onClick: () -> Unit) {
    val colors = OwnTVTheme.colors
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        shape = CircleShape,
        focusedContainerColor = colors.primary,
        unfocusedContainerColor = Color.White.copy(alpha = 0.14f),
        contentAlignment = Alignment.Center,
    ) { focused ->
        tv.own.owntv.ui.components.OwnTVIcon(
            icon = OwnTVIcon.FAVORITE,
            tint = if (isFavorite) colors.favorite else if (focused) colors.onPrimary else Color.White,
            filled = isFavorite,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** The "74% · Calificación de los usuarios" ring, from a 0–10 rating. */
@Composable
internal fun RatingRing(rating: Double) {
    val pct = (rating * 10).toInt().coerceIn(0, 100)
    val ring = when {
        pct >= 70 -> Color(0xFF21D07A)
        pct >= 50 -> Color(0xFFD2D531)
        else -> Color(0xFFDB2360)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 5.dp.toPx()
                val arcSize = Size(size.width - w, size.height - w)
                drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, Offset(w / 2, w / 2), arcSize, style = Stroke(w))
                drawArc(ring, -90f, 360f * pct / 100f, false, Offset(w / 2, w / 2), arcSize, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text("$pct%", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Text(
            stringResource(R.string.zenplay_details_user_score),
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.width(110.dp),
        )
    }
}

@Composable
private fun CastCircle(name: String, photo: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF2A2F55)),
            contentAlignment = Alignment.Center,
        ) {
            val initials = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
            Text(initials, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (!photo.isNullOrBlank()) {
                AsyncImage(
                    model = photo,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun jsonNames(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
}

/** "01:33:00" → "1h 33min"; anything else is shown as the panel wrote it. */
private fun compactDuration(raw: String): String {
    val parts = raw.split(':').mapNotNull { it.trim().toIntOrNull() }
    if (parts.size == 3) {
        val (h, m, _) = parts
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }
    return raw
}

private fun formatClock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s)
    } else {
        "%d:%02d".format(java.util.Locale.ROOT, m, s)
    }
}

/** A quality tag from the provider's title ("4K", "FHD", "HD"), when it carries one. */
internal fun qualityTag(name: String): String? {
    val n = name.uppercase()
    return when {
        "4K" in n || "UHD" in n || "2160" in n -> "4K"
        "FHD" in n || "1080" in n -> "FHD"
        Regex("\\bHD\\b").containsMatchIn(n) || "720" in n -> "HD"
        "SD" in n.split(' ', '|', '-', '[', ']', '(', ')') -> "SD"
        else -> null
    }
}

/** ZenPlay: five films like [movie] from its own category — best rated first, then closest in year. */
private suspend fun similarMovies(movie: MovieEntity, dao: MovieDao): List<MovieEntity> =
    withContext(Dispatchers.IO) {
        val category = movie.categoryId ?: return@withContext emptyList()
        val source = dao.pagingByCategoryDateAdded(category)
        val page = try {
            when (val r = source.load(androidx.paging.PagingSource.LoadParams.Refresh(null, 60, false))) {
                is androidx.paging.PagingSource.LoadResult.Page -> r.data
                else -> emptyList()
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
            emptyList()
        } finally {
            source.invalidate()
        }
        fun key(n: String) = n.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val ownYear = movie.year ?: movie.parsedYear
        val seen = hashSetOf(key(movie.name))
        page.asSequence()
            .filter { it.id != movie.id }
            .sortedWith(
                compareByDescending<MovieEntity> { it.rating ?: 0.0 }
                    .thenBy { m -> val y = m.year ?: m.parsedYear; if (ownYear != null && y != null) kotlin.math.abs(ownYear - y) else 99 },
            )
            .filter { seen.add(key(it.name)) }
            .take(5)
            .toList()
    }

@Composable
internal fun ZenSimilarPoster(movie: MovieEntity, onClick: () -> Unit) {
    val colors = OwnTVTheme.colors
    Column(Modifier.width(132.dp)) {
        FocusableSurface(
            onClick = onClick,
            modifier = Modifier.width(132.dp).height(198.dp),
            shape = RoundedCornerShape(12.dp),
            focusedContainerColor = colors.primary,
            unfocusedContainerColor = colors.surfaceContainerHigh,
            contentAlignment = Alignment.Center,
        ) { _ ->
            if (!movie.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = movie.posterUrl,
                    contentDescription = movie.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Text(movie.name, fontSize = 13.sp, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(movie.name, fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
